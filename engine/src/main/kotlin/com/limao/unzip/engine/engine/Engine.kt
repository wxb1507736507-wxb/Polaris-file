package com.limao.unzip.engine.engine

import com.limao.unzip.engine.api.ArchiveInfo
import com.limao.unzip.engine.api.EngineId
import com.limao.unzip.engine.api.EngineListener
import com.limao.unzip.engine.api.EntryCursor
import com.limao.unzip.engine.api.ExtractOptions
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.api.OutputSink
import com.limao.unzip.engine.api.SeekableInput

data class ArchiveSummary(
    val entryCount: Long,
    val totalUncompressedBytes: Long,
    val anyEncrypted: Boolean,
)

/**
 * 执行计划（一次性传入引擎）。选中/跳过均为位图：
 * 10 万条目 ≈ 12.5KB，是 Java 侧唯一允许的 per-entry 数据结构（F5 红线的例外）。
 */
data class ExtractPlan(
    val destination: OutputSink,
    val selectedBits: LongArray,
    val skipBits: LongArray?,
    val options: ExtractOptions,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

interface ExtractionEngine {
    val id: EngineId

    /** 轻量探测：格式/条目数（不做全量扫描）。 */
    fun probe(input: SeekableInput): ArchiveInfo

    /** 流式扫描：逐条目回调（cursor 原地复用），native 汇总 count/total。 */
    fun scan(input: SeekableInput, sink: (EntryCursor) -> Unit): ArchiveSummary

    /**
     * 阻塞执行解压；必须在工作线程调用。中止通过 [EngineListener.shouldAbort] 协作完成，
     * 返回 [ExtractResult.Aborted] 而非抛异常。致命错误抛 [com.limao.unzip.engine.error.EngineException]。
     */
    fun extract(input: SeekableInput, plan: ExtractPlan, listener: EngineListener): ExtractResult
}

object BitOps {
    fun get(bits: LongArray?, index: Long): Boolean {
        if (bits == null || index < 0) return false
        val i = index.toInt()
        if (i / 64 >= bits.size) return false
        return bits[i / 64] and (1L shl (i % 64)) != 0L
    }

    fun set(bits: LongArray, index: Long) {
        val i = index.toInt()
        bits[i / 64] = bits[i / 64] or (1L shl (i % 64))
    }

    fun wordsFor(entryCount: Long): Int = ((entryCount + 63) / 64).toInt()
}
