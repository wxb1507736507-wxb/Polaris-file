package com.limao.unzip.files
import android.provider.MediaStore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.widget.ImageView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 缩略图加载（LruCache + 后台采样解码）。
 * 统一以文件路径为数据源：图片用 BitmapFactory 采样，视频用 MediaMetadataRetriever 首帧。
 * imageView.tag = key（文件路径）防复用错位。
 */
object Thumbs {

    private val executor: ExecutorService = Executors.newFixedThreadPool(3)

    private val cache = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 256
    }

    /** 判断文件是否可加载封面（图片或视频扩展名）。 */
    fun hasCover(name: String): Boolean {
        val lower = name.lowercase()
        return IMAGE_EXTS.any { lower.endsWith(it) } || VIDEO_EXTS.any { lower.endsWith(it) }
    }

    /**
     * 加载封面到 ImageView。key 必须唯一（用文件绝对路径）。
     * 支持 MediaStore thumbKey（"img:id"/"vid:id"）和纯文件路径两种来源。
     */
    fun loadInto(context: Context, key: String, path: String?, imageView: ImageView) {
        imageView.tag = key // 校验基准：先设置再走缓存/解码，命中路径也生效
        val cached = synchronized(cache) { cache[key] }
        if (cached != null) {
            apply(imageView, key, cached)
            return
        }
        executor.execute {
            val bmp = decode(context, key, path) ?: return@execute
            synchronized(cache) { cache[key] = bmp }
            imageView.post { apply(imageView, key, bmp) }
        }
    }

    private fun apply(imageView: ImageView, key: String, bmp: Bitmap) {
        if (imageView.tag == key) {
            imageView.setImageBitmap(bmp)
            imageView.visibility = android.view.View.VISIBLE
        }
    }

    private fun decode(context: Context, key: String, path: String?): Bitmap? = runCatching {
        when {
            // MediaStore 键（"img:123"/"vid:456"）→ 从 MediaStore 或对应文件解码
            key.startsWith("img:") || key.startsWith("vid:") -> decodeMediaStore(context, key, path)
            // 纯文件路径 → 直接采样解码
            path != null -> decodeSampled(path, 256)
            else -> null
        }
    }.getOrNull()

    private fun decodeMediaStore(context: Context, key: String, path: String?): Bitmap? {
        val (kind, idStr) = key.split(":", limit = 2)
        val id = idStr.toLongOrNull() ?: return null
        return when (kind) {
            "img" -> decodeSampled(path, 256)
                ?: MediaStore.Images.Thumbnails.getThumbnail(
                    context.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null,
                )
            "vid" -> {
                if (path != null && File(path).exists()) decodeVideoFrame(path)
                else {
                    val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(idStr).build()
                    MediaMetadataRetriever().use { r ->
                        r.setDataSource(context, uri)
                        r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                }
            }
            else -> null
        }
    }

    private fun decodeVideoFrame(path: String): Bitmap? {
        if (!File(path).exists()) return null
        return MediaMetadataRetriever().use { r ->
            r.setDataSource(path)
            r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    }

    private fun decodeSampled(path: String?, target: Int): Bitmap? {
        if (path == null) return null
        val f = File(path)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 && bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, opts)
    }

    private inline fun <T> MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> T): T =
        try { block(this) } finally { runCatching { release() } }

    private val IMAGE_EXTS = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic")
    private val VIDEO_EXTS = listOf(".mp4", ".mkv", ".avi", ".mov", ".3gp", ".webm")

    private fun isVideo(name: String): Boolean = VIDEO_EXTS.any { name.lowercase().endsWith(it) }
}
