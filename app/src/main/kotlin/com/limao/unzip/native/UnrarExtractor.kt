package com.limao.unzip.native

import com.limao.unzip.engine.api.AbortPoint
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.api.ProgressSnapshot
import com.limao.unzip.engine.api.ProgressState
import com.limao.unzip.engine.api.RawFdSource
import com.limao.unzip.engine.error.ErrorCatalog
import com.limao.unzip.engine.error.UserError
import com.limao.unzip.engine.progress.ProgressThrottler
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 官方 unrar 桥（加密 RAR3/RAR5 专用）：libarchive 无法解密任何 RAR 数据，
 * 该路径直接消费 options.password，逐条目向 /destDir 解压（unrar 自管路径与时间戳）。
 */
object UnrarExtractor {

    /** ERAR_* 与 XE_ABORTED 之外的结果码经 [mapResult] 归一。 */
    private const val CODE_ABORTED = 1L

    data class Cbs(
        val shouldAbort: () -> Boolean,
        val onEntryStart: (index: Long, name: String, expectBytes: Long) -> Unit,
        val onEntryDone: (index: Long, name: String, bytes: Long) -> Unit,
        val onOutput: (delta: Long) -> Unit,
    )

    fun extract(
        source: RawFdSource,
        destDir: File,
        password: String?,
        cbs: Cbs,
    ): Triple<Int, Long, Long> {
        val callbacks = object {
            @Suppress("unused")
            fun shouldAbort(): Boolean = cbs.shouldAbort()

            @Suppress("unused")
            fun onEntryStart(index: Long, name: String, expectBytes: Long) = cbs.onEntryStart(index, name, expectBytes)

            @Suppress("unused")
            fun onEntryDone(index: Long, name: String, bytes: Long) = cbs.onEntryDone(index, name, bytes)

            @Suppress("unused")
            fun onOutput(delta: Long) = cbs.onOutput(delta)
        }
        val ret = XeBridge.unrarExtract(source.fd, destDir.absolutePath, password, callbacks)
        return Triple(ret[0].toInt(), ret[1], ret[2])
    }

    fun lastError(): String = XeBridge.unrarLastError()

    fun mapUserError(code: Int, detail: String): UserError = when (code) {
        22 -> ErrorCatalog.needsPassword()          // ERAR_MISSING_PASSWORD
        24 -> ErrorCatalog.wrongPassword(1)         // ERAR_BAD_PASSWORD
        else -> ErrorCatalog.archiveCorrupt(detail.ifEmpty { "unrar err=$code" })
    }

    fun isAborted(code: Int): Boolean = code.toLong() == CODE_ABORTED
}
