package com.limao.unzip.io

import com.limao.unzip.engine.api.ArchiveExtractor
import com.limao.unzip.engine.api.ArchiveFormat
import com.limao.unzip.engine.api.ExtractRequest
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.api.ExtractionJob
import com.limao.unzip.engine.api.SeekableInput
import com.limao.unzip.engine.engine.ZipReferenceEngine
import com.limao.unzip.engine.error.EngineErrorCode
import com.limao.unzip.engine.error.EngineException
import com.limao.unzip.engine.extractor.ArchiveExtractorImpl
import com.limao.unzip.native.NativeLibarchiveEngine
import com.limao.unzip.native.XeBridge

/**
 * 引擎路由：
 *  - ZIP → Kotlin 参考引擎（GBK 文件名启发式、行为已被完整测试覆盖）
 *  - 其他格式（7z/tar/gz/…）→ libarchive native 引擎
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
        private val routedNative: Boolean

        init {
            // 复用 UI 预检缓存（点击压缩包时已 scan），消除第三次全量扫描
            val cached = com.limao.unzip.ObjectGraph.cachedProbe
            val info = if (cached != null && request.options.password == null) {
                cached.second
            } else {
                kotlinx.coroutines.runBlocking { probe(request.source) }
            }
            // 加密包参考引擎不支持：加密 zip / 带密码 → 强制 native
            routedNative = info.format != com.limao.unzip.engine.api.ArchiveFormat.ZIP ||
                info.anyEncrypted || request.options.password != null
            delegate = if (routedNative) nativeExtractor!!.submit(request) else zipExtractor.submit(request)
        }

        override val progress = delegate.progress
        override fun cancel() = delegate.cancel()
        override suspend fun await(): ExtractResult = delegate.await()
    }
}
