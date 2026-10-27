package com.limao.unzip.files

import android.content.ContentUris
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

    private fun typeLabel(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot + 1).uppercase() else "文件"
    }
}
