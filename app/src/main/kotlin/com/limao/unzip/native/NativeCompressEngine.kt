package com.limao.unzip.native

import android.os.ParcelFileDescriptor
import com.limao.unzip.engine.api.ArchiveOutput
import com.limao.unzip.engine.api.CompressSource
import com.limao.unzip.engine.api.CreateFormat
import com.limao.unzip.engine.api.EngineId
import com.limao.unzip.engine.api.EngineListener
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.api.FdCompressSource
import com.limao.unzip.engine.error.EngineErrorCode
import com.limao.unzip.engine.error.EngineException
import java.io.File

/**
 * libarchive native 压缩引擎（7z/tar/tar.gz；zip 亦可走此路径但路由默认给参考引擎）。
 * fd 所有权：归档输出 fd 经 detachFd 转移（native 只写不关）；源条目 fd 由 openFd 转移、native 读毕 close。
 */
class NativeCompressEngine(override val format: CreateFormat) : com.limao.unzip.engine.api.CompressionEngine {

    override val id = EngineId.LIBARCHIVE

    override fun compress(source: CompressSource, output: ArchiveOutput, listener: EngineListener): ExtractResult {
        val started = System.nanoTime()
        val fdSource = source as? FdCompressSource
            ?: throw EngineException(EngineErrorCode.IO, "native 压缩引擎需要 FdCompressSource")
        val outFd = output.beginFd()

        // 物化源清单（有界）
        val metas = ArrayList<Meta>(128)
        source.scan { c -> metas.add(Meta(c.pathString(), c.uncompressedSize, c.mtimeMs, c.isDirectory)) }

        var lastDone = -1L
        val callbacks = object {
            @Suppress("unused")
            fun entryCount(): Int = metas.size

            @Suppress("unused")
            fun entryName(index: Long): String = metas[index.toInt()].relPath

            @Suppress("unused")
            fun entrySize(index: Long): Long = metas[index.toInt()].size

            @Suppress("unused")
            fun entryMtime(index: Long): Long = metas[index.toInt()].mtime

            @Suppress("unused")
            fun entryIsDir(index: Long): Boolean = metas[index.toInt()].isDir

            @Suppress("unused")
            fun openEntry(index: Long): Int = fdSource.openFd(index) ?: -1

            @Suppress("unused")
            fun entryDone(index: Long, bytes: Long) {
                lastDone = index
                listener.onEntryDone(index, metas[index.toInt()].relPath, bytes)
            }

            @Suppress("unused")
            fun onInputBytes(delta: Long) = listener.onInputBytes(delta)

            @Suppress("unused")
            fun onOutputBytes(delta: Long) = listener.onOutputBytes(delta)

            @Suppress("unused")
            fun shouldAbort(): Boolean = listener.shouldAbort()
        }

        val formatCode = when (format) {
            CreateFormat.ZIP -> XeBridge.CFMT_ZIP
            CreateFormat.SEVEN_Z -> XeBridge.CFMT_SEVEN_Z
            CreateFormat.TAR -> XeBridge.CFMT_TAR
            CreateFormat.TAR_GZ -> XeBridge.CFMT_TAR_GZ
        }
        val ret = XeBridge.compress(outFd, formatCode, 6, callbacks)
        val code = ret[0].toInt()
        val entriesDone = ret[1]
        val bytesInput = ret[2]
        val bytesOutput = ret[3]

        return when (code) {
            XeBridge.XE_OK -> {
                output.commit()
                ExtractResult.Completed(
                    entriesDone = entriesDone,
                    bytesDone = bytesInput,
                    elapsedMs = (System.nanoTime() - started) / 1_000_000L,
                    warnings = emptyList(),
                    warningsOverflow = 0,
                    entriesTotal = metas.size.toLong(),
                    bytesInput = bytesOutput,
                )
            }
            XeBridge.XE_ABORTED -> {
                output.discard()
                ExtractResult.Aborted(
                    entriesDone = entriesDone,
                    bytesDone = bytesInput,
                    abortPoint = com.limao.unzip.engine.api.AbortPoint(lastDone, null, 0),
                )
            }
            else -> {
                output.discard()
                throw EngineException(
                    when (code) {
                        XeBridge.XE_ERR_READ -> EngineErrorCode.READ
                        XeBridge.XE_ERR_WRITE -> EngineErrorCode.WRITE
                        else -> EngineErrorCode.FORMAT
                    },
                    XeBridge.lastError().ifEmpty { "native 压缩失败 code=$code" },
                )
            }
        }
    }

    private data class Meta(val relPath: String, val size: Long, val mtime: Long, val isDir: Boolean)
}
