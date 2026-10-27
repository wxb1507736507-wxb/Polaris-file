package com.limao.unzip.engine.journal

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** I05：journal 容错解析 + 写入节奏 + 断点恢复位图。 */
class RecoveryJournalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `records survive reopen and build skip bitmap`() {
        val f = tmp.newFile("a.journal")
        val j = RecoveryJournal.create(f, entryCount = 100)
        for (i in 0 until 10) j.record(i.toLong())
        j.abandon()

        val r = RecoveryJournal.open(f)!!
        assertEquals(10L, r.completedCount())
        for (i in 0 until 10) assertTrue(r.isCompleted(i.toLong()), "record $i")
        for (i in 10 until 100) assertFalse(r.isCompleted(i.toLong()))
        assertFalse(r.markedCompleted())
    }

    @Test
    fun `completed sentinel is visible after reopen`() {
        val f = tmp.newFile("b.journal")
        val j = RecoveryJournal.create(f, entryCount = 5)
        j.record(1); j.record(3)
        j.markCompletedAndClose()

        val r = RecoveryJournal.open(f)!!
        assertTrue(r.markedCompleted())
        assertEquals(2L, r.completedCount())
    }

    @Test
    fun `truncated tail record is ignored (crash during write)`() {
        val f = tmp.newFile("c.journal")
        val j = RecoveryJournal.create(f, entryCount = 1000)
        for (i in 0 until 100) j.record(i.toLong())
        j.abandon()

        val bytes = f.readBytes()
        // 砍掉 7 字节：最后一条记录残缺
        f.writeBytes(bytes.copyOf(bytes.size - 7))

        val r = RecoveryJournal.open(f)!!
        assertEquals(99L, r.completedCount())  // 最后一条不完整，被丢弃
    }

    @Test
    fun `out-of-range index stops replay but keeps valid prefix`() {
        val f = tmp.newFile("d.journal")
        val j = RecoveryJournal.create(f, entryCount = 10)
        j.record(0); j.record(1); j.record(2)
        j.abandon()
        // 追加一条越界记录：解析应停在越界处
        f.appendBytes(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))

        val r = RecoveryJournal.open(f)!!
        assertEquals(3L, r.completedCount())
        assertFalse(r.isCompleted(4))
    }

    @Test
    fun `corrupted header returns null`() {
        val f = tmp.newFile("e.journal")
        val j = RecoveryJournal.create(f, entryCount = 5)
        j.record(0)
        j.abandon()
        val bytes = f.readBytes()
        bytes[1] = 'X'.code.toByte()
        f.writeBytes(bytes)
        assertNull(RecoveryJournal.open(f))
    }

    @Test
    fun `unflushed buffer is lost on crash but abandon flushes everything`() {
        val f = tmp.newFile("g.journal")
        var now = 0L
        val j = RecoveryJournal.create(f, entryCount = 10_000, clock = { now })
        for (i in 0 until 600) j.record(i.toLong())   // 时钟冻结：只有缓冲满/积压阈值触发的部分落盘
        // 不 abandon，模拟进程被杀
        val crashedView = RecoveryJournal.open(f)!!
        // 至少 512（触发 FLUSH_PENDING 的部分）可见，且一定少于 600
        assertTrue(crashedView.completedCount() in 512..599, "crash view=${crashedView.completedCount()}")

        j.abandon()  // 正常收尾补 flush
        val final = RecoveryJournal.open(f)!!
        assertEquals(600L, final.completedCount())
    }
}
