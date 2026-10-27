package com.limao.unzip.engine.engine

import com.limao.unzip.engine.api.ArchiveFormat
import com.limao.unzip.engine.api.ArchiveInfo
import com.limao.unzip.engine.api.EngineId
import com.limao.unzip.engine.api.EngineListener
import com.limao.unzip.engine.api.EntryCursor
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.api.WarningCode
import com.limao.unzip.engine.api.ExtractWarning
import com.limao.unzip.engine.error.EngineErrorCode.FORMAT
import com.limao.unzip.engine.error.EngineErrorCode.READ
import com.limao.unzip.engine.error.EngineErrorCode.ENCRYPTED
import com.limao.unzip.engine.error.EngineErrorCode.METHOD
import com.limao.unzip.engine.error.EngineErrorCode.CRC
import com.limao.unzip.engine.error.EngineException
import com.limao.unzip.engine.util.PathGuard
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.min

/**
 * 纯 Kotlin 参考 ZIP 引擎（契约 v1.2 的可执行基线，native 引擎就位前的默认实现）。
 *
 * 设计要点（与总体设计一致）：
 * - 中央目录逐条流式遍历，任何时刻只持有一个条目头（禁止整表加载）；
 * - 输入/输出双计数器，统计点分别为"实际消费的压缩字节"与"成功 write 的解压字节"；
 * - 中止检查点在条目边界与每个数据块边界（块间=chunk 间）；
 * - .part + commit 原子收尾由 OutputSink 保证；
 * - 每 entry 零对象分配（CdEntry/Cursor/缓冲均复用），块循环零分配。
 *
 * 限制：仅 ZIP（STORED/DEFLATED），不支持加密（探测到即抛 ENCRYPTED，由上层 fast-fail）。
 */
class ZipReferenceEngine : ExtractionEngine {

    override val id: EngineId = EngineId.REFERENCE_ZIP

    override fun probe(input: SeekableInputCompat): ArchiveInfo {
        val eocd = findEocd(input)
        return ArchiveInfo(ArchiveFormat.ZIP, id, eocd.entryCount, ProgressUnknown, false, false)
    }

    override fun scan(input: SeekableInputCompat, sink: (EntryCursor) -> Unit): ArchiveSummary {
        val eocd = findEocd(input)
        val io = EntryIo()
        val cursor = EntryCursor()
        var pos = eocd.cdOffset
        var count = 0L
        var total = 0L
        var anyEnc = false
        while (count < eocd.entryCount) {
            if (!io.readCdEntry(input, pos)) {
                throw EngineException(FORMAT, "中央目录条目 $count 损坏（偏移 $pos）")
            }
            val e = io.entry
            cursor.assign(count, e.name, e.uncomp, e.comp, e.isDir, e.encrypted, e.mtime)
            sink(cursor)
            if (e.uncomp > 0) total += e.uncomp
            anyEnc = anyEnc || e.encrypted
            pos += e.entryTotalLen
            count++
        }
        return ArchiveSummary(count, total, anyEnc)
    }

