package com.limao.unzip.engine.journal

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * 断点恢复日志：固定头 + 追加式完成记录（每条 16B：条目序号 + 保留字段）。
 *
 * 写入节奏：记录先进 8KB 应用缓冲，缓冲满、积压超 [FLUSH_PENDING] 或距上次 flush 超
 * [FLUSH_INTERVAL_MS] 时才落盘 + force——10 万条目的大包不会退化成 10 万次 fsync。
 *
 * 容错解析（I05）：尾部截断的半条记录被忽略；条目序号越界视为损坏尾，解析停在最后
 * 一条合法记录；头损坏则 [open] 返回 null（上层按全新任务处理）。
 *
 * 内存约束：完成状态以位图（LongArray）表示，10 万条目仅 12.5KB，随会话释放，
 * Java 侧不出现 per-entry 集合（F5 红线）。
 */
class RecoveryJournal private constructor(
    private val channel: FileChannel,
    val entryCount: Long,
    private val clock: () -> Long,
) : AutoCloseable {

    private val lock = Any()
    private val bitmap = LongArray(((entryCount + 63) / 64).toInt())
    private var completedCount = 0L

    @Volatile private var markedCompleted: Boolean = false
    private var pending = 0
    private var lastFlush = 0L
    private var writePos = 0L
    private val appBuffer = ByteBuffer.allocate(APP_BUFFER_BYTES)

    fun isCompleted(index: Long): Boolean = synchronized(lock) {
        if (index !in 0 until entryCount) return false
        val word = (index / 64).toInt()
        val bit = 1L shl (index % 64).toInt()
        (bitmap[word] and bit) != 0L
    }

    fun completedCount(): Long = synchronized(lock) { completedCount }

    fun markedCompleted(): Boolean = markedCompleted

    /** 引擎消费的跳过位图（拷贝，调用方持有）。 */
    fun skipBits(): LongArray = synchronized(lock) { bitmap.copyOf() }

    fun record(index: Long) {
        synchronized(lock) {
            if (markedCompleted) return
            if (index !in 0 until entryCount) return
            val word = (index / 64).toInt()
            val bit = 1L shl (index % 64).toInt()
            if ((bitmap[word] and bit) != 0L) return
            bitmap[word] = bitmap[word] or bit
            completedCount++
            appendRecord(index, 0L)
            pending++
            maybeFlush()
        }
    }

    private fun appendRecord(a: Long, b: Long) {
        if (appBuffer.remaining() < REC_BYTES) drainBuffer()
        appBuffer.putLong(a)
        appBuffer.putLong(b)
    }

    private fun maybeFlush() {
        val now = clock()
        if (pending >= FLUSH_PENDING || now - lastFlush >= FLUSH_INTERVAL_MS) {
            flushLocked()
        }
    }

    private fun flushLocked() {
        drainBuffer()
        if (channel.isOpen) channel.force(true)
        pending = 0
        lastFlush = clock()
    }

    private fun drainBuffer() {
        if (appBuffer.position() > 0) {
            appBuffer.flip()
            while (appBuffer.hasRemaining()) {
                // 定位写：open() 的恢复会话不依赖 channel position（定位读不推进它）
                val n = channel.write(appBuffer, writePos)
                writePos += n.toLong()
            }
            appBuffer.clear()
        }
    }

    /** 任务成功：写入哨兵记录并关闭。 */
    fun markCompletedAndClose() {
        synchronized(lock) {
            appendRecord(COMPLETED_SENTINEL, 0L)
            flushLocked()
            markedCompleted = true
            channel.close()
        }
    }

    /** 中止/失败：保留已写记录供续传，关闭。 */
    fun abandon() {
        synchronized(lock) {
            runCatching { flushLocked() }
            runCatching { channel.close() }
        }
    }

    override fun close() = abandon()

    companion object {
        const val REC_BYTES = 16
        const val VERSION: Int = 1
        const val HEADER_BYTES = 16
        const val MAX_ENTRIES = 1_000_000L
        const val FLUSH_INTERVAL_MS = 200L
        const val FLUSH_PENDING = 512
        const val APP_BUFFER_BYTES = 8 * 1024

        val COMPLETED_SENTINEL = Long.MIN_VALUE
        private val MAGIC = byteArrayOf('L'.code.toByte(), 'J'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte())

        fun create(file: File, entryCount: Long, clock: () -> Long = System::currentTimeMillis): RecoveryJournal {
            require(entryCount in 1..MAX_ENTRIES) { "entryCount 超界: $entryCount" }
            val channel = FileChannel.open(
                file.toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.TRUNCATE_EXISTING,
            )
            val header = ByteBuffer.allocate(HEADER_BYTES)
            header.put(MAGIC)
            header.putShort(VERSION.toShort())
            header.putShort(0)
            header.putLong(entryCount)
            header.flip()
            while (header.hasRemaining()) channel.write(header)
            channel.force(true)
            return RecoveryJournal(channel, entryCount, clock).apply {
                synchronized(this.lock) {
                    writePos = HEADER_BYTES.toLong()
                    lastFlush = clock()
                }
            }
        }

        /** 容错打开：头损坏/不存在返回 null；尾部损坏截断到最近合法记录。 */
        fun open(file: File, clock: () -> Long = System::currentTimeMillis): RecoveryJournal? {
            if (!file.exists()) return null
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)
            try {
                val header = ByteBuffer.allocate(HEADER_BYTES)
                if (readFully(channel, header, 0) < HEADER_BYTES) {
                    channel.close(); return null
                }
                header.flip()
                val magic = ByteArray(4); header.get(magic)
                if (!magic.contentEquals(MAGIC)) { channel.close(); return null }
                val version = header.short.toInt()
                if (version != VERSION) { channel.close(); return null }
                header.short // reserved
                val entryCount = header.long
                if (entryCount !in 1..MAX_ENTRIES) { channel.close(); return null }

                val journal = RecoveryJournal(channel, entryCount, clock)
                val rec = ByteBuffer.allocate(REC_BYTES)
                var pos = HEADER_BYTES.toLong()
                while (true) {
                    rec.clear()
                    if (readFully(channel, rec, pos) < REC_BYTES) break
                    rec.flip()
                    val a = rec.long
                    if (a == COMPLETED_SENTINEL) { journal.markedCompleted = true; break }
                    if (a !in 0 until entryCount) break
                    journal.applyRecord(a)
                    pos += REC_BYTES
                }
                synchronized(journal.lock) {
                    // 追加写从最后一条合法记录之后开始（定位读不推进 channel position）
                    journal.writePos = pos
                    journal.lastFlush = clock()
                }
                return journal
            } catch (t: Throwable) {
                runCatching { channel.close() }
                return null
            }
        }

        private fun RecoveryJournal.applyRecord(index: Long) {
            val word = (index / 64).toInt()
            val bit = 1L shl (index % 64).toInt()
            if ((bitmap[word] and bit) == 0L) {
                bitmap[word] = bitmap[word] or bit
                completedCount++
            }
        }

        private fun readFully(channel: FileChannel, buffer: ByteBuffer, position: Long): Int {
            var done = 0
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, position + done)
                if (n < 0) break
                done += n
            }
            return done
        }
    }
}
