package com.limao.unzip.io

import android.os.ParcelFileDescriptor
import android.system.Os
import com.limao.unzip.engine.api.FdCapableSink
import com.limao.unzip.engine.api.OutputSink
import com.limao.unzip.engine.api.OverwritePolicy
import com.limao.unzip.engine.api.RawFdSource
import com.limao.unzip.engine.error.EngineErrorCode
import com.limao.unzip.engine.error.EngineException
import java.io.File
import java.nio.ByteBuffer

/**
 * 原始 fd 输入（RawFdSource）：ParcelFileDescriptor.adoptFd 接管 int fd 所有权，
 * Os.* 走 FileDescriptor，native 引擎经 [fd] 拿裸 int。
 */
class AndroidFdInput(fd: Int) : RawFdSource {

    private val pfd = ParcelFileDescriptor.adoptFd(fd)
    override val fd: Int get() = pfd.fd
    override val sizeBytes: Long = Os.fstat(pfd.fileDescriptor).st_size

    override fun pread(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= sizeBytes) return -1
        val bb = ByteBuffer.wrap(buffer, offset, length)
        Os.pread(pfd.fileDescriptor, bb, position)
        return bb.position() - offset
    }

    override fun close() {
        runCatching { pfd.close() }
    }
}

/**
 * 本地文件输出（OutputSink + FdCapableSink 双通道）：
 * byte[] 通道服务参考引擎，fd 通道服务 native 引擎；.part + rename 收尾语义一致。
 */
class AndroidFileSink(
    private val root: File,
    private val overwritePolicy: OverwritePolicy = OverwritePolicy.REPLACE,
    private val preserveTimestamps: Boolean = true,
) : OutputSink, FdCapableSink {

    init {
        root.mkdirs()
    }

    private fun targetOf(name: String): Pair<File, File> {
        val target = File(root, name)
        val parent = target.parentFile ?: root
        if (!parent.exists() && !parent.mkdirs()) {
            throw EngineException(EngineErrorCode.WRITE, "无法创建父目录: $name")
        }
        return target to File(parent, target.name + ".part")
    }

    private fun existsAction(target: File, name: String): Boolean {
        if (!target.exists()) return true
        return when (overwritePolicy) {
            OverwritePolicy.SKIP -> false
            OverwritePolicy.FAIL -> throw EngineException(EngineErrorCode.WRITE, "目标已存在: $name")
            OverwritePolicy.REPLACE -> true
        }
    }

    private fun mkdirs(name: String) {
        val (t, _) = targetOf(name)
        if (!t.exists() && !t.mkdirs()) throw EngineException(EngineErrorCode.WRITE, "无法创建目录: $name")
    }

    // ---- byte[] 通道（参考引擎） ----
    override fun beginEntry(name: String, expectBytes: Long, mtimeMs: Long, isDirectory: Boolean): OutputSink.EntryOutput? {
        if (isDirectory) {
            mkdirs(name)
            return Noop
        }
        val (target, part) = targetOf(name)
        if (!existsAction(target, name)) return null
        return ByteEntry(part, target, mtimeMs)
    }

    // ---- fd 通道（native 引擎） ----
    override fun beginEntryFd(name: String, expectBytes: Long, mtimeMs: Long, isDirectory: Boolean): FdCapableSink.FdEntryOutput? {
        if (isDirectory) {
            mkdirs(name)
            return NoopFd
        }
        val (target, part) = targetOf(name)
        if (!existsAction(target, name)) return null
        return FdEntry(part, target, mtimeMs)
    }

    override fun close() {}

    private object Noop : OutputSink.EntryOutput {
        override fun write(src: ByteArray, offset: Int, length: Int) {}
        override fun commit() {}
        override fun discard() {}
        override fun leaveAsPart() {}
    }

    private object NoopFd : FdCapableSink.FdEntryOutput {
        override val fd: Int get() = -2
        override fun commitFd() {}
        override fun discardFd() {}
        override fun leavePartFd() {}
        override fun afterFdClosed() {}
    }

    private inner class ByteEntry(private val part: File, private val target: File, private val mtime: Long) : OutputSink.EntryOutput {
        private val out = part.outputStream()
        private var closed = false
        override fun write(src: ByteArray, offset: Int, length: Int) = out.write(src, offset, length)
        override fun commit() {
            close0()
            finish(part, target, mtime)
        }
        override fun discard() { close0(); part.delete() }
        override fun leaveAsPart() { close0() }
        private fun close0() { if (!closed) { closed = true; out.close() } }
    }

    private inner class FdEntry(private val part: File, private val target: File, private val mtime: Long) : FdCapableSink.FdEntryOutput {
        // 所有权转移给 native：fd 由 native close()，本类只负责文件收尾
        override val fd: Int = ParcelFileDescriptor.open(
            part,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        ).detachFd()
        private var finalized = false

        override fun commitFd() {
            finalized = true
            finish(part, target, mtime)
        }

        override fun discardFd() {
            finalized = true
            part.delete()
        }

        override fun leavePartFd() { finalized = true }

        override fun afterFdClosed() { finalized = true }
    }

    private fun finish(part: File, target: File, mtime: Long) {
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            throw EngineException(EngineErrorCode.IO, "无法收尾文件（rename 失败）: ${target.name}")
        }
        if (preserveTimestamps && mtime > 0) target.setLastModified(mtime)
    }
}
