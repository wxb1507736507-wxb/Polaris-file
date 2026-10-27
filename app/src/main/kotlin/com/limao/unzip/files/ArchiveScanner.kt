package com.limao.unzip.files

import android.os.Environment
import java.io.File

/** 归档扫描：遍历常见目录，收集压缩包（QQ浏览器压缩包分类的数据源）。 */
object ArchiveScanner {

    private val ARCHIVE_EXT = listOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "cab", "iso", "jar")

    data class ArchiveItem(val file: File, val name: String, val size: Long, val lastModified: Long)

    fun scanRoots(): List<ArchiveItem> {
        val root = Environment.getExternalStorageDirectory()
        val result = ArrayList<ArchiveItem>(64)
        // 常见归档位置优先浅扫；全部目录深度限制 4、单项目录上限防慢
        val quickDirs = listOf(
            Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS,
            "Tencent/QQfile_recv", "Tencent/MicroMsg/Download", "BaiduNetdisk", "浏览器下载", "",
        ).map { rel -> File(root, rel) }
            .filter { it.exists() && it.isDirectory }
        for (dir in quickDirs) walk(dir, result, depth = 0, maxDepth = 3)
        // 去重排序：新 → 旧
        return result.distinctBy { it.file.absolutePath }.sortedByDescending { it.lastModified }
    }

    fun isArchive(name: String): Boolean {
        val lower = name.lowercase()
        return ARCHIVE_EXT.any { lower.endsWith(it) }
    }

    private fun walk(dir: File, out: MutableList<ArchiveItem>, depth: Int, maxDepth: Int) {
        if (depth > maxDepth || out.size > 2000) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isFile && isArchive(f.name)) {
                out.add(ArchiveItem(f, f.name, f.length(), f.lastModified()))
            } else if (f.isDirectory && depth < maxDepth && !f.name.startsWith(".")) {
                walk(f, out, depth + 1, maxDepth)
            }
        }
    }
}
