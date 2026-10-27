package com.limao.unzip.engine.engine

import com.limao.unzip.engine.api.CreateFormat
import com.limao.unzip.engine.api.EngineId
import com.limao.unzip.engine.api.EngineListener
import com.limao.unzip.engine.api.ExtractResult
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 纯 Kotlin 参考 ZIP 压缩器（DEFLATED，UTF-8 文件名）。
 * 契约与解压侧一致：双计数器（input=源字节，output=归档字节，经 ArchiveOutput 计数回调？——
 * 参考路径 output 由 ZipOutputStream 写入，无法逐字节计数，Completed.bytesInput=-1）、
 * 中止检查点=条目边界+每块边界、.part+commit 收尾。
 */
class ZipReferenceCompressor : com.limao.unzip.engine.api.CompressionEngine {

    override val id = EngineId.REFERENCE_ZIP
    override val format = CreateFormat.ZIP

    override fun compress(
        source: com.limao.unzip.engine.api.CompressSource,
        output: com.limao.unzip.engine.api.ArchiveOutput,
        listener: EngineListener,
    ): ExtractResult {
        val started = System.nanoTime()
        // 物化源清单（有界；源枚举需要两遍：scan 元数据 + open 数据）
        val metas = ArrayList<Meta>(128)
        source.scan { c -> metas.add(Meta(c.pathString(), c.uncompressedSize, c.mtimeMs, c.isDirectory)) }

        var entriesDone = 0L
        var bytesDone = 0L
        val buf = ByteArray(64 * 1024)

        val stream = output.begin()
        val zos = ZipOutputStream(stream.buffered())
        try {
            for ((index, meta) in metas.withIndex()) {
                // 检查点 1：条目边界
                if (listener.shouldAbort()) {
                    zos.close()
                    output.discard()
                    return ExtractResult.Aborted(entriesDone, bytesDone, com.limao.unzip.engine.api.AbortPoint(index - 1L, null, 0))
                }
                listener.onEntryStart(index.toLong(), meta.relPath)
                val entry = ZipEntry(if (meta.isDir) meta.relPath + "/" else meta.relPath)
                entry.time = meta.mtime
                zos.putNextEntry(entry)
                if (!meta.isDir) {
                    source.openStream(index.toLong())?.use { ins ->
                        while (true) {
                            // 检查点 2：块边界
                            if (listener.shouldAbort()) {
                                zos.close()
                                output.discard()
                                return ExtractResult.Aborted(
                                    entriesDone, bytesDone,
                                    com.limao.unzip.engine.api.AbortPoint(index - 1L, meta.relPath, 0),
                                )
                            }
                            val n = ins.read(buf)
                            if (n < 0) break
                            zos.write(buf, 0, n)
                            listener.onInputBytes(n.toLong())
                            bytesDone += n
                        }
                    }
                }
                zos.closeEntry()
                listener.onEntryDone(index.toLong(), meta.relPath, if (meta.isDir) 0 else meta.size)
                entriesDone++
            }
            zos.finish()
        } finally {
            runCatching { zos.close() }
        }
        output.commit()
        return ExtractResult.Completed(
            entriesDone = entriesDone,
            bytesDone = bytesDone,
            elapsedMs = (System.nanoTime() - started) / 1_000_000L,
            warnings = emptyList(),
            warningsOverflow = 0,
            entriesTotal = metas.size.toLong(),
            bytesInput = -1, // 归档字节数（参考路径无法精确逐字节计数）
        )
    }

    private data class Meta(val relPath: String, val size: Long, val mtime: Long, val isDir: Boolean)
}
