package com.limao.unzip.engine.api

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** 可创建的归档格式（引擎按此路由）。 */
enum class CreateFormat(val extension: String) {
    ZIP("zip"),
    SEVEN_Z("7z"),
    TAR("tar"),
    TAR_GZ("tar.gz"),
}

data class CompressOptions(
    val chunkSizeBytes: Int = ExtractOptions.DEFAULT_CHUNK_BYTES,
    /** 0-9；参考引擎仅 DEFLATED（0 级=不压缩的 deflate 流）。 */
    val level: Int = 6,
    val progressMinIntervalMs: Long = 1_000L,
    val maxEntryCount: Int = 20_000,
    val onEntryDone: ((EntryDone) -> Unit)? = null,
) {
    init {
        require(level in 0..9) { "level 必须在 0..9" }
        require(chunkSizeBytes >= ExtractOptions.MIN_CHUNK_BYTES && chunkSizeBytes <= ExtractOptions.MAX_CHUNK_BYTES)
        require(chunkSizeBytes and (chunkSizeBytes - 1) == 0)
    }
}

data class CompressRequest(
    val source: CompressSource,
    val output: ArchiveOutput,
    val format: CreateFormat = CreateFormat.ZIP,
    val options: CompressOptions = CompressOptions(),
)

/**
 * 待压缩数据源：scan 枚举条目（复用 EntryCursor：uncomp=源大小，comp=-1，isEnc=false），
 * 引擎按 index 打开数据。目录条目 openStream/openFd 返回 null。
 * 枚举产物由实现物化（有界 maxEntryCount；这是用户选定的源清单，不属于解压红线范畴）。
 */
interface CompressSource : Closeable {
    fun scan(sink: (EntryCursor) -> Unit)

    /** 字节流通道（参考引擎）。流由调用方关闭。目录条目返回 null。 */
    fun openStream(index: Long): InputStream?
}

/** fd 通道（native 引擎）：返回的 fd 所有权转移给调用方。目录条目返回 null。 */
interface FdCompressSource : CompressSource {
    fun openFd(index: Long): Int?
}

/**
 * 归档输出（.part 语义：commit 后才以最终名可见）。
 * 引擎按自身通道需求选择 begin(stream)/beginFd()。
 */
interface ArchiveOutput : Closeable {
    /** 返回归档数据的字节流通道（.part 文件）。 */
    fun begin(): OutputStream

    /** 返回归档数据的 fd 通道（native 直写）。fd 所有权转移给调用方。 */
    fun beginFd(): Int

    /** 归档写完且关闭：.part → 最终名。 */
    fun commit()

    /** 删除 .part。 */
    fun discard()
}

interface CompressionEngine {
    val id: EngineId
    val format: CreateFormat

    /**
     * 阻塞执行压缩；中止经 [EngineListener.shouldAbort] 协作，返回 [ExtractResult.Aborted]。
     * 计数器语义：onInputBytes = 读取的源字节；onOutputBytes = 写入归档的字节。
     * Completed.bytesDone = 已归档的源字节总量。
     */
    fun compress(source: CompressSource, output: ArchiveOutput, listener: EngineListener): ExtractResult
}

interface ArchiveCompressor {
    fun submit(request: CompressRequest): ExtractionJob

    suspend fun compress(request: CompressRequest): ExtractResult
}