    override fun extract(
        input: SeekableInputCompat,
        plan: ExtractPlan,
        listener: EngineListener,
    ): ExtractResult {
        val started = System.nanoTime()
        val eocd = findEocd(input)
        val io = EntryIo()
        val warnings = Warnings()
        val chunkIn = ByteArray(plan.options.chunkSizeBytes)
        val chunkOut = ByteArray(plan.options.chunkSizeBytes)
        val crc = CRC32()
        val policy = plan.options.entryErrorPolicy

        var pos = eocd.cdOffset
        var index = 0L
        var entriesDone = 0L
        var bytesDone = 0L
        var bytesInput = 0L
        var lastDoneIndex = -1L

        while (index < eocd.entryCount) {
            // 检查点 1：条目边界
            if (listener.shouldAbort()) {
                return ExtractResult.Aborted(entriesDone, bytesDone, AbortPointOf(lastDoneIndex, null, 0))
            }
            if (!io.readCdEntry(input, pos)) {
                throw EngineException(FORMAT, "中央目录条目 $index 损坏（偏移 $pos）")
            }
            val e = io.entry

            if (BitOps.get(plan.skipBits, index)) {
                // 断点续传：此前已完成，跳过但仍上报（计数/journal 幂等）
                listener.onEntryDone(index, "", 0)
                entriesDone++
                lastDoneIndex = index
                pos += e.entryTotalLen
                index++
                continue
            }
            if (!BitOps.get(plan.selectedBits, index)) {
                pos += e.entryTotalLen
                index++
                continue
            }

            val rawName = e.name ?: ""
            val safePath = PathGuard.sanitize(rawName)
            if (safePath == null) {
                warnings.add(ExtractWarning(WarningCode.ENTRY_NAME_UNSAFE, rawName.take(64), "非法路径被拒绝"))
                pos += e.entryTotalLen
                index++
                continue
            }
            if (e.encrypted) {
                throw EngineException(ENCRYPTED, "条目已加密", index, safePath)
            }

            listener.onEntryStart(index, safePath)

            if (e.isDir) {
                val out = plan.destination.beginEntry(safePath, 0, e.mtime, true)
                if (out == null) {
                    warnings.add(ExtractWarning(WarningCode.ENTRY_EXISTS_SKIPPED, safePath, "目录已存在"))
                } else {
                    out.commit()
                }
                listener.onEntryDone(index, safePath, 0)
                entriesDone++
                lastDoneIndex = index
                pos += e.entryTotalLen
                index++
                continue
            }

            if (e.method != 0 && e.method != 8) {
                if (policy == com.limao.unzip.engine.api.EntryErrorPolicy.ABORT) {
                    throw EngineException(METHOD, "不支持的压缩方法 ${e.method}", index, safePath)
                }
                warnings.add(ExtractWarning(WarningCode.UNSUPPORTED_METHOD_SKIPPED, safePath, "method=${e.method}"))
                pos += e.entryTotalLen
                index++
                continue
            }

            val out = plan.destination.beginEntry(safePath, e.uncomp, e.mtime, false)
            if (out == null) {
                warnings.add(ExtractWarning(WarningCode.ENTRY_EXISTS_SKIPPED, safePath, "已存在，按策略跳过"))
                listener.onEntryDone(index, safePath, 0)
                entriesDone++
                lastDoneIndex = index
                pos += e.entryTotalLen
                index++
                continue
            }

            crc.reset()
            var written = 0L
            var inputConsumed = 0L
            var abortedMidFile = false
            try {
                val dataStart = io.locateDataStart(input, e, index, safePath)
                if (e.method == 0) {
                    var p = dataStart
                    var remaining = e.comp
                    while (remaining > 0) {
                        // 检查点 2：块边界
                        if (listener.shouldAbort()) {
                            abortedMidFile = true
                            break
                        }
                        val want = min(chunkIn.size.toLong(), remaining).toInt()
                        val n = input.pread(p, chunkIn, 0, want)
                        if (n <= 0) throw EngineException(READ, "数据截断", index, safePath)
                        out.write(chunkIn, 0, n)
                        crc.update(chunkIn, 0, n)
                        listener.onInputBytes(n.toLong())
                        listener.onOutputBytes(n.toLong())
                        written += n
                        inputConsumed += n
                        p += n
                        remaining -= n
                    }
                } else if (e.comp == 0L) {
                    // 0 字节 deflate 条目：CD comp=0，无流可解
                    inputConsumed = 0
                } else {
                    val inf = Inflater(true)
                    try {
                        var fed = 0L
                        var reportedConsumed = 0L
                        var remaining = e.comp
                        while (!inf.finished()) {
                            // 检查点 2：块边界
                            if (listener.shouldAbort()) {
                                abortedMidFile = true
                                break
                            }
                            if (inf.needsInput() && remaining > 0) {
                                val want = min(chunkIn.size.toLong(), remaining).toInt()
                                val n = input.pread(dataStart + fed, chunkIn, 0, want)
                                if (n <= 0) throw EngineException(READ, "压缩数据截断", index, safePath)
                                inf.setInput(chunkIn, 0, n)
                                fed += n
                                remaining -= n
                            }
                            val produced = try {
                                inf.inflate(chunkOut)
                            } catch (ex: DataFormatException) {
                                throw EngineException(FORMAT, "deflate 数据损坏: ${ex.message}", index, safePath)
                            }
                            if (produced > 0) {
                                out.write(chunkOut, 0, produced)
                                crc.update(chunkOut, 0, produced)
                                listener.onOutputBytes(produced.toLong())
                                written += produced
                            } else if (inf.needsInput() && remaining <= 0) {
                                // raw Inflater 对空 final block 需要额外输入字节才报 finished（JDK 已知行为）；
                                // CD 的 uncomp 是权威值：产出已达标即视为流完整
                                if (written == e.uncomp) break
                                throw EngineException(FORMAT, "压缩流提前结束 (comp=${e.comp} uncomp=${e.uncomp})", index, safePath)
                            }
                            // input 计数只在上报"已确认消费"口径，保证单调
                            val consumed = fed - inf.remaining
                            if (consumed > reportedConsumed) {
                                listener.onInputBytes(consumed - reportedConsumed)
                                reportedConsumed = consumed
                            }
                        }
                        inputConsumed = reportedConsumed
                    } finally {
                        inf.end()
                    }
                }
            } catch (t: Throwable) {
                runCatching { out.discard() }
                throw t
            }

            if (abortedMidFile) {
                if (plan.options.deleteIncompleteOnAbort) out.discard() else out.leaveAsPart()
                bytesInput += inputConsumed
                return ExtractResult.Aborted(
                    entriesDone, bytesDone,
                    AbortPointOf(lastDoneIndex, safePath, written),
                )
            }

            val actualCrc = crc.value.toLong() and 0xFFFFFFFFL
            if (e.crc != 0L && actualCrc != e.crc) {
                out.discard()
                if (policy == com.limao.unzip.engine.api.EntryErrorPolicy.ABORT) {
                    throw EngineException(CRC, "CRC 不匹配: 期望=${e.crc} 实际=$actualCrc", index, safePath)
                }
                warnings.add(ExtractWarning(WarningCode.CRC_MISMATCH_SKIPPED, safePath, "期望 crc=${e.crc} 实际=$actualCrc"))
            } else {
                out.commit()
                bytesDone += written
                entriesDone++
                lastDoneIndex = index
                listener.onEntryDone(index, safePath, written)
            }
            bytesInput += inputConsumed

            pos += e.entryTotalLen
            index++
        }

        return ExtractResult.Completed(
            entriesDone = entriesDone,
            bytesDone = bytesDone,
            elapsedMs = (System.nanoTime() - started) / 1_000_000L,
            warnings = warnings.list,
            warningsOverflow = warnings.overflow,
            entriesTotal = eocd.entryCount,
            bytesInput = bytesInput,
        )
    }

