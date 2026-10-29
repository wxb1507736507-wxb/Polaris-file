package com.limao.unzip.native

import com.limao.unzip.engine.api.ArchiveFormat
import com.limao.unzip.engine.api.ArchiveInfo
import com.limao.unzip.engine.api.AbortPoint
import com.limao.unzip.engine.api.EngineId
import com.limao.unzip.engine.api.EngineListener
import com.limao.unzip.engine.api.EntryCursor
import com.limao.unzip.engine.api.EntryErrorPolicy
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.api.ExtractWarning
import com.limao.unzip.engine.api.FdCapableSink
import com.limao.unzip.engine.api.RawFdSource
import com.limao.unzip.engine.api.SeekableInput
import com.limao.unzip.engine.api.WarningCode
import com.limao.unzip.engine.engine.ArchiveSummary
import com.limao.unzip.engine.engine.ExtractPlan
import com.limao.unzip.engine.engine.ExtractionEngine
import com.limao.unzip.engine.error.EngineErrorCode
import com.limao.unzip.engine.error.EngineException

/**
 * libarchive native 引擎适配器：把 JNI 回调模型适配到 ExtractionEngine SPI。
 * 职责：skipBits/中止/双计数器/警告聚合/ExtractResult 组装；批量数据全在 native。
 */
class NativeLibarchiveEngine : ExtractionEngine {

    override val id = EngineId.LIBARCHIVE

    override fun probe(input: SeekableInput): ArchiveInfo {
        val fd = borrowFd(input)
        val fmt = XeBridge.probe(fd)
        // journal 创建需要准确的 entryCount：native 路径在此补一次全量扫描
        // （7z 读头很快；tar.gz 需解压流，暂接受该成本，见 README 已知问题）
        val summary = scan(input) { }
        return ArchiveInfo(mapFormat(fmt), id, summary.entryCount, summary.totalUncompressedBytes, summary.anyEncrypted, solidOf(fmt))
    }

    override fun scan(input: SeekableInput, sink: (EntryCursor) -> Unit): ArchiveSummary {
        val cursor = EntryCursor()
        val sinkObj = object {
            @Suppress("unused")
            fun onEntry(index: Long, path: String, uncomp: Long, comp: Long, isDir: Boolean, mtime: Long) {
                cursor.assign(index, path, uncomp, comp, isDir, false, mtime)
                sink(cursor)
            }
        }
        val ret = XeBridge.scan(borrowFd(input), sinkObj)
        if (ret.isEmpty()) {
            throw EngineException(EngineErrorCode.FORMAT, XeBridge.lastError().ifEmpty { "native 扫描失败" })
        }
        return ArchiveSummary(ret[0], ret[1], ret[2] != 0L)
    }

