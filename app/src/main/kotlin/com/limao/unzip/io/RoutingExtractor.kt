package com.limao.unzip.io

import com.limao.unzip.engine.api.AbortPoint
import com.limao.unzip.engine.api.ArchiveExtractor
import com.limao.unzip.engine.api.ArchiveFormat
import com.limao.unzip.engine.api.ExtractRequest
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.api.ExtractionJob
import com.limao.unzip.engine.api.ProgressSnapshot
import com.limao.unzip.engine.api.ProgressState
import com.limao.unzip.engine.api.RawFdSource
import com.limao.unzip.engine.api.SeekableInput
import com.limao.unzip.engine.dispatch.EngineDispatchers
import com.limao.unzip.engine.engine.ZipReferenceEngine
import com.limao.unzip.engine.error.EngineErrorCode
import com.limao.unzip.engine.error.EngineException
import com.limao.unzip.engine.error.ErrorCatalog
import com.limao.unzip.engine.extractor.ArchiveExtractorImpl
import com.limao.unzip.engine.progress.ProgressThrottler
import com.limao.unzip.native.NativeLibarchiveEngine
import com.limao.unzip.native.UnrarExtractor
import com.limao.unzip.native.XeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 引擎路由：
 *  - ZIP → Kotlin 参考引擎（GBK 文件名启发式、行为已被完整测试覆盖）
 *  - 其他格式（7z/tar/gz/…）→ libarchive native 引擎
 *  - 加密 RAR → 官方 unrar 引擎（libarchive 无 RAR 解密能力）
 *  - native 不可用（libxe 缺失/JVM 单测）→ 回退参考引擎，非 ZIP 给出结构化错误
 */
class RoutingExtractor : ArchiveExtractor {

    private val zipExtractor = ArchiveExtractorImpl(ZipReferenceEngine())
    private val nativeExtractor = if (XeBridge.available) {
        ArchiveExtractorImpl(NativeLibarchiveEngine())
    } else {
        null
    }

    override suspend fun probe(source: SeekableInput): com.limao.unzip.engine.api.ArchiveInfo {
        return if (nativeExtractor != null && source is com.limao.unzip.engine.api.RawFdSource) {
            try {
                val info = nativeExtractor.probe(source)
                if (info.format == ArchiveFormat.UNKNOWN) zipProbe(source) else info
            } catch (e: EngineException) {
                if (e.code == EngineErrorCode.IO) zipProbe(source) else throw e
            }
        } else {
            zipProbe(source)
        }
    }

    override fun submit(request: ExtractRequest): ExtractionJob {
        val preferNative = nativeExtractor != null && request.source is com.limao.unzip.engine.api.RawFdSource &&
            request.destination is com.limao.unzip.engine.api.FdCapableSink
        return if (preferNative) {
            // 路由判定在 probe 内做：submit 走惰性包装，首次 await/progress 前先探测
            RoutingJob(request)
        } else {
            zipExtractor.submit(request)
        }
    }

    override suspend fun extract(request: ExtractRequest): ExtractResult = submit(request).await()

    private suspend fun zipProbe(source: SeekableInput) = zipExtractor.probe(source)

    private inner class RoutingJob(request: ExtractRequest) : ExtractionJob {
        private val delegate: ExtractionJob

        init {
            // 复用 UI 预检缓存（点击压缩包时已 scan），消除第三次全量扫描
            val cached = com.limao.unzip.ObjectGraph.cachedProbe
            val info = if (cached != null && request.options.password == null) {
                cached.second
            } else {
                kotlinx.coroutines.runBlocking { probe(request.source) }
            }
            // 加密包参考引擎不支持：加密 zip / 带密码 → 强制 native
            val routedNative = info.format != com.limao.unzip.engine.api.ArchiveFormat.ZIP ||
                info.anyEncrypted || request.options.password != null
            // libarchive 无法解密 RAR 数据（上游未实现）：加密 RAR 走官方 unrar
            delegate = if (info.format == ArchiveFormat.RAR && info.anyEncrypted) {
                UnrarJob(request)
            } else if (routedNative) {
                nativeExtractor!!.submit(request)
            } else {
                zipExtractor.submit(request)
            }
        }

        override val progress = delegate.progress
        override fun cancel() = delegate.cancel()
        override suspend fun await(): ExtractResult = delegate.await()
    }

    /** 加密 RAR：unrar 直解到目录，逐条目回调进度。 */
    private inner class UnrarJob(private val request: ExtractRequest) : ExtractionJob {
        private val state = MutableStateFlow<ProgressSnapshot?>(null)
        private val throttler = ProgressThrottler(request.options.progressMinIntervalMs) { state.value = it }
        private val cancelFlag = AtomicBoolean(false)
        private val scope = CoroutineScope(SupervisorJob() + EngineDispatchers.WORKERS)
        private val deferred: Deferred<ExtractResult> = scope.async { run() }

        private fun run(): ExtractResult {
            val sink = request.destination as? AndroidFileSink
                ?: return ExtractResult.Failed(ErrorCatalog.providerIo("加密 RAR 暂仅支持本地目录输出"), 0)
            val src = request.source as? RawFdSource
                ?: return ExtractResult.Failed(ErrorCatalog.providerIo("加密 RAR 需要 fd 输入"), 0)
            throttler.setTotals(
                inputBytesTotal = src.sizeBytes,
                outputBytesTotal = ProgressSnapshot.UNKNOWN,
                totalEntries = ProgressSnapshot.UNKNOWN,
            )
            var currentName = ""
            val (code, entriesDone, bytesDone) = UnrarExtractor.extract(
                src,
                sink.rootDir,
                request.options.password,
                UnrarExtractor.Cbs(
                    shouldAbort = { cancelFlag.get() },
                    onEntryStart = { _, name, _ -> currentName = name; throttler.setCurrentPath(name) },
                    onEntryDone = { _, _, _ -> throttler.onEntryDone() },
                    onOutput = { throttler.offerOutput(it) },
                ),
            )
            runCatching { src.close() }
            runCatching { request.destination.close() }
            val result: ExtractResult = when {
                code == 0 -> ExtractResult.Completed(
                    entriesDone = entriesDone,
                    bytesDone = bytesDone,
                    elapsedMs = 0L,
                    warnings = emptyList(),
                    warningsOverflow = 0,
                    entriesTotal = entriesDone,
                    bytesInput = -1L,
                )
                UnrarExtractor.isAborted(code) -> ExtractResult.Aborted(
                    entriesDone = entriesDone,
                    bytesDone = bytesDone,
                    abortPoint = AbortPoint(entriesDone - 1, currentName.ifEmpty { null }, 0L),
                )
                else -> ExtractResult.Failed(
                    UnrarExtractor.mapUserError(code, UnrarExtractor.lastError()),
                    entriesDone,
                )
            }
            throttler.finish(
                when (result) {
                    is ExtractResult.Completed -> ProgressState.COMPLETED
                    is ExtractResult.Aborted -> ProgressState.ABORTED
                    is ExtractResult.Failed -> ProgressState.FAILED
                }
            )
            return result
        }

        override val progress: Flow<ProgressSnapshot> = state.filterNotNull()
        override fun cancel() = cancelFlag.set(true)
        override suspend fun await(): ExtractResult = deferred.await().also { scope.cancel() }
    }
}
