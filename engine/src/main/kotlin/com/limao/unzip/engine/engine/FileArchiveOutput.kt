package com.limao.unzip.engine.engine

import com.limao.unzip.engine.api.ArchiveOutput
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** 本地文件归档输出：.part 写入 → commit 原子 rename。fd 通道经 ParcelFileDescriptor 不可用（JVM），仅流通道。 */
class FileArchiveOutput(private val target: File) : ArchiveOutput {

    private val part = File(target.parentFile, target.name + ".part")
    private var state = 0 // 0=未开始 1=进行中 2=已收尾

    override fun begin(): OutputStream {
        check(state == 0)
        state = 1
        part.parentFile?.mkdirs()
        return FileOutputStream(part)
    }

    override fun beginFd(): Int = throw UnsupportedOperationException("JVM 通道无裸 fd；native 引擎请在 App 侧使用 SAF/文件 fd 输出")

    override fun commit() {
        if (state == 2) return
        state = 2
        if (target.exists()) target.delete()
        check(part.renameTo(target)) { "rename 失败: $target" }
    }

    override fun discard() {
        state = 2
        part.delete()
    }

    override fun close() {
        if (state == 1) discard()
    }
}
