package com.limao.unzip.files

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** 归档扫描：MediaStore 毫秒级主源 + 白名单浅扫兜底（不再全盘递归）。 */
object ArchiveScanner {

    private val ARCHIVE_EXT = listOf(
        "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "cab", "iso", "jar",
    )

    data class ArchiveItem(val file: File, val name: String, val size: Long, val lastModified: Long)

    private val extSelection: String by lazy {
        ARCHIVE_EXT.joinToString(" OR ") { "_data LIKE ?" }
    }

    private val extArgs: Array<String> by lazy {
        ARCHIVE_EXT.map { "%.$it" }.toTypedArray()
    }

    /**
     * 主源：MediaStore Files（SQLite 索引查询，毫秒级，覆盖所有已扫描卷）；
     * 兜底：白名单目录浅扫（补 MediaStore 未及时索引的新文件）。
     */
    fun scanRoots(context: Context? = null): List<ArchiveItem> {
        val out = ArrayList<ArchiveItem>(64)
        val seen = HashSet<String>()

        if (context != null) {
            runCatching {
                val proj = arrayOf(
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                )
                context.contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    proj,
                    extSelection,
                    extArgs,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
                )?.use { c ->
                    while (c.moveToNext() && out.size < 500) {
                        val p = c.getString(0) ?: continue
                        if (p.contains("/Android/") || !seen.add(p)) continue
                        val f = File(p)
                        if (f.length() > 0 || f.exists()) {
                            out.add(ArchiveItem(f, f.name, f.length(), f.lastModified()))
                        }
                    }
                }
            }
        }

        // 兜底：白名单目录一级扫描（深度 1，只抓漏网新文件）
        val root = Environment.getExternalStorageDirectory()
        val quickDirs = listOf(
            Environment.DIRECTORY_DOWNLOADS,
            "Tencent/QQfile_recv",
            "Tencent/MicroMsg/Download",
            "BaiduNetdisk",
            "浏览器下载",
        )
        for (rel in quickDirs) {
            val d = File(root, rel)
            val files = runCatching { d.listFiles() }.getOrNull() ?: continue
            for (f in files) {
                if (out.size >= 500) break
                if (f.isFile && isArchive(f.name) && seen.add(f.absolutePath)) {
                    out.add(ArchiveItem(f, f.name, f.length(), f.lastModified()))
                }
            }
        }

        return out.sortedByDescending { it.lastModified }
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
            } else if (f.isDirectory && depth < maxDepth && !f.name.startsWith(".") && f.name != "Android") {
                walk(f, out, depth + 1, maxDepth)
            }
        }
    }
}