    // ---- internals ----

    private class Warnings {
        val list = ArrayList<ExtractWarning>(8)
        var overflow = 0
        fun add(w: ExtractWarning) {
            if (list.size < 64) list.add(w) else overflow++
        }
    }

    private data class Eocd(val entryCount: Long, val cdOffset: Long, val cdSize: Long)

    private fun AbortPointOf(index: Long, path: String?, bytes: Long) =
        com.limao.unzip.engine.api.AbortPoint(index, path, bytes)

    private fun findEocd(input: SeekableInputCompat): Eocd {
        val size = input.sizeBytes
        if (size < 22) throw EngineException(FORMAT, "文件过小，非 ZIP")
        val scanLen = minOf(size, 66_000L).toInt()
        val start = size - scanLen
        val tail = ByteArray(scanLen)
        readFully(input, tail, 0, scanLen, start)
        var off = -1
        var i = scanLen - 22
        while (i >= 0) {
            if (u32(tail, i) == EOCD_SIG) {
                off = i
                break
            }
            i--
        }
        if (off < 0) throw EngineException(FORMAT, "找不到 ZIP 结束记录（EOCD）")
        val absEocd = start + off
        var count = u16(tail, off + 10).toLong()
        var cdSize = u32(tail, off + 12)
        var cdOffset = u32(tail, off + 16)
        val needZip64 = count == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL
        if (needZip64) {
            if (absEocd < 20) throw EngineException(FORMAT, "zip64 定位记录缺失")
            val loc = ByteArray(20)
            readFully(input, loc, 0, 20, absEocd - 20)
            if (u32(loc, 0) != ZIP64_LOC_SIG) throw EngineException(FORMAT, "zip64 定位签名错误")
            val z64 = u64(loc, 8)
            val buf = ByteArray(56)
            readFully(input, buf, 0, 56, z64)
            if (u32(buf, 0) != ZIP64_EOCD_SIG) throw EngineException(FORMAT, "zip64 EOCD 签名错误")
            count = u64(buf, 32)
            cdSize = u64(buf, 40)
            cdOffset = u64(buf, 48)
        }
        if (count < 0 || cdOffset < 0 || cdSize < 0 || cdOffset + cdSize > size) {
            throw EngineException(FORMAT, "中央目录范围越界（文件可能被截断）: off=$cdOffset size=$cdSize total=$size")
        }
        return Eocd(count, cdOffset, cdSize)
    }

