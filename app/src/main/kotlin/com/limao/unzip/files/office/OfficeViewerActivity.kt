package com.limao.unzip.files.office

import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.limao.unzip.R
import java.io.File
import java.util.zip.ZipFile

/**
 * 内建 Office 查看器：docx/xlsx/pptx 在软件内直接渲染（不调起外部应用）。
 * - docx：标题/富文本段落/表格/图片/列表，RecyclerView 按块渲染
 * - xlsx：多工作表 chips + 网格行虚拟化
 * - pptx：每页幻灯片按形状坐标缩放成卡片
 * - .doc（旧二进制格式）：内建提示卡
 */
class OfficeViewerActivity : AppCompatActivity() {

    private lateinit var fileName: String


    private lateinit var root: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var status: TextView
    private lateinit var sheetChips: HorizontalScrollView

    private val fgPrimary get() = if (com.limao.unzip.UiTheme.isNight()) 0xFFE6E6E6.toInt() else 0xFF1D1B20.toInt()
    private val fgSecondary get() = if (com.limao.unzip.UiTheme.isNight()) 0xFFAAAAAA.toInt() else 0xFF49454F.toInt()
    private val pageBg get() = if (com.limao.unzip.UiTheme.isNight()) 0xFF121417.toInt() else 0xFFFFFFFF.toInt()
    private val cardBg get() = if (com.limao.unzip.UiTheme.isNight()) 0xFF1E2126.toInt() else 0xFFF6F6F6.toInt()
    private val divider get() = if (com.limao.unzip.UiTheme.isNight()) 0xFF3A3F45.toInt() else 0xFFDDDDDD.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        val file = File(path)
        fileName = file.name
        if (!file.exists()) { Toast.makeText(this, R.string.toast_cant_open, Toast.LENGTH_SHORT).show(); finish(); return }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBg)
            fitsSystemWindows = true
        }
        // 顶栏
        val bar = LinearLayout(this).apply {
            setBackgroundColor(com.limao.unzip.UiTheme.navBar())
            setPadding(dp(6), dp(8), dp(6), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(this).apply {
            text = "‹"; textSize = 26f; setTextColor(fgPrimary); setPadding(dp(6), 0, dp(14), 0)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = fileName; textSize = 15f; setTextColor(fgPrimary); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        sheetChips = HorizontalScrollView(this).apply { visibility = View.GONE; isHorizontalScrollBarEnabled = false }
        root.addView(sheetChips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        loading = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(loading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(24)
        })
        status = TextView(this).apply {
            textSize = 13f; setTextColor(fgSecondary); setPadding(dp(24), dp(16), dp(24), 0)
            visibility = View.GONE
        }
        root.addView(status)

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@OfficeViewerActivity)
            setBackgroundColor(pageBg)
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val lower = fileName.lowercase()
        val kind = when {
            lower.endsWith(".docx") -> "docx"
            lower.endsWith(".xlsx") -> "xlsx"
            lower.endsWith(".pptx") -> "pptx"
            lower.endsWith(".doc") -> "doc"
            else -> "unknown"
        }
        Thread {
            val result = runCatching { parse(file, kind) }
            android.util.Log.d("OfficeViewer", "parse kind=$kind blocks=${result.getOrNull()?.size} err=${result.exceptionOrNull()?.message}")
            runOnUiThread {
                loading.visibility = View.GONE
                result.fold(
                    onSuccess = { blocks ->
                        if (blocks.isEmpty()) {
                            status.text = getString(R.string.office_empty)
                            status.visibility = View.VISIBLE
                        } else {
                            setupAdapter(blocks, kind)
                        }
                    },
                    onFailure = {
                        status.text = getString(R.string.office_failed, it.message ?: "")
                        status.visibility = View.VISIBLE
                    },
                )
            }
        }.start()
    }

    private fun parse(file: File, kind: String): List<OfficeBlock> {
        return when (kind) {
            // docx 的 zip 保持打开（图片块在 RecyclerView 绑定时懒解码），onDestroy 统一关闭
            "docx" -> {
                val z = ZipFile(file)
                docxZip = z
                DocxParser.parse(z)
            }
            "xlsx" -> ZipFile(file).use { z -> XlsxParser.parse(z) }
            "pptx" -> ZipFile(file).use { z -> PptxParser.parse(z) }
            "doc" -> listOf(
                OfficeBlock.Notice(getString(R.string.office_legacy_doc)),
            )
            else -> listOf(OfficeBlock.Notice(getString(R.string.office_unknown)))
        }
    }

    // docx 的 zip 需要跨块懒解码图片，持有到 destroy
    private var docxZip: ZipFile? = null

    private fun setupAdapter(blocks: List<OfficeBlock>, kind: String) {
        when (kind) {
            "docx", "pptx", "doc", "unknown" -> {
                list.adapter = BlockAdapter(blocks)
            }
            "xlsx" -> {
                val sheets = blocks.filterIsInstance<OfficeBlock.SheetHeader>()
                buildSheetChips(sheets)
                if (sheets.isNotEmpty()) showSheet(sheets[0])
            }
        }
    }



    private fun buildSheetChips(sheets: List<OfficeBlock.SheetHeader>) {
        sheetChips.visibility = View.VISIBLE
        val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(8), dp(10), dp(8)) }
        sheets.forEach { s ->
            inner.addView(TextView(this).apply {
                text = s.name; textSize = 14f; setTextColor(fgPrimary)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setBackgroundResource(if (s.index == 0) R.drawable.chip_selected else R.drawable.chip_normal)
                setOnClickListener { v ->
                    inner.children2().forEach { c ->
                        (c as TextView).setBackgroundResource(R.drawable.chip_normal)
                    }
                    (v as TextView).setBackgroundResource(R.drawable.chip_selected)
                    showSheet(s)
                }
            })
        }
        sheetChips.addView(inner)
    }

    private fun LinearLayout.children2(): List<View> {
        val out = ArrayList<View>()
        for (i in 0 until childCount) out.add(getChildAt(i))
        return out
    }

    private fun showSheet(s: OfficeBlock.SheetHeader) {
        list.adapter = SheetAdapter(s)
    }

    // ---------- 块渲染 ----------

    private fun applySpans(sb: SpannableStringBuilder, spans: List<OfficeBlock.Span>, baseSize: Float) {
        for (s in spans) {
            val start = sb.length
            sb.append(s.text)
            val end = sb.length
            if (end == start) continue
            val styles = ArrayList<CharacterStyle>()
            if (s.bold) styles.add(StyleSpan(Typeface.BOLD))
            if (s.italic) styles.add(StyleSpan(Typeface.ITALIC))
            if (s.underline) styles.add(UnderlineSpan())
            if (s.strike) styles.add(StrikethroughSpan())
            s.color?.let { styles.add(ForegroundColorSpan(it)) }
            s.highlight?.let { styles.add(BackgroundColorSpan(it)) }
            val size = s.sizeSp ?: baseSize
            if (size != baseSize) styles.add(RelativeSizeSpan(size / baseSize))
            for (st in styles) sb.setSpan(st, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private inner class BlockAdapter(private val blocks: List<OfficeBlock>) :
        RecyclerView.Adapter<BlockHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BlockHolder {
            val content = when (viewType) {
                1 -> { // 图片
                    ImageView(this@OfficeViewerActivity).apply {
                        adjustViewBounds = true
                        setPadding(0, dp(2), 0, dp(2))
                    }
                }
                2 -> { // 表格（占位，onBind 构建）
                    LinearLayout(this@OfficeViewerActivity).apply { orientation = LinearLayout.VERTICAL }
                }
                3 -> { // 提示
                    TextView(this@OfficeViewerActivity).apply {
                        textSize = 14f; setTextColor(fgSecondary)
                        setBackgroundColor(cardBg)
                        setPadding(dp(20), dp(16), dp(20), dp(16))
                    }
                }
                else -> TextView(this@OfficeViewerActivity).apply {
                    setTextColor(fgPrimary)
                    setPadding(dp(22), dp(2), dp(22), dp(2))
                }
            }
            val wrap = FrameContainer(this@OfficeViewerActivity)
            wrap.addView(content)
            return BlockHolder(wrap, content)
        }

        override fun getItemViewType(position: Int): Int = when (blocks[position]) {
            is OfficeBlock.Image -> 1
            is OfficeBlock.Table -> 2
            is OfficeBlock.Notice -> 3
            else -> 0
        }

        override fun getItemCount() = blocks.size

        override fun onBindViewHolder(holder: BlockHolder, position: Int) {
            val b = blocks[position]
            when (b) {
                is OfficeBlock.Para -> {
                    val tv = holder.content as TextView
                    val base = 16f
                    val sb = SpannableStringBuilder()
                    if (b.bullet != null) sb.append("${b.bullet} ")
                    applySpans(sb, b.spans, base)
                    // 无显式字号时用统一基准；有字号差异由 RelativeSizeSpan 缩放
                    tv.textSize = base
                    tv.text = sb
                    tv.gravity = when (b.align) {
                        OfficeBlock.ALIGN_CENTER -> Gravity.CENTER_HORIZONTAL
                        OfficeBlock.ALIGN_RIGHT -> Gravity.END
                        else -> Gravity.START
                    }
                    val padL = dp(22) + dp(14) * b.indentLevel
                    tv.setPadding(padL, dp(2), dp(22), dp(b.spaceAfterDp))
                    // 标题样式：若首个 span 字号显著大于正文，加重行高
                    val big = b.spans.firstOrNull()?.sizeSp ?: 0f
                    tv.setLineSpacing(0f, if (big > 17f) 1.15f else 1.05f)
                    tv.visibility = if (sb.isEmpty()) View.GONE else View.VISIBLE
                }
                is OfficeBlock.Image -> {
                    val iv = holder.content as ImageView
                    iv.setImageDrawable(null)
                    Thread {
                        val bmp = docxZip?.let { OfficeUtil.decodeImage(it, b.zipPath, b.maxDim) }
                        holder.content.post {
                            if (bmp != null) {
                                iv.setImageBitmap(bmp)
                                iv.visibility = View.VISIBLE
                            } else {
                                iv.visibility = View.GONE
                            }
                        }
                    }.start()
                }
                is OfficeBlock.Table -> {
                    val ll = holder.content as LinearLayout
                    ll.removeAllViews()
                    val maxCols = b.rows.maxOfOrNull { it.cells.size } ?: 0
                    for (row in b.rows) {
                        val rowView = LinearLayout(this@OfficeViewerActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setBackgroundColor(pageBg)
                        }
                        for (ci in 0 until maxCols) {
                            val spans = row.cells.getOrNull(ci) ?: emptyList()
                            val cell = TextView(this@OfficeViewerActivity).apply {
                                textSize = 13f
                                setTextColor(fgPrimary)
                                val sb = SpannableStringBuilder()
                                applySpans(sb, spans, 13f)
                                text = sb
                                setPadding(dp(10), dp(6), dp(10), dp(6))
                                // 单元格描边
                                background = android.graphics.drawable.GradientDrawable().apply {
                                    setColor(android.graphics.Color.TRANSPARENT)
                                    setStroke(dp(1), divider)
                                }
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            }
                            rowView.addView(cell)
                            if (ci < maxCols - 1) {
                                rowView.addView(View(this@OfficeViewerActivity).apply {
                                    layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
                                })
                            }
                        }
                        ll.addView(rowView)
                        ll.addView(View(this@OfficeViewerActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                        })
                    }
                }
                is OfficeBlock.Notice -> {
                    (holder.content as TextView).text = b.text
                }
                is OfficeBlock.SheetHeader -> { /* 不走此 adapter */ }
                is OfficeBlock.Slide -> {
                    val container = holder.content as ViewGroup
                    container.removeAllViews()
                    container.addView(buildSlideView(b))
                }
            }
        }
    }

    private class BlockHolder(val container: ViewGroup, val content: View) : RecyclerView.ViewHolder(container)

    private class FrameContainer(ctx: android.content.Context) : LinearLayout(ctx) {
        init {
            orientation = VERTICAL
        }
    }

    // ---------- pptx 幻灯片渲染 ----------

    private fun buildSlideView(s: OfficeBlock.Slide): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val label = TextView(this).apply {
            text = "${s.index} / ${s.total}"
            textSize = 11f; setTextColor(fgSecondary); gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(6))
        }
        card.addView(label)

        val slideW = dp(340)
        val scale = slideW.toFloat() / OfficeUtil.emuToDp(s.slideWEmu).coerceAtLeast(1)
        val slideH = (OfficeUtil.emuToDp(s.slideHEmu) * scale).toInt().coerceAtLeast(120)

        val frame = android.widget.FrameLayout(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(slideW, slideH).apply { gravity = Gravity.CENTER_HORIZONTAL }
            clipChildren = true
        }
        if (com.limao.unzip.UiTheme.isNight()) frame.setBackgroundColor(0xFFF2F2F2.toInt())

        // 按形状添加（叠放顺序即文档顺序）
        for (shape in s.shapes) {
            val left = (OfficeUtil.emuToDp(shape.xEmu) * scale).toInt()
            val top = (OfficeUtil.emuToDp(shape.yEmu) * scale).toInt()
            val sw = (OfficeUtil.emuToDp(shape.wEmu) * scale).toInt().coerceAtLeast(24)
            val sh = (OfficeUtil.emuToDp(shape.hEmu) * scale).toInt().coerceAtLeast(18)
            val child: View = if (shape.imagePath != null) {
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                }
            } else {
                TextView(this).apply {
                    setTextColor(0xFF222222.toInt())
                    val sb = SpannableStringBuilder()
                    val base = 12f
                    var maxLine = 0f
                    for ((li, line) in shape.text.withIndex()) {
                        if (li > 0) sb.append("\n")
                        val lineStart = sb.length
                        applySpans(sb, line.spans, base)
                        val big = line.spans.maxOfOrNull { it.sizeSp ?: base } ?: base
                        if (big > maxLine) maxLine = big
                        if (big > base * 1.2) {
                            // 首行大字号（标题）：按幻灯片缩放
                            val rel = big / base
                            sb.setSpan(RelativeSizeSpan(rel * (slideW / 340f).coerceIn(0.8f, 1.4f)), lineStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        when (line.align) {
                            OfficeBlock.ALIGN_CENTER -> gravity = Gravity.CENTER_HORIZONTAL
                            OfficeBlock.ALIGN_RIGHT -> gravity = Gravity.END
                        }
                    }
                    text = sb
                    textSize = base * (slideW / 340f).coerceIn(0.8f, 1.4f)
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                }
            }
            val params = android.widget.FrameLayout.LayoutParams(sw, sh)
            params.leftMargin = left
            params.topMargin = top
            frame.addView(child, params)
            if (shape.imagePath != null && docxZip != null) {
                val iv = child as ImageView
                Thread {
                    val bmp = docxZip?.let { OfficeUtil.decodeImage(it, shape.imagePath!!, 720) }
                    iv.post { if (bmp != null) iv.setImageBitmap(bmp) }
                }.start()
            }
        }
        card.addView(frame)
        return card
    }

    // ---------- xlsx 网格渲染 ----------

    private inner class SheetAdapter(private val sheet: OfficeBlock.SheetHeader) :
        RecyclerView.Adapter<RowHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val row = LinearLayout(this@OfficeViewerActivity).apply { orientation = LinearLayout.HORIZONTAL }
            return RowHolder(row)
        }

        override fun getItemCount() = sheet.rows.size

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val row = holder.view as LinearLayout
            row.removeAllViews()
            val cells = sheet.rows[position]
            for (ci in 0 until sheet.colCount) {
                val cell = cells.getOrNull(ci)
                val tv = TextView(this@OfficeViewerActivity).apply {
                    textSize = 12.5f
                    setTextColor(fgPrimary)
                    text = cell?.text ?: ""
                    setTypeface(typeface, if (cell?.bold == true) Typeface.BOLD else Typeface.NORMAL)
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.TRANSPARENT)
                        setStroke(dp(1), divider)
                    }
                    layoutParams = LinearLayout.LayoutParams(sheet.colWidthDp.getOrNull(ci) ?: 60, ViewGroup.LayoutParams.WRAP_CONTENT)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                row.addView(tv)
            }
        }
    }

    private class RowHolder(val view: View) : RecyclerView.ViewHolder(view)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        runCatching { docxZip?.close() }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
