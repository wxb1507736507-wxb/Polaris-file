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
    private lateinit var list: RecyclerView
    private val pageCache = android.util.LruCache<Int, Bitmap>(6)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        val file = File(path)
        // 背景跟随应用主题：日间白底，夜间黑底（页面纸张始终为白）
        val night = com.limao.unzip.UiTheme.isNight()
        val bgPage = if (night) 0xFF121417.toInt() else 0xFFF5F6F8.toInt()
        val bgBar = if (night) 0xFF2B2B2B.toInt() else 0xFFFFFFFF.toInt()
        val fgPrimary = if (night) 0xFFEEEEEE.toInt() else 0xFF1D1B20.toInt()
        val fgSecondary = if (night) 0xFFDDDDDD.toInt() else 0xFF1D1B20.toInt()
        val accent = if (night) 0xFF82AAFF.toInt() else 0xFF1296DB.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgPage)
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            setBackgroundColor(bgBar)
            setPadding(24, 32, 24, 32)
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(this).apply {
            text = "‹"; textSize = 26f; setTextColor(fgSecondary); setPadding(0, 0, 28, 0)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = file.name; textSize = 15f; setTextColor(fgPrimary)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        pageLabel = TextView(this).apply { textSize = 13f; setTextColor(accent) }
        bar.addView(pageLabel)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PdfReaderActivity)
            setBackgroundColor(bgPage)
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        Thread {
            // 空文件/损坏文件给出内建错误页（不再 toast+退出，让用户明确看到原因）
            if (file.length() == 0L) {
                runOnUiThread {
                    pageLabel.text = "无法打开"
                    showPdfError("文件为空（0 KB），可能下载中断或源文件已损坏")
                }
                return@Thread
            }
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
                    pageLabel.text = "无法打开"
                    showPdfError(
                        when {
                            t.message?.contains("length", true) == true -> "文件不完整或已损坏（大小 ${file.length() / 1024} KB）"
                            else -> t.message ?: "未知错误"
                        },
                    )
                }
            }
        }.start()
    }

    /** 内建错误卡片：常驻显示，不退出页面 */
    private fun showPdfError(message: String) {
        val err = TextView(this).apply {
            textSize = 14f
            setTextColor(if (com.limao.unzip.UiTheme.isNight()) 0xFFAAAAAA.toInt() else 0xFF49454F.toInt())
            setBackgroundColor(if (com.limao.unzip.UiTheme.isNight()) 0xFF1E2126.toInt() else 0xFFF6F6F6.toInt())
            setPadding(dp(20), dp(24), dp(20), dp(24))
            gravity = Gravity.CENTER
            text = "无法打开 PDF\n\n$message"
        }
        (list.parent as? ViewGroup)?.addView(err, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        list.visibility = View.GONE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
                setTextColor(if (com.limao.unzip.UiTheme.isNight()) 0xFF999999.toInt() else 0xFF49454F.toInt())
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
