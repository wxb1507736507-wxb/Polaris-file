package com.limao.unzip.engine.engine

import com.limao.unzip.engine.api.OutputSink
import com.limao.unzip.engine.api.OverwritePolicy
import com.limao.unzip.engine.error.EngineErrorCode.IO
import com.limao.unzip.engine.error.EngineErrorCode.WRITE
import com.limao.unzip.engine.error.EngineException
import java.io.File
import java.io.FileOutputStream

/**
 * OutputSink 的本地文件实现：.part 写入 → commit 时原子 rename 收尾，
 * discard 删除 .part，leaveAsPart 保留半截文件。
 * 文件路径拼接（root + name）只发生在此处（输出端），且每会话仅按条目发生一次。
 */
class FileSink(
    private val root: File,
    private val overwritePolicy: OverwritePolicy = OverwritePolicy.REPLACE,
    private val preserveTimestamps: Boolean = true,
) : OutputSink {

    init {
        root.mkdirs()
    }

    override fun beginEntry(name: String, expectBytes: Long, mtimeMs: Long, isDirectory: Boolean): OutputSink.EntryOutput? {
        val target = File(root, name)
        if (isDirectory) {
            if (target.exists()) return NoopEntry
            if (!target.mkdirs()) throw EngineException(WRITE, "无法创建目录: $name")
            return NoopEntry
        }
        val parent = target.parentFile ?: root
        if (!parent.exists() && !parent.mkdirs()) throw EngineException(WRITE, "无法创建父目录: $name")
        if (target.exists()) {
            when (overwritePolicy) {
                OverwritePolicy.SKIP -> return null
                OverwritePolicy.FAIL -> throw EngineException(WRITE, "目标已存在: $name")
                OverwritePolicy.REPLACE -> Unit
            }
        }
        val part = File(parent, target.name + PART_SUFFIX)
        return FileEntryOutput(part, target, mtimeMs)
    }

    override fun close() {}

    private object NoopEntry : OutputSink.EntryOutput {
        override fun write(src: ByteArray, offset: Int, length: Int) {}
        override fun commit() {}
        override fun discard() {}
        override fun leaveAsPart() {}
    }

    private inner class FileEntryOutput(
        private val part: File,
        private val target: File,
        private val mtimeMs: Long,
    ) : OutputSink.EntryOutput {
        private val out = FileOutputStream(part)
        private var closed = false

        override fun write(src: ByteArray, offset: Int, length: Int) {
            out.write(src, offset, length)
        }

        override fun commit() {
            close0()
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                throw EngineException(IO, "无法收尾文件（rename 失败）: ${target.name}")
            }
            if (preserveTimestamps && mtimeMs > 0) target.setLastModified(mtimeMs)
        }

        override fun discard() {
            close0()
            part.delete()
        }

        override fun leaveAsPart() {
            close0()
        }

        private fun close0() {
            if (!closed) {
                closed = true
                out.close()
            }
        }
    }

    companion object {
        const val PART_SUFFIX = ".part"
    }
}