    /** 中央目录条目读取器：单条目复用，缓冲只增不减。 */
    private class EntryIo {
        val entry = CdEntry()
        private var buf = ByteArray(256)
        private val hdr30 = ByteArray(30)
        private val utf8Strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        private val gbk = charsetFor("GBK")
        private val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"))

        fun locateDataStart(input: SeekableInputCompat, e: CdEntry, index: Long, path: String): Long {
            readFully(input, hdr30, 0, 30, e.lho)
            if (u32(hdr30, 0) != LFH_SIG) throw EngineException(FORMAT, "本地头签名错误", index, path)
            return e.lho + 30 + u16(hdr30, 26) + u16(hdr30, 28)
        }

        fun readCdEntry(input: SeekableInputCompat, pos: Long): Boolean {
            ensure(46)
            if (readFully(input, buf, 0, 46, pos) < 46) return false
            if (u32(buf, 0) != CDH_SIG) return false
            val flags = u16(buf, 8)
            val method = u16(buf, 10)
            var crc = u32(buf, 16)
            var comp = u32(buf, 20)
            var uncomp = u32(buf, 24)
            val nameLen = u16(buf, 28)
            val extraLen = u16(buf, 30)
            val commentLen = u16(buf, 32)
            var lho = u32(buf, 42)
            val dosTime = u16(buf, 12)
            val dosDate = u16(buf, 14)
            val restLen = nameLen + extraLen + commentLen
            val total = 46 + restLen
            ensure(total)
            if (restLen > 0 && readFully(input, buf, 46, restLen, pos + 46) < restLen) return false

            if (extraLen > 0 && (uncomp == 0xFFFFFFFFL || comp == 0xFFFFFFFFL || lho == 0xFFFFFFFFL)) {
                var p = 46 + nameLen
                val end = p + extraLen
                while (p + 4 <= end) {
                    val id = u16(buf, p)
                    val sz = u16(buf, p + 2)
                    var f = p + 4
                    if (id == 1) {
                        if (uncomp == 0xFFFFFFFFL && f + 8 <= end) { uncomp = u64(buf, f); f += 8 }
                        if (comp == 0xFFFFFFFFL && f + 8 <= end) { comp = u64(buf, f); f += 8 }
                        if (lho == 0xFFFFFFFFL && f + 8 <= end) { lho = u64(buf, f); f += 8 }
                        break
                    }
                    p += 4 + sz
                }
            }

            val e = entry
            e.name = decodeName(buf, 46, nameLen, flags and 0x800 != 0)
            e.method = method
            e.flags = flags
            e.crc = crc
            e.comp = comp
            e.uncomp = uncomp
            e.lho = lho
            e.mtime = dosToEpochMs(dosDate, dosTime)
            e.encrypted = flags and 1 != 0
            e.isDir = e.name?.endsWith('/') == true
            e.entryTotalLen = total.toLong()
            return true
        }

