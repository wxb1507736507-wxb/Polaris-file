package com.limao.unzip.engine.api

import kotlin.math.min

enum class ProgressState { RUNNING, ABORTING, COMPLETED, ABORTED, FAILED }

enum class ProgressBasis { OUTPUT_BYTES, ENTRY_COUNT, INDETERMINATE }

/**
 * 进度双轨制：inputBytes = 引擎实际消费的压缩包字节；outputBytes = 成功写入目标的解压后字节。
 * 两者压缩比差异可达 1:20 且 solid 格式存在块级滞后，禁止交叉换算。
 */
data class ProgressSnapshot(
    val state: ProgressState,
    val inputBytesDone: Long,
    val inputBytesTotal: Long,
    val outputBytesDone: Long,
    val outputBytesTotal: Long,
    val entriesDone: Long,
    val entriesTotal: Long,
    val currentEntryPath: String?,
) {
    companion object {
        const val UNKNOWN: Long = -1L
    }
}

/** 百分比基准选择规则（引擎侧一次性决定，UI 不自行换算 input/output）。 */
fun ProgressSnapshot.basis(): ProgressBasis = when {
    outputBytesTotal > 0 -> ProgressBasis.OUTPUT_BYTES
    entriesTotal > 0 -> ProgressBasis.ENTRY_COUNT
    else -> ProgressBasis.INDETERMINATE
}

enum class ArchiveFormat { ZIP, SEVEN_Z, TAR, GZ, RAR, CAB, ISO, UNKNOWN }

enum class EngineId(val isNative: Boolean) {
    /** 纯 Kotlin 参考实现（ZIP/未加密），用于无 NDK 环境与测试基线。 */
    REFERENCE_ZIP(false),
    LIBARCHIVE(true),
    SEVEN_ZIP(true),
}

data class ArchiveInfo(
    val format: ArchiveFormat,
    val engineId: EngineId,
    val entryCount: Long,
    val totalUncompressedBytes: Long,
    val anyEncrypted: Boolean,
    val solid: Boolean,
)

data class ArchiveEntryInfo(
    val path: String,
    val uncompressedSize: Long,
    val compressedSize: Long,
    val isDirectory: Boolean,
    val isEncrypted: Boolean,
    val mtimeMs: Long,
)

/**
 * 引擎在每次回调前【原地覆写】的游标（Cursor 复用模式，规避每条目一次对象分配）。
 * 调用方不得缓存引用或跨回调持有；确需保留时调用 [materialize]。
 */
class EntryCursor {
    var index: Long = -1
        internal set
    var uncompressedSize: Long = -1
        internal set
    var compressedSize: Long = -1
        internal set
    var isDirectory: Boolean = false
        internal set
    var isEncrypted: Boolean = false
        internal set
    var mtimeMs: Long = 0L
        internal set

    private var pathLen = 0
    private val pathChars = CharArray(MAX_PATH_CHARS)

    fun pathString(): String = String(pathChars, 0, pathLen)

    fun materialize(): ArchiveEntryInfo = ArchiveEntryInfo(
        path = pathString(),
        uncompressedSize = uncompressedSize,
        compressedSize = compressedSize,
        isDirectory = isDirectory,
        isEncrypted = isEncrypted,
        mtimeMs = mtimeMs,
    )

    /** 引擎在每次回调前调用（跨模块：native 适配器也复用此游标）。 */
    fun assign(
        index: Long,
        path: String?,
        uncomp: Long,
        comp: Long,
        isDir: Boolean,
        isEnc: Boolean,
        mtimeMs: Long,
    ) {
        this.index = index
        this.uncompressedSize = uncomp
        this.compressedSize = comp
        this.isDirectory = isDir
        this.isEncrypted = isEnc
        this.mtimeMs = mtimeMs
        if (path == null) {
            pathLen = 0
            return
        }
        val n = min(path.length, MAX_PATH_CHARS)
        path.toCharArray(pathChars, 0, 0, n)
        pathLen = n
    }

    companion object {
        const val MAX_PATH_CHARS = 256
    }
}
