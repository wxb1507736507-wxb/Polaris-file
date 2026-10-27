package com.limao.unzip.engine.progress

import com.limao.unzip.engine.api.ProgressSnapshot
import com.limao.unzip.engine.api.ProgressState
import java.util.concurrent.atomic.AtomicLong

/**
 * 进度节流器：首事件立即发射（保证"首个文件必触发"），其后每 [minIntervalMs] 至多一次；
 * 窗口内的更新聚合进累加器（latest-wins 的路径/状态），终态经 [finish] 无条件发射（"最后一个文件必触发"）。
 *
 * offer* 只做原子累加，不分配事件对象；快照仅在发射时构造。
 * clock 可注入假时钟（B04 用例）。
 */
class ProgressThrottler(
    private val minIntervalMs: Long = 1_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sink: (ProgressSnapshot) -> Unit,
) {
    private val inputBytes = AtomicLong(0)
    private val outputBytes = AtomicLong(0)
    private val entriesDone = AtomicLong(0)

    @Volatile private var inputTotal: Long = ProgressSnapshot.UNKNOWN
    @Volatile private var outputTotal: Long = ProgressSnapshot.UNKNOWN
    @Volatile private var entriesTotal: Long = ProgressSnapshot.UNKNOWN
    @Volatile private var currentPath: String? = null

    private val lock = Any()
    private var lastEmit: Long = Long.MIN_VALUE

    fun setTotals(inputBytesTotal: Long, outputBytesTotal: Long, totalEntries: Long) {
        inputTotal = inputBytesTotal
        outputTotal = outputBytesTotal
        entriesTotal = totalEntries
    }

    fun setCurrentPath(path: String?) {
        currentPath = path
    }

    fun offerInput(deltaBytes: Long) {
        if (deltaBytes > 0) inputBytes.addAndGet(deltaBytes)
        tryEmit()
    }

    fun offerOutput(deltaBytes: Long) {
        if (deltaBytes > 0) outputBytes.addAndGet(deltaBytes)
        tryEmit()
    }

    fun onEntryDone() {
        entriesDone.incrementAndGet()
        tryEmit()
    }

    fun currentEntriesDone(): Long = entriesDone.get()

    /** 终态必达：无条件发射并标记结束。 */
    fun finish(state: ProgressState) {
        synchronized(lock) {
            lastEmit = clock()
            sink(snapshot(state))
        }
    }

    private fun tryEmit() {
        synchronized(lock) {
            val now = clock()
            if (lastEmit == Long.MIN_VALUE || now - lastEmit >= minIntervalMs) {
                lastEmit = now
                sink(snapshot(ProgressState.RUNNING))
            }
        }
    }

    private fun snapshot(state: ProgressState) = ProgressSnapshot(
        state = state,
        inputBytesDone = inputBytes.get(),
        inputBytesTotal = inputTotal,
        outputBytesDone = outputBytes.get(),
        outputBytesTotal = outputTotal,
        entriesDone = entriesDone.get(),
        entriesTotal = entriesTotal,
        currentEntryPath = currentPath,
    )
}
