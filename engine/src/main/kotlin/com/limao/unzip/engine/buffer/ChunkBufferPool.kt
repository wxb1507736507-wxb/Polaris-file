package com.limao.unzip.engine.buffer

import java.util.ArrayDeque

/**
 * 固定分块缓冲池（默认 64KB，2 的幂，16KB..1MB）。
 * ThreadLocal 快路径 + 有界共享栈兜底；worker 线程内一个缓冲用到底，跨线程归还不丢失。
 * 注：native 引擎的批量数据不经 Java（零拷贝直写 fd），此池服务于 SAF 桥接与
 * 预览流等 Java 侧数据面。
 */
class ChunkBufferPool(
    val chunkSize: Int = DEFAULT_CHUNK,
    val maxPooled: Int = 8,
) {
    init {
        require(chunkSize >= MIN_CHUNK && chunkSize <= MAX_CHUNK) { "chunkSize 必须在 $MIN_CHUNK..$MAX_CHUNK" }
        require(chunkSize and (chunkSize - 1) == 0) { "chunkSize 必须是 2 的幂" }
        require(maxPooled in 1..64) { "maxPooled 必须在 1..64" }
    }

    private val local = object : ThreadLocal<ByteArray?>() {
        override fun initialValue(): ByteArray? = null
    }
    private val shared = ArrayDeque<ByteArray>()

    fun acquire(): ByteArray {
        local.get()?.let { return it }
        val pooled = synchronized(shared) { shared.pollLast() }
        val buf = pooled ?: ByteArray(chunkSize)
        local.set(buf)
        return buf
    }

    fun recycle(buffer: ByteArray) {
        require(buffer.size == chunkSize) { "回收缓冲大小不匹配" }
        if (local.get() == null) {
            local.set(buffer)
            return
        }
        synchronized(shared) {
            if (shared.size < maxPooled) shared.addLast(buffer)
        }
    }

    companion object {
        const val DEFAULT_CHUNK: Int = 64 * 1024
        const val MIN_CHUNK: Int = 16 * 1024
        const val MAX_CHUNK: Int = 1 shl 20
    }
}