    override fun extract(input: SeekableInput, plan: ExtractPlan, listener: EngineListener): ExtractResult {
        val started = System.nanoTime()
        val src = input as? RawFdSource
            ?: throw EngineException(EngineErrorCode.IO, "native 引擎需要 RawFdSource")
        val sink = plan.destination as? FdCapableSink
            ?: throw EngineException(EngineErrorCode.IO, "native 引擎需要 FdCapableSink 输出通道")

        val warnings = ArrayList<ExtractWarning>(8)
        var warningsOverflow = 0
        var entriesSeen = 0L
        var abortPoint: AbortPoint? = null
        val open = HashMap<Long, FdCapableSink.FdEntryOutput>()

        fun warn(w: ExtractWarning) {
            if (warnings.size < 64) warnings.add(w) else warningsOverflow++
        }

        val callbacks = object {
            @Suppress("unused")
            fun onEntryStart(index: Long, name: String, expectBytes: Long, isDir: Boolean): Int {
                entriesSeen = maxOf(entriesSeen, index + 1)
                listener.onEntryStart(index, name)
                val out = sink.beginEntryFd(name, expectBytes, 0L, isDir)
                    ?: return -1 // 目标已存在且策略为跳过
                if (isDir) {
                    // 目录条目：Kotlin 已处理，native 不写数据
                    out.commitFd()
                    out.afterFdClosed()
                    return -2
                }
                open[index] = out
                return out.fd
            }

            @Suppress("unused")
            fun onEntryDone(index: Long, name: String, bytes: Long) {
                open.remove(index)?.let {
                    it.commitFd()
                    it.afterFdClosed()
                }
                listener.onEntryDone(index, name, bytes)
            }

            @Suppress("unused")
            fun onEntryAborted(index: Long, name: String, bytes: Long) {
                open.remove(index)?.let {
                    if (plan.options.deleteIncompleteOnAbort) it.discardFd() else it.leavePartFd()
                    it.afterFdClosed()
                }
                abortPoint = AbortPoint(index - 1, name, bytes)
            }

            @Suppress("unused")
            fun onEntrySkipped(index: Long, name: String, reason: Int) {
                when (reason) {
                    1 -> warn(ExtractWarning(WarningCode.ENTRY_NAME_UNSAFE, name.take(64), "非法路径被拒绝"))
                    2 -> listener.onEntryDone(index, "", 0L) // 断点续传 skip：journal/节流对齐
                    3 -> warn(ExtractWarning(WarningCode.CRC_MISMATCH_SKIPPED, name, "数据校验失败，已跳过"))
                }
            }

            @Suppress("unused")
            fun onInputBytes(delta: Long) = listener.onInputBytes(delta)

            @Suppress("unused")
            fun onOutputBytes(delta: Long) = listener.onOutputBytes(delta)

            @Suppress("unused")
            fun shouldAbort(): Boolean = listener.shouldAbort()
        }

        val ret = XeBridge.extract(
            src.fd, callbacks, plan.skipBits,
            plan.options.entryErrorPolicy == EntryErrorPolicy.SKIP_CONTINUE,
            plan.options.password?.takeIf { it.isNotEmpty() },
        )
        val code = ret[0].toInt()
        val entriesDone = ret[1]
        val bytesDone = ret[2]

        return when (code) {
            XeBridge.XE_OK -> ExtractResult.Completed(
                entriesDone = entriesDone,
                bytesDone = bytesDone,
                elapsedMs = (System.nanoTime() - started) / 1_000_000L,
                warnings = warnings,
                warningsOverflow = warningsOverflow,
                entriesTotal = entriesSeen,
                bytesInput = -1,
            )
            XeBridge.XE_ABORTED -> ExtractResult.Aborted(
                entriesDone = entriesDone,
                bytesDone = bytesDone,
                abortPoint = abortPoint ?: AbortPoint(-1, null, 0),
            )
            else -> throw EngineException(
                when (code) {
                    XeBridge.XE_ERR_ENCRYPTED -> EngineErrorCode.ENCRYPTED
                    XeBridge.XE_ERR_READ -> EngineErrorCode.READ
                    XeBridge.XE_ERR_WRITE -> EngineErrorCode.WRITE
                    XeBridge.XE_ERR_LIMIT -> EngineErrorCode.LIMIT
                    else -> EngineErrorCode.FORMAT
                },
                XeBridge.lastError().ifEmpty { "native 解压失败 code=$code" },
            )
        }
    }

    private fun borrowFd(input: SeekableInput): Int =
        (input as? RawFdSource)?.fd
            ?: throw EngineException(EngineErrorCode.IO, "native 引擎需要 RawFdSource")

    companion object {
        private const val ProgressUnknown = -1L

        fun mapFormat(fmt: Int): ArchiveFormat = when (fmt) {
            XeBridge.FMT_ZIP -> ArchiveFormat.ZIP
            XeBridge.FMT_SEVEN_Z -> ArchiveFormat.SEVEN_Z
            XeBridge.FMT_TAR -> ArchiveFormat.TAR
            XeBridge.FMT_RAR -> ArchiveFormat.RAR
            XeBridge.FMT_GZ -> ArchiveFormat.GZ
            else -> ArchiveFormat.UNKNOWN
        }

        private fun solidOf(fmt: Int): Boolean = fmt == XeBridge.FMT_SEVEN_Z
    }
}
