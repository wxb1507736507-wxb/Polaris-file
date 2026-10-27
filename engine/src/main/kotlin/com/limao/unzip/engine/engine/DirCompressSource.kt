package com.limao.unzip.engine.engine

import com.limao.unzip.engine.api.EntryCursor
import com.limao.unzip.engine.api.CompressSource
import com.limao.unzip.engine.util.PathGuard
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 目录树压缩源（JVM/本地文件）：递归枚举，相对路径经 PathGuard 规范化，
 * 稳定排序保证 scan/open 与多次遍历一致。仅提供字节流通道。
 */
class DirCompressSource(private val root: File) : CompressSource {

    data class Meta(val relPath: String, val size: Long, val mtime: Long, val isDir: Boolean)

    private var entries: List<Meta> = emptyList()

    override fun scan(sink: (EntryCursor) -> Unit) {
        val list = ArrayList<Meta>(128)
        root.walkTopDown().forEach { f ->
            val rel = f.relativeTo(root).invariantSeparatorsPath
            if (rel.isEmpty() || rel == ".") return@forEach
            val safe = PathGuard.sanitize(rel) ?: return@forEach
            list.add(Meta(safe, if (f.isFile) f.length() else 0, f.lastModified(), f.isDirectory))
        }
        entries = list.sortedBy { it.relPath }
        val cursor = EntryCursor()
        entries.forEachIndexed { i, m ->
            cursor.assign(i.toLong(), m.relPath, m.size, -1, m.isDir, false, m.mtime)
            sink(cursor)
        }
    }

    override fun openStream(index: Long): InputStream? {
        val m = entries.getOrNull(index.toInt()) ?: return null
        if (m.isDir) return null
        return FileInputStream(File(root, m.relPath))
    }

    override fun close() {}
}
