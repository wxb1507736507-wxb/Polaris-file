package com.limao.unzip.engine.progress

import com.limao.unzip.engine.api.ProgressState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** B04：发射频率 ≤1Hz（fake clock），首事件立即、终态必达、窗口内计数聚合。 */
class ProgressThrottlerTest {

    @Test
    fun `throttles to at most one emission per interval and aggregates counters`() {
        var now = 0L
        val emissions = mutableListOf<Pair<Long, Long>>() // (clock, inputBytesDone)
        val throttler = ProgressThrottler(minIntervalMs = 1_000, clock = { now }, sink = {
            emissions.add(now to it.inputBytesDone)
        })
        throttler.setTotals(100, 100, 10)

        throttler.offerInput(10)          // t=0  首事件立即发射
        now = 500
        throttler.offerInput(20)          // 窗口内，聚合不发射
        now = 900
        throttler.offerOutput(5)
        now = 1_000
        throttler.offerInput(30)          // 窗口到期，发射聚合值 60
        now = 1_200
        throttler.onEntryDone()           // 窗口内
        now = 1_300
        throttler.finish(ProgressState.COMPLETED)  // 终态无条件发射

        assertEquals(listOf(0L, 1_000L, 1_300L), emissions.map { it.first })
        assertEquals(10L, emissions[0].second)     // 首发即当前值
        assertEquals(60L, emissions[1].second)     // 窗口内聚合
        assertEquals(60L, emissions[2].second)
    }

    @Test
    fun `running emissions are at least interval apart`() {
        var now = 0L
        val times = mutableListOf<Long>()
        val throttler = ProgressThrottler(minIntervalMs = 100, clock = { now }, sink = { times.add(now) })
        for (tick in 0 until 5_000) {
            now = tick.toLong()
            throttler.offerInput(1)
        }
        // 任意相邻两次发射间隔 >= 100ms
        for (i in 1 until times.size) {
            assertTrue(times[i] - times[i - 1] >= 100, "interval ${times[i - 1]}→${times[i]}")
        }
        assertTrue(times.size in 2..60, "emission count=${times.size}")
    }

    @Test
    fun `first emission carries state RUNNING even before any bytes`() {
        var now = 5L
        val states = mutableListOf<com.limao.unzip.engine.api.ProgressSnapshot>()
        val throttler = ProgressThrottler(minIntervalMs = 1_000, clock = { now }, sink = { states.add(it) })
        throttler.onEntryDone()
        assertEquals(1, states.size)
        assertEquals(ProgressState.RUNNING, states[0].state)
        assertEquals(1L, states[0].entriesDone)
    }
}
