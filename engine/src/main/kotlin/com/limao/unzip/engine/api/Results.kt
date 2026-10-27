package com.limao.unzip.engine.api

import com.limao.unzip.engine.error.UserError

enum class WarningCode { ENTRY_NAME_UNSAFE, CRC_MISMATCH_SKIPPED, UNSUPPORTED_METHOD_SKIPPED, ENTRY_EXISTS_SKIPPED }

/** per-entry 非致命问题。结果中的 warnings 有界（≤64 条），超出部分聚合为计数。 */
data class ExtractWarning(val code: WarningCode, val entryPath: String?, val detail: String)

/** 单条目完成事件（回调参数为借予语义，调用方不得长期持有；累计统计请用计数器）。 */
data class EntryDone(val index: Long, val path: String, val bytesWritten: Long)

data class AbortPoint(
    val completedEntryIndex: Long,
    val partialPath: String?,
    val partialBytes: Long,
)

sealed interface ExtractResult {
    data class Completed(
        val entriesDone: Long,
        val bytesDone: Long,
        val elapsedMs: Long,
        val warnings: List<ExtractWarning>,
        val warningsOverflow: Int,
        val entriesTotal: Long,
        val bytesInput: Long,
    ) : ExtractResult

    data class Aborted(
        val entriesDone: Long,
        val bytesDone: Long,
        val abortPoint: AbortPoint,
    ) : ExtractResult

    data class Failed(
        val error: UserError,
        val entriesDone: Long,
    ) : ExtractResult
}

enum class OverwritePolicy { REPLACE, SKIP, FAIL }

enum class EntryErrorPolicy { ABORT, SKIP_CONTINUE }

enum class ProgressMode { DETAILED, INDETERMINATE }

fun interface EntryFilter {
    /** 在扫描阶段对每个条目回调一次；cursor 为复用实例，返回 true 表示选中解压。 */
    fun test(cursor: EntryCursor): Boolean

    companion object {
        val ALL: EntryFilter = EntryFilter { true }
    }
}
