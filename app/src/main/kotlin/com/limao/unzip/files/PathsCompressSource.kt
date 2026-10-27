package com.limao.unzip.files

import android.os.ParcelFileDescriptor
import com.limao.unzip.engine.api.EntryCursor
import com.limao.unzip.engine.api.FdCompressSource
import com.limao.unzip.engine.util.PathGuard
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** 多选压缩源：把任意若干文件/文件夹（含顶层目录递归）作为条目集合。 */
class PathsCompressSource(private val paths: List<File>) : FdCompressSource {

    private data class M(val rel: String, val size: Long, val mtime: Long, val isDir: Boolean, val file: File)

    private var metas: List<M> = emptyList()

    override fun scan(sink: (EntryCursor) -> Unit) {
        val list = ArrayList<M>(64)
        for (root in paths) {
            val rootName = root.name.ifEmpty { "root" }
            if (root.isFile) {
                list.add(M(rootName, root.length(), root.lastModified(), false, root))
            } else {
                list.add(M(rootName, 0, root.lastModified(), true, root))
                root.walkTopDown().forEach { f ->
                    if (f == root) return@forEach
                    val rel = PathGuard.sanitize("$rootName/${f.relativeTo(root).invariantSeparatorsPath}") ?: return@forEach
                    list.add(M(rel, if (f.isFile) f.length() else 0, f.lastModified(), f.isDirectory, f))
                }
            }
        }
        metas = list
        val cursor = EntryCursor()
        metas.forEachIndexed { i, m ->
            cursor.assign(i.toLong(), m.rel, m.size, -1, m.isDir, false, m.mtime)
            sink(cursor)
        }
    }

    override fun openStream(index: Long): InputStream? {
        val m = metas.getOrNull(index.toInt()) ?: return null
        return if (m.isDir) null else FileInputStream(m.file)
    }

    override fun openFd(index: Long): Int? {
        val m = metas.getOrNull(index.toInt()) ?: return null
        if (m.isDir) return null
        return ParcelFileDescriptor.open(m.file, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
    }

    override fun close() {}
}
