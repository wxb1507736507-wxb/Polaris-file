package com.limao.unzip.engine.api

import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/** 可随机读的压缩包输入（fd/FileChannel 的最小抽象，native 引擎直接消费 fd）。 */
interface SeekableInput : Closeable {
    val sizeBytes: Long

    /** 返回实际读取字节数；越过文件尾返回 -1。 */
    fun pread(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/** native 引擎的输入通道：暴露可借用的原始 fd（所有权仍归本对象）。 */
interface RawFdSource : SeekableInput {
    val fd: Int
}

/**
 * native 引擎的输出通道：per-entry 返回可写 fd（.part 语义由实现保证），
 * 批量数据由 native 直写 fd，不跨 JNI。
 */
interface FdCapableSink : OutputSink {
    /**
     * 返回值：null=目标已存在且策略为跳过（native 侧跳过该条目）；
     * [FdEntryOutput.fd] 供 native 写入。
     */
    fun beginEntryFd(name: String, expectBytes: Long, mtimeMs: Long, isDirectory: Boolean): FdEntryOutput?

    interface FdEntryOutput {
        val fd: Int

        /** 数据写完且校验通过：关 fd + 原子收尾（rename）。 */
        fun commitFd()

        /** 删除 .part。 */
        fun discardFd()

        /** 保留 .part。 */
        fun leavePartFd()

        /** native 已 close(fd) 后由 Kotlin 侧释放伴随资源。 */
        fun afterFdClosed()
    }
}

/**
 * 条目输出目标。实现负责 .part 语义：写入期间以临时名存在，[OutputSink.EntryOutput.commit]
 * 后才以最终名可见——"已解压的文件保持完整"由文件系统级保证。
 */
interface OutputSink : Closeable {
    /**
     * 开始一个条目的输出。
     * @return null 表示目标已存在且策略为跳过（调用方按已完成处理）。
     */
    fun beginEntry(name: String, expectBytes: Long, mtimeMs: Long, isDirectory: Boolean): EntryOutput?

    interface EntryOutput {
        fun write(src: ByteArray, offset: Int, length: Int)

        /** 校验通过：.part → 最终名（原子收尾）。 */
        fun commit()

        /** 删除 .part。 */
        fun discard()

        /** 保留 .part（用户选择保留半截文件时）。 */
        fun leaveAsPart()
    }
}

/**
 * 引擎回调。实现必须线程安全且 [shouldAbort]/[onInputBytes]/[onOutputBytes] 无锁快
 * （它们在每个数据块边界被调用）。形参一律基本类型，禁止装箱。
 */
interface EngineListener {
    fun shouldAbort(): Boolean
    fun onInputBytes(deltaBytes: Long)
    fun onOutputBytes(deltaBytes: Long)
    fun onEntryStart(index: Long, path: String)
    fun onEntryDone(index: Long, path: String, bytesWritten: Long)
}

interface ExtractionJob {
    /** conflate 后的进度流（线程安全），UI 可从任意线程 collect。 */
    val progress: Flow<ProgressSnapshot>

    /** 幂等：置位中止标志，引擎在下一个检查点（条目间/块间）退出。 */
    fun cancel()

    suspend fun await(): ExtractResult
}

interface ArchiveExtractor {
    suspend fun probe(source: SeekableInput): ArchiveInfo

    fun submit(request: ExtractRequest): ExtractionJob

    suspend fun extract(request: ExtractRequest): ExtractResult
}
