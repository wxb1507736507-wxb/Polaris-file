package com.limao.unzip.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.ImageView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 缩略图加载（LruCache + 后台采样解码）。
 * 直接解码源文件/视频帧——不依赖系统缩略图表（Android 10+ 上 getThumbnail 不可靠）。
 * imageView.tag = key 防复用错位。
 */
object Thumbs {

    private val executor: ExecutorService = Executors.newFixedThreadPool(3)

    private val cache = object : LinkedHashMap<String, Bitmap>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 192
    }

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
        val (kind, idStr) = key.split(":", limit = 2)
        when (kind) {
            "img" -> decodeSampled(path, 256)
            "vid" -> {
                val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(idStr).build()
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, uri)
                    r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            }
            else -> null
        }
    }.getOrNull()

    /** 按目标边长采样解码，避免大图 OOM。 */
    private fun decodeSampled(path: String?, target: Int): Bitmap? {
        if (path == null) return null
        val f = java.io.File(path)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, opts)
    }

    private inline fun <T> MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> T): T =
        try { block(this) } finally { runCatching { release() } }
}