        private fun decodeName(buf: ByteArray, off: Int, len: Int, utf8Flag: Boolean): String {
            if (len == 0) return ""
            if (utf8Flag) return String(buf, off, len, Charsets.UTF_8)
            // 无 UTF-8 标志：先严格 UTF-8，失败按 GBK（中文市场惯例），再退 ISO-8859-1
            try {
                utf8Strict.reset()
                return utf8Strict.decode(ByteBuffer.wrap(buf, off, len)).toString()
            } catch (_: CharacterCodingException) {
                gbk?.let { return String(buf, off, len, it) }
                return String(buf, off, len, Charsets.ISO_8859_1)
            }
        }

        private fun dosToEpochMs(dosDate: Int, dosTime: Int): Long {
            val year = ((dosDate shr 9) and 0x7F) + 1980
            val month = (dosDate shr 5) and 0x0F
            val day = dosDate and 0x1F
            if (month !in 1..12 || day !in 1..31) return 0L
            val cal = calendar
            cal.clear()
            cal.set(year, month - 1, day, (dosTime shr 11) and 0x1F, (dosTime shr 5) and 0x3F, (dosTime and 0x1F) * 2)
            return cal.timeInMillis
        }

        private fun ensure(n: Int) {
            if (buf.size < n) buf = buf.copyOf(maxOf(n, buf.size * 2))
        }
    }

    internal class CdEntry {
        var name: String? = null
        var method = 0
        var flags = 0
        var crc = 0L
        var comp = 0L
        var uncomp = 0L
        var lho = 0L
        var mtime = 0L
        var encrypted = false
        var isDir = false
        var entryTotalLen = 0L
    }

    companion object {
        private const val EOCD_SIG = 0x06054b50L
        private const val ZIP64_LOC_SIG = 0x07064b50L
        private const val ZIP64_EOCD_SIG = 0x06064b50L
        private const val CDH_SIG = 0x02014b50L
        private const val LFH_SIG = 0x04034b50L
        private const val ProgressUnknown = -1L

        private fun charsetFor(name: String) = try {
            java.nio.charset.Charset.forName(name)
        } catch (_: Exception) {
            null
        }

        internal fun readFully(input: SeekableInputCompat, buf: ByteArray, off: Int, len: Int, pos: Long): Int {
            var done = 0
            while (done < len) {
                val n = input.pread(pos + done, buf, off + done, len - done)
                if (n < 0) break
                if (n == 0) continue
                done += n
            }
            return done
        }
    }
}

internal typealias SeekableInputCompat = com.limao.unzip.engine.api.SeekableInput

internal fun u16(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

internal fun u32(b: ByteArray, o: Int): Long =
    (b[o].toInt() and 0xFF).toLong() or
        ((b[o + 1].toInt() and 0xFF).toLong() shl 8) or
        ((b[o + 2].toInt() and 0xFF).toLong() shl 16) or
        ((b[o + 3].toInt() and 0xFF).toLong() shl 24)

internal fun u64(b: ByteArray, o: Int): Long = u32(b, o) or (u32(b, o + 4) shl 32)
