package com.limao.unzip.io

import com.limao.unzip.engine.api.ArchiveCompressor
import com.limao.unzip.engine.api.CompressRequest
import com.limao.unzip.engine.api.CreateFormat
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.api.ExtractionJob
import com.limao.unzip.engine.api.FdCompressSource
import com.limao.unzip.engine.engine.ZipReferenceCompressor
import com.limao.unzip.engine.error.EngineErrorCode
import com.limao.unzip.engine.error.EngineException
import com.limao.unzip.engine.extractor.ArchiveCompressorImpl
import com.limao.unzip.native.NativeCompressEngine
import com.limao.unzip.native.XeBridge
import java.util.concurrent.ConcurrentHashMap

/**
 * 压缩引擎路由（镜像解压侧）：
 *  - ZIP → Kotlin 参考压缩器（UTF-8 名，行为由 JVM 测试覆盖）
 *  - 7z / tar / tar.gz → libarchive native（需 FdCompressSource + fd 输出通道）
 *  - native 不可用 → 结构化错误
 */
class RoutingCompressor : ArchiveCompressor {

    private val zipImpl = ArchiveCompressorImpl(ZipReferenceCompressor())
    private val nativeImpls = ConcurrentHashMap<CreateFormat, ArchiveCompressorImpl>()

    override fun submit(request: CompressRequest): ExtractionJob = implFor(request).submit(request)

    override suspend fun compress(request: CompressRequest): ExtractResult = submit(request).await()

    private fun implFor(request: CompressRequest): ArchiveCompressorImpl = when (request.format) {
        CreateFormat.ZIP -> zipImpl
        else -> {
            if (!XeBridge.available || request.source !is FdCompressSource) {
                throw EngineException(EngineErrorCode.METHOD, "此格式需要 native 引擎（当前不可用）：${request.format}")
            }
            nativeImpls.computeIfAbsent(request.format) { ArchiveCompressorImpl(NativeCompressEngine(it)) }
        }
    }
}
