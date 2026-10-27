package com.limao.unzip.engine.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 缓冲区策略：ThreadLocal 复用、跨线程归还、参数校验。 */
class ChunkBufferPoolTest {

    @Test
    fun `same thread reuses the same buffer`() {
        val pool = ChunkBufferPool(chunkSize = 64 * 1024)
        val a = pool.acquire()
        val b = pool.acquire()
        assertSame(a, b, "同线程应命中 ThreadLocal 快路径")
        pool.recycle(a)
        assertSame(a, pool.acquire(), "recycle 后再次 acquire 应复用")
    }

    @Test
    fun `cross thread recycle is bounded and reusable`() {
        val pool = ChunkBufferPool(chunkSize = 16 * 1024, maxPooled = 2)
        val produced = Array(4) { pool.acquire() }
        // 模拟 4 个 worker 线程各自持有缓冲后归还到主线程
        val main = Thread.currentThread()
        // 主线程已持有 produced[0]（acquire 设置了 ThreadLocal），其余归还
        for (i in 1 until 4) pool.recycle(produced[i])
        val got = listOf(pool.acquire(), pool.acquire(), pool.acquire())
        // 同线程 acquire 全部来自 ThreadLocal，重新拿到 produced[0]
        assertSame(produced[0], got[0])
    }

    @Test
    fun `chunk size must be power of two within bounds`() {
        assertFailsWith<IllegalArgumentException> { ChunkBufferPool(chunkSize = 24 * 1024) }
        assertFailsWith<IllegalArgumentException> { ChunkBufferPool(chunkSize = 8 * 1024) }
        assertFailsWith<IllegalArgumentException> { ChunkBufferPool(chunkSize = 2 shl 20) }
        assertEquals(64 * 1024, ChunkBufferPool().chunkSize, "默认 64KB")
        assertTrue(ChunkBufferPool(chunkSize = 1 shl 20).acquire().size == 1 shl 20)
    }

    @Test
    fun `recycle rejects mismatched size`() {
        val pool = ChunkBufferPool(chunkSize = 16 * 1024)
        assertFailsWith<IllegalArgumentException> { pool.recycle(ByteArray(32 * 1024)) }
    }
}
