package com.limao.unzip.files

import android.content.ContentUris
import com.limao.unzip.R
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** 统一列表条目：名称/路径或内容Uri/大小/时间/图标/类型标签。 */
data class Entry(
    val name: String,
    val path: String?,
    val uri: Uri?,
    val size: Long,
    val time: Long,
    val isDir: Boolean,
    val icon: String,
    val kindLabel: String,
    val thumbKey: String? = null,
)

/** 分类数据源：MediaStore（图片/视频/音频/文档/安装包）+ 目录扫描（下载/浏览器）。 */
object FileItems {

    val DOC_MIMES = listOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain",
        "application/rtf",
    )

    fun docGroup(name: String): Int = when {
        name.endsWith(".pdf", true) -> 0
        name.endsWith(".doc", true) || name.endsWith(".docx", true) || name.endsWith(".wps", true) -> 1
        name.endsWith(".xls", true) || name.endsWith(".xlsx", true) || name.endsWith(".et", true) || name.endsWith(".csv", true) -> 2
        name.endsWith(".ppt", true) || name.endsWith(".pptx", true) || name.endsWith(".dps", true) -> 3
        name.endsWith(".txt", true) || name.endsWith(".log", true) || name.endsWith(".md", true) -> 4
        else -> 5
    }

    val DOC_GROUPS = arrayOf("全部", "PDF", "Word", "表格", "演示", "文本", "其他")

    private fun docIcon(g: Int) = when (g) {
        0 -> "📕"; 1 -> "📘"; 2 -> "📗"; 3 -> "📙"; 4 -> "📃"; else -> "📄"
    }

    fun iconFor(name: String): String = when {
        ArchiveScanner.isArchive(name) -> "📦"
        name.endsWith(".apk", true) -> "🤖"
        name.endsWith(".pdf", true) -> "📕"
        name.endsWith(".doc", true) || name.endsWith(".docx", true) || name.endsWith(".wps", true) -> "📘"
        name.endsWith(".xls", true) || name.endsWith(".xlsx", true) || name.endsWith(".csv", true) -> "📗"
        name.endsWith(".ppt", true) || name.endsWith(".pptx", true) -> "📙"
        name.endsWith(".txt", true) || name.endsWith(".md", true) || name.endsWith(".log", true) -> "📃"
        name.endsWith(".mp3", true) || name.endsWith(".flac", true) || name.endsWith(".m4a", true) || name.endsWith(".wav", true) -> "🎵"
        name.endsWith(".mp4", true) || name.endsWith(".mkv", true) || name.endsWith(".avi", true) || name.endsWith(".mov", true) -> "🎬"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) || name.endsWith(".png", true) || name.endsWith(".gif", true) || name.endsWith(".webp", true) || name.endsWith(".heic", true) -> "🖼"
        else -> "📄"
    }

    // ---- MediaStore ----

    private fun queryMedia(context: Context, collection: Uri, mimes: List<String>?, docStyle: Boolean, thumbPrefix: String? = null): List<Entry> {
        val resolver = context.contentResolver
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATA,
        )
        val selection = mimes?.let { "${MediaStore.MediaColumns.MIME_TYPE} IN (${it.joinToString(",") { "?" }})" }
        val out = ArrayList<Entry>(128)
        runCatching {
            resolver.query(collection, proj, selection, mimes?.toTypedArray(), "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < 2000) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val size = c.getLong(2)
                    val time = c.getLong(3) * 1000
                    val uri = ContentUris.withAppendedId(collection, id)
                    val dataPath = runCatching { c.getString(5) }.getOrNull()
                    val icon = if (docStyle) docIcon(docGroup(name)) else iconFor(name)
                    val label = if (docStyle) DOC_GROUPS[docGroup(name) + 1].let { d -> if (d == "其他") "文档" else d } else typeLabel(name)
                    val thumb = thumbPrefix?.let { "$it:$id" }
                    // 真实路径让所有 MediaStore 条目都支持重命名/删除/复制路径（全文件权限）
                    out.add(Entry(name, dataPath, uri, size, time, false, icon, label, thumb))
                }
            }
        }
        return out
    }

    fun images(ctx: Context) = queryMedia(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, false, "img")
    fun videos(ctx: Context) = queryMedia(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null, false, "vid")
    fun audio(ctx: Context) = queryMedia(ctx, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, false)
    fun apks(ctx: Context) = queryMedia(ctx, MediaStore.Files.getContentUri("external"), listOf("application/vnd.android.package-archive"), false)
    fun docs(ctx: Context) = queryMedia(ctx, MediaStore.Files.getContentUri("external"), DOC_MIMES, true)

    /** 最近文件（全部类型，时间倒序；排除 Android/ 内部目录）。 */
    fun recentAll(ctx: Context, limit: Int): List<Entry> {
        val resolver = ctx.contentResolver
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATA,
        )
        val out = ArrayList<Entry>(limit)
        runCatching {
            resolver.query(
                MediaStore.Files.getContentUri("external"),
                proj,
                "(_data NOT LIKE '%/Android/%' AND _data NOT LIKE 'Android/%' AND _mime_type != 'vnd.android.document/directory')",
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
                    val dataPath = runCatching { c.getString(5) }.getOrNull()
                    out.add(Entry(name, dataPath, uri, c.getLong(2), c.getLong(3) * 1000, false, iconFor(name), typeLabel(name)))
                }
            }
        }
        return out
    }

    /** 网盘下载目录聚合：扫描百度网盘/夸克/迅雷/UC/QQ/微信等所有网盘应用的下载目录，按 mtime 倒序。 */
    fun netdiskDownloads(limit: Int = 500): List<Entry> {
        val out = ArrayList<Entry>(limit)
        val seen = HashSet<String>()
        val root = Environment.getExternalStorageDirectory()
        val dirs = listOf(
            Environment.DIRECTORY_DOWNLOADS,
            "BaiduNetdisk",
            "Quark/Download",
            "Quark",
            "UCDownloads",
            "Thunder",
            "Download/Thunder",
            "ALYDownload",
            "Tencent/QQfile_recv",
            "Tencent/MicroMsg/Download",
            "Documents",
        )
        for (rel in dirs) {
            val d = File(root, rel)
            val files = runCatching { d.listFiles() }.getOrNull() ?: continue
            for (f in files) {
                if (out.size >= limit) break
                if (!f.isFile || f.name.startsWith(".") || !seen.add(f.absolutePath)) continue
                out.add(Entry(f.name, f.absolutePath, null, f.length(), f.lastModified(), false, iconFor(f.name), typeLabel(f.name)))
            }
            // 一级子目录也扫（网盘常有子文件夹）
            for (sub in files) {
                if (out.size >= limit) break
                if (sub.isDirectory && !sub.name.startsWith(".")) {
                    val subFiles = runCatching { sub.listFiles() }.getOrNull() ?: continue
                    for (f in subFiles) {
                        if (out.size >= limit) break
                        if (!f.isFile || f.name.startsWith(".")) continue
                        val rel2 = "${rel}/${sub.name}/${f.name}"
                        val safe = rel2
                        if (!seen.add(f.absolutePath)) continue
                        out.add(Entry(f.name, f.absolutePath, null, f.length(), f.lastModified(), false, iconFor(f.name), typeLabel(f.name)))
                    }
                }
            }
        }
        return out.sortedByDescending { it.time }
    }

    /** 网盘下载目录是否存在。 */
    fun hasNetdiskDirs(): Boolean {
        val root = Environment.getExternalStorageDirectory()
        return listOf("BaiduNetdisk", "Quark", "UCDownloads", "Thunder").any { File(root, it).isDirectory }
    }

    fun counts(ctx: Context): Map<String, Int> {
        fun countOf(collection: Uri, mimes: List<String>?): Int {
            val resolver = ctx.contentResolver
            val selection = mimes?.let { "${MediaStore.MediaColumns.MIME_TYPE} IN (${it.joinToString(",") { "?" }})" }
            return runCatching {
                resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, mimes?.toTypedArray(), null)?.use { it.count } ?: 0
            }.getOrDefault(0)
        }
        return mapOf(
            "images" to countOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null),
            "video" to countOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null),
            "audio" to countOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null),
            "docs" to countOf(MediaStore.Files.getContentUri("external"), DOC_MIMES),
            "apk" to countOf(MediaStore.Files.getContentUri("external"), listOf("application/vnd.android.package-archive")),
        )
    }

    // ---- 目录 ----

    fun listDir(dir: File, archiveOnly: Boolean): List<Entry> {
        val files = dir.listFiles() ?: return emptyList()
        val dirs = files.filter { it.isDirectory && !it.name.startsWith(".") }.map {
            Entry(it.name, it.absolutePath, null, 0, it.lastModified(), true, "📂", "文件夹")
        }.sortedBy { it.name.lowercase() }
        val fs = files.filter { it.isFile && (!archiveOnly || ArchiveScanner.isArchive(it.name)) }
            .map { Entry(it.name, it.absolutePath, null, it.length(), it.lastModified(), false, iconFor(it.name), typeLabel(it.name)) }
            .sortedByDescending { it.time }
        return dirs + fs
    }

    fun downloads(archiveOnly: Boolean): List<Entry> =
        listDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), archiveOnly)

    /** 最近文件（文件系统直扫：全设备 /sdcard 递归，按修改时间倒序——保证完整不漏）。 */
    fun recentFs(limit: Int = 200, maxDepth: Int = 3): List<Entry> {
        val out = ArrayList<Entry>(limit)
        val seen = HashSet<String>()
        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth || out.size >= limit) return
            val files = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (f in files) {
                if (out.size >= limit) return
                val n = f.name
                if (n.startsWith(".")) continue
                if (f.isDirectory) {
                    if (n != "Android") walk(f, depth + 1)
                } else if (seen.add(f.absolutePath)) {
                    out.add(Entry(n, f.absolutePath, null, f.length(), f.lastModified(), false, iconFor(n), typeLabel(n)))
                }
            }
        }
        walk(Environment.getExternalStorageDirectory(), 0)
        return out.sortedByDescending { it.time }
    }

    fun isTextName(name: String): Boolean {
        val lower = name.lowercase()
        return listOf(".txt", ".log", ".md", ".xml", ".json", ".csv", ".html", ".htm", ".py", ".java", ".kt", ".c", ".cpp", ".h", ".sh", ".properties", ".ini", ".conf", ".yaml", ".yml", ".js", ".ts", ".css")
            .any { lower.endsWith(it) }
    }

    /** Material 矢量图标映射（m3e 官方 path）。 */
    fun iconResFor(name: String): Int = when {
        ArchiveScanner.isArchive(name) -> R.drawable.ic_archive
        name.endsWith(".apk", true) -> R.drawable.ic_android_apk
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) || name.endsWith(".png", true) ||
            name.endsWith(".gif", true) || name.endsWith(".webp", true) || name.endsWith(".heic", true) -> R.drawable.ic_image
        name.endsWith(".mp4", true) || name.endsWith(".mkv", true) || name.endsWith(".avi", true) ||
            name.endsWith(".mov", true) || name.endsWith(".3gp", true) -> R.drawable.ic_videocam
        name.endsWith(".mp3", true) || name.endsWith(".flac", true) || name.endsWith(".m4a", true) ||
            name.endsWith(".wav", true) || name.endsWith(".ogg", true) -> R.drawable.ic_audiotrack
        else -> R.drawable.ic_description
    }

    private fun typeLabel(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot + 1).uppercase() else "文件"
    }
}
