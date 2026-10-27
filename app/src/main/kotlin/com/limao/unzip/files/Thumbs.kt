package com.limao.unzip.files

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.ImageView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 缩略图加载（LruCache + 后台解码）。
 * key: "img:<id>"（MediaStore 图片缩略图）/ "vid:<id>"（视频首帧）。
 * imageView.tag = key 防复用错位。
 */
object Thumbs {

    private val executor: ExecutorService = Executors.newFixedThreadPool(3)

    private val cache = object : LinkedHashMap<String, Bitmap>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 192
    }

    fun loadInto(context: Context, key: String, imageView: ImageView) {
        val cached = synchronized(cache) { cache[key] }
        if (cached != null) {
            apply(imageView, key, cached)
            return
        }
        imageView.tag = key
        executor.execute {
            val bmp = decode(context, key) ?: return@execute
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

    private fun decode(context: Context, key: String): Bitmap? = runCatching {
        val (kind, idStr) = key.split(":", limit = 2)
        val id = idStr.toLong()
        when (kind) {
            "img" -> MediaStore.Images.Thumbnails.getThumbnail(
                context.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null,
            )
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

    private inline fun <T> MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> T): T =
        try { block(this) } finally { runCatching { release() } }
}
