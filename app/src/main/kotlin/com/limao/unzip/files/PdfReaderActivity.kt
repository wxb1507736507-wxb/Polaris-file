package com.limao.unzip.files

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.limao.unzip.R
import java.io.File

/** PDF 内建阅读器：PdfRenderer 按需渲染，垂直滚动翻页，零外部依赖。 */
class PdfReaderActivity : AppCompatActivity() {

    private lateinit var renderer: PdfRenderer
    private lateinit var fd: ParcelFileDescriptor
    private lateinit var pageLabel: TextView
    private val pageCache = android.util.LruCache<Int, Bitmap>(6)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        val file = File(path)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF3A3A3A.toInt())
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            setBackgroundColor(0xFF2B2B2B.toInt())
            setPadding(24, 32, 24, 32)
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(this).apply {
            text = "‹"; textSize = 26f; setTextColor(0xFFDDDDDD.toInt()); setPadding(0, 0, 28, 0)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = file.name; textSize = 15f; setTextColor(0xFFEEEEEE.toInt())
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        pageLabel = TextView(this).apply { textSize = 13f; setTextColor(0xFF82AAFF.toInt()) }
        bar.addView(pageLabel)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PdfReaderActivity)
            setBackgroundColor(0xFF3A3A3A.toInt())
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        Thread {
            runCatching {
                fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(fd)
                val pageCount = renderer.pageCount
                runOnUiThread {
                    pageLabel.text = "$pageCount 页"
                    list.adapter = PdfAdapter(pageCount) { i -> renderPage(i) }
                }
            }.onFailure { t ->
                runOnUiThread {
                    Toast.makeText(this, "无法打开 PDF：${t.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun renderPage(index: Int): Bitmap? {
        synchronized(pageCache) { pageCache.get(index) }?.let { return it }
        if (!this::renderer.isInitialized) return null
        return runCatching {
            synchronized(renderer) {
                if (index >= renderer.pageCount) return null
                renderer.openPage(index).use { page ->
                    val targetW = resources.displayMetrics.widthPixels
                    val scale = targetW.toFloat() / page.width
                    val bmp = Bitmap.createBitmap(
                        targetW,
                        (page.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    synchronized(pageCache) { pageCache.put(index, bmp) }
                    bmp
                }
            }
        }.getOrNull()
    }

    private inner class PdfAdapter(
        private val pageCount: Int,
        private val render: (Int) -> Bitmap?,
    ) : RecyclerView.Adapter<PdfHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfHolder {
            val cell = LinearLayout(this@PdfReaderActivity).apply { orientation = LinearLayout.VERTICAL }
            val img = android.widget.ImageView(this@PdfReaderActivity).apply {
                adjustViewBounds = true
                setBackgroundColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val footer = TextView(this@PdfReaderActivity).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(0xFF999999.toInt())
                setPadding(0, 8, 0, 8)
            }
            cell.addView(img)
            cell.addView(footer)
            return PdfHolder(cell, img, footer)
        }

        override fun getItemCount() = pageCount

        override fun onBindViewHolder(holder: PdfHolder, position: Int) {
            holder.footer.text = "${position + 1} / $pageCount"
            holder.img.setImageBitmap(null)
            Thread {
                val bmp = render(position)
                holder.img.post {
                    if (bmp != null) holder.img.setImageBitmap(bmp)
                }
            }.start()
        }
    }

    private class PdfHolder(itemView: View, val img: android.widget.ImageView, val footer: TextView) :
        RecyclerView.ViewHolder(itemView)

    override fun onDestroy() {
        runCatching {
            if (this::renderer.isInitialized) {
                renderer.close()
                fd.close()
            }
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
