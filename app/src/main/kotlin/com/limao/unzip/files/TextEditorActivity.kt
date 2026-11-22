package com.limao.unzip.files

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.Layout
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import com.limao.unzip.R
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 文本查看/编辑/阅读器（三合一）：
 *  - 编辑模式：UTF-8/GBK 自适应，全部大小可编辑（上限 50MB），原子保存
 *  - 阅读器（番茄小说风格）：
 *      · 进入提速：后台读完后 fullText 即可用，直接切片显示首屏，不等编辑框灌完
 *      · 双翻页模式：上下滚动 / 左右翻页（StaticLayout 惰性二分分页）
 *      · 调节：亮度遮罩、三种字体、字号、行距、五种背景、进度跳转
 *      · 自动阅读：翻页定时翻页 / 滚动匀速滚动
 *      · 设置全部持久化；点击中央隐藏/呼出控制层
 *  - 打开速度：采样式编码检测（前 8KB）+ 单次流式读取
 */
class TextEditorActivity : AppCompatActivity() {

    private lateinit var file: File
    private lateinit var edit: EditText
    private lateinit var status: TextView
    private lateinit var titleView: TextView
    private lateinit var saveBtn: TextView
    private lateinit var bar: LinearLayout
    private lateinit var editContainer: LinearLayout
    private var dirty = false
    private var loaded = false
    private var charset: Charset = StandardCharsets.UTF_8
    private var readOnly = false
    private var isOffice = false
    private var suppressDirty = false
    private var pendingText: String? = null
    private var cachedLines = 0
    private var cachedChars = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 完整文本：后台读取完成后立即可用，是阅读器的数据源（进入阅读器无需等待编辑框灌入） */
    private var fullText: String? = null


// ==================== 阅读器（番茄小说风格 · 章节分页 + RecyclerView 虚拟化） ====================
    // 架构：全文按章切分（ReaderPaginator），当前章后台分页 → 页首偏移数组 →
    // RecyclerView 按页渲染。字体/行距变更只重排当前章（<1s），翻页/滚动零重排，
    // 按键只改状态不重建视图 —— 彻底消除旧"巨型 TextView"方案的整段重排卡顿。

    // ---- 阅读器状态 ----
    private var readingMode = false
    private var rFontSize = 20f
    private var rBgIndex = FOLLOW_SYSTEM
    private var rLineIdx = 1 // 0 紧凑 1 标准 2 宽松
    private var rTypefaceIdx = 0 // 0 衬线 1 无衬线 2 等宽
    private var rPageMode = 0 // 0 滚动 1 翻页
    private var rAutoIdx = 0 // 0 关 1 慢 2 中 3 快
    private var rBrightness = 1.0f
    private var controlsVisible = true

    private var chapters: List<ReaderPaginator.Chapter> = emptyList()
    private var curChapter = 0
    private var pageStarts = IntArray(0)
    private var paginating = false
    private var curPage = 0
    private var lastReadOffset = 0
    private var pageBoxW = 0
    private var pageBoxH = 0
    private val readerPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var fontSizeLabel: TextView? = null
    private var autoIconLabel: TextView? = null
    private var autoSpeedMemo = 2
    private var pendingJumpOffset = -1
    private var pendingPaginate: Int? = null
    private val chipRefreshers = ArrayList<() -> Unit>()

    // 视图
    private lateinit var readingRoot: FrameLayout
    private var contentRecycler: RecyclerView? = null
    private var pageAdapter: RecyclerView.Adapter<*>? = null
    private var overlay: View? = null
    private var topBar: LinearLayout? = null
    private var controlBar: LinearLayout? = null
    private var jumpSeek: SeekBar? = null
    private var settingsPanel: View? = null
    private var tocMask: View? = null
    private var tocPanel: LinearLayout? = null
    private var tocList: RecyclerView? = null
    private var tocCountLabel: TextView? = null
    private var readingProgressLabel: TextView? = null
    private var topProgress: TextView? = null

    private fun toggleReadingMode() {
        if (isOffice) { Toast.makeText(this, R.string.reader_office_unsupported, Toast.LENGTH_SHORT).show(); return }
        if (fullText == null) { Toast.makeText(this, R.string.editor_loading, Toast.LENGTH_SHORT).show(); return }
        if (readingMode) exitReadingMode() else enterReadingMode()
    }

    private fun enterReadingMode() {
        readingMode = true
        stopAuto()
        controlsVisible = true
        bar.visibility = View.GONE
        status.visibility = View.GONE
        edit.visibility = View.GONE
        saveBtn.visibility = View.GONE
        if (chapters.isEmpty() && fullText != null) chapters = ReaderPaginator.splitChapters(fullText!!)
        if (curChapter < 0 || curChapter >= chapters.size) curChapter = 0
        pendingJumpOffset = -1
        pendingPaginate = null
        // 恢复上次阅读位置（按文件名记录书签）
        lastReadOffset = prefs.getInt("rp_off_" + file.name, 0)
        val bm = prefs.getInt("rp_ch_" + file.name, -1)
        if (bm in 0 until chapters.size) curChapter = bm
        buildReadingRoot()
        applyReaderTheme()
        readingRoot.post { measurePageBoxAndPaginate(true) }
    }

    private fun buildReadingRoot() {
        readingRoot = FrameLayout(this).apply { fitsSystemWindows = true }

        contentRecycler = RecyclerView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        readingRoot.addView(contentRecycler, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        overlay = View(this).apply { setBackgroundColor(Color.BLACK); isClickable = false }
        readingRoot.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        topBar = LinearLayout(this).apply {
            setBackgroundColor(chromeBg)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
        }
        topBar!!.addView(TextView(this).apply {
            text = "‹"; textSize = 24f; setTextColor(chromeFg); setPadding(dp(8), 0, dp(14), 0)
            setOnClickListener { exitReadingMode() }
        })
        topBar!!.addView(TextView(this).apply {
            text = file.name; textSize = 14f; setTextColor(chromeFg); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        topProgress = TextView(this).apply {
            textSize = 12f; setTextColor(chromeFgSub); setPadding(dp(8), 0, dp(4), 0)
        }
        topBar!!.addView(topProgress)

        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(chromeBg)
            setPadding(dp(12), dp(4), dp(12), dp(8))
            isClickable = true
        }
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        progressRow.addView(TextView(this).apply {
            text = "上一页"; textSize = 13f; setTextColor(chromeFg)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setOnClickListener { pageTurn(-1) }
        })
        jumpSeek = SeekBar(this).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) topProgress?.text = "跳转 $value%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { jumpToPercent(sb?.progress ?: 0) }
            })
        }
        progressRow.addView(jumpSeek)
        progressRow.addView(TextView(this).apply {
            text = "下一页"; textSize = 13f; setTextColor(chromeFg)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setOnClickListener { pageTurn(1) }
        })
        controlBar!!.addView(progressRow)
        val iconRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun icon(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 13f; setTextColor(chromeFg); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { onClick() }
        }
        iconRow.addView(icon("☰ 目录") { toggleToc() })
        val autoIcon = icon(autoLabel()) { toggleAuto() }
        autoIconLabel = autoIcon
        iconRow.addView(autoIcon)
        iconRow.addView(icon("🌙 夜间") { toggleNightBg() })
        iconRow.addView(icon("⚙ 设置") { toggleSettings() })
        iconRow.addView(icon("📖 " + getString(R.string.edit_mode)) { exitReadingMode() })
        controlBar!!.addView(iconRow)

        readingRoot.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        readingRoot.addView(controlBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        settingsPanel = buildSettingsPanel()
        settingsPanel!!.visibility = View.GONE
        settingsPanel!!.isClickable = true
        readingRoot.addView(settingsPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(330), Gravity.BOTTOM))

        tocMask = View(this).apply {
            setBackgroundColor(0x88000000.toInt())
            setOnClickListener { hideToc() }
        }
        tocMask!!.visibility = View.GONE
        readingRoot.addView(tocMask, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tocPanel = buildTocPanel()
        tocPanel!!.visibility = View.GONE
        readingRoot.addView(tocPanel, FrameLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))

        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleTap(e.x)
                return true
            }
        })
        contentRecycler!!.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                tapDetector.onTouchEvent(e)
                return false
            }
        })
        contentRecycler!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { updateReadingProgress() }
        })

        applyPageMode()
        setContentView(readingRoot)
    }

    private fun applyPageMode() {
        val rv = contentRecycler ?: return
        rv.setOnFlingListener(null) // PagerSnapHelper 重复 attach 会抛 "OnFlingListener already set"
        rv.layoutManager = if (rPageMode == 1) {
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false).also {
                androidx.recyclerview.widget.PagerSnapHelper().attachToRecyclerView(rv)
            }
        } else {
            LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        }
        pageAdapter?.let { rv.adapter = it }
    }

    private fun applyReaderTheme() {
        val eff = effectiveBgIndex()
        val bg = BG_COLORS[eff]
        readingRoot.setBackgroundColor(bg)
        contentRecycler?.setBackgroundColor(bg)
        overlay?.setAlpha((1f - rBrightness) * 0.72f)
        topBar?.setBackgroundColor(chromeBg)
        controlBar?.setBackgroundColor(chromeBg)
        topProgress?.setTextColor(chromeFgSub)
        readerPaint.textSize = rFontSize * resources.displayMetrics.scaledDensity
        readerPaint.typeface = TYPEFACES[rTypefaceIdx]
    }

    private fun effectiveBgIndex(): Int =
        if (rBgIndex == FOLLOW_SYSTEM) (if (nightNow()) 4 else 0) else rBgIndex

    private fun toggleNightBg() {
        val dark = rBgIndex == 4 || (rBgIndex == FOLLOW_SYSTEM && nightNow())
        rBgIndex = if (dark) 0 else 4
        onReaderSettingChanged(false)
    }

    private val relayoutRunnable = Runnable { measurePageBoxAndPaginate(true) }

    /** 防抖重排：芯片即时变色，120ms 后只排一次版 —— 按键零延迟 */
    private fun scheduleRelayout() {
        mainHandler.removeCallbacks(relayoutRunnable)
        mainHandler.postDelayed(relayoutRunnable, 120)
    }

    private fun onReaderSettingChanged(needsRelayout: Boolean) {
        applyReaderTheme()
        saveReaderPrefs()
        if (needsRelayout) scheduleRelayout() else pageAdapter?.notifyDataSetChanged()
    }

    private fun toggleSettings() {
        settingsPanel?.visibility = if (settingsPanel?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun toggleControls() {
        controlsVisible = !controlsVisible
        topBar?.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        controlBar?.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        if (!controlsVisible) settingsPanel?.visibility = View.GONE
    }

    private fun handleTap(x: Float) {
        if (settingsPanel?.visibility == View.VISIBLE) { settingsPanel?.visibility = View.GONE; return }
        if (tocPanelVisible()) { hideToc(); return }
        if (rAutoIdx > 0) { toggleAuto(); return }
        val w = readingRoot.width
        if (rPageMode == 1) {
            when {
                x < w / 3f -> pageTurn(-1)
                x > w * 2f / 3f -> pageTurn(1)
                else -> toggleControls()
            }
        } else {
            toggleControls()
        }
    }

    // ---- 分页与页面渲染 ----

    private fun measurePageBoxAndPaginate(restore: Boolean) {
        val rv = contentRecycler ?: return
        pageBoxW = maxOf(1, rv.width - dp(52))
        pageBoxH = maxOf(1, rv.height - dp(68))
        readerPaint.textSize = rFontSize * resources.displayMetrics.scaledDensity
        readerPaint.typeface = TYPEFACES[rTypefaceIdx]
        paginateCurrent(if (restore) lastReadOffset else -1)
    }

    /** 当前章分页（后台线程）→ 渲染；restoreOffset>=0 时定位到该偏移所在页。
     *  分页进行中的新请求会排队（pendingPaginate），完成后自动补排最新一次。 */
    private fun paginateCurrent(restoreOffset: Int) {
        val ft = fullText ?: return
        if (chapters.isEmpty()) return
        if (curChapter >= chapters.size) curChapter = 0
        if (paginating) { pendingPaginate = restoreOffset; return }
        paginating = true
        readingProgressLabel?.text = "排版中…"
        val ch = curChapter
        val start = chapters[ch].start
        val end = chapterEndOf(ch)
        val paintCopy = android.text.TextPaint(readerPaint)
        val pw = pageBoxW; val ph = pageBoxH
        val add = dp(3).toFloat(); val mult = LINE_MULTS[rLineIdx]
        Thread {
            val pag = ReaderPaginator(ft, paintCopy, pw, ph, add, mult)
            val starts = pag.paginateChapter(start, end)
            runOnUiThread {
                paginating = false
                if (!readingMode || ch != curChapter) { drainPendingPaginate(); return@runOnUiThread }
                pageStarts = starts
                attachPages()
                val target = if (pendingJumpOffset >= 0) pendingJumpOffset else restoreOffset
                pendingJumpOffset = -1
                if (target in start until end) {
                    var page = 0
                    for (i in starts.indices) if (target >= starts[i]) page = i
                    scrollToPage(page, smooth = false)
                } else scrollToPage(0, smooth = false)
                updateReadingProgress()
                drainPendingPaginate()
            }
        }.start()
    }

    private fun drainPendingPaginate() {
        val r = pendingPaginate
        if (r != null && readingMode) {
            pendingPaginate = null
            paginateCurrent(r)
        }
    }

    private fun attachPages() {
        val rv = contentRecycler ?: return
        val ft = fullText ?: return
        if (pageStarts.isEmpty()) return
        val chEnd = chapterEndOf(curChapter)
        val adapter = object : RecyclerView.Adapter<PageHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
                val tv = TextView(this@TextEditorActivity).apply {
                    textSize = rFontSize
                    setTextColor(BG_TEXT_COLORS[effectiveBgIndex()])
                    setTypeface(TYPEFACES[rTypefaceIdx])
                    setLineSpacing(dp(3).toFloat(), LINE_MULTS[rLineIdx])
                    setPadding(dp(26), dp(34), dp(26), dp(34))
                    includeFontPadding = false
                }
                return PageHolder(tv)
            }

            override fun getItemCount() = pageStarts.size

            override fun onBindViewHolder(h: PageHolder, pos: Int) {
                val s = pageStarts[pos]
                val e = if (pos + 1 < pageStarts.size) pageStarts[pos + 1] else chEnd
                h.view.text = ft.subSequence(s, e)
                h.view.setTextColor(BG_TEXT_COLORS[effectiveBgIndex()])
            }
        }
        rv.adapter = adapter
        pageAdapter = adapter
    }

    private class PageHolder(val view: TextView) : RecyclerView.ViewHolder(view)

    private fun chapterEndOf(idx: Int): Int =
        if (idx + 1 < chapters.size) chapters[idx + 1].start else (fullText?.length ?: 0)

    /** 翻页：dir +1 下一页 / -1 上一页（章内） */
    private fun pageTurn(dir: Int) {
        val rv = contentRecycler ?: return
        if (paginating || pageStarts.isEmpty()) return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val cur = lm.findFirstVisibleItemPosition()
        val target = cur + dir
        if (target < 0 || target >= pageStarts.size) return
        rv.smoothScrollToPosition(target)
    }

    private fun scrollToPage(page: Int, smooth: Boolean) {
        val rv = contentRecycler ?: return
        if (pageStarts.isEmpty()) return
        curPage = page.coerceIn(0, pageStarts.size - 1)
        if (smooth) rv.smoothScrollToPosition(curPage) else rv.scrollToPosition(curPage)
        updateReadingProgress()
    }

    private fun updateReadingProgress() {
        val ft = fullText ?: return
        if (pageStarts.isEmpty()) return
        val lm = contentRecycler?.layoutManager as? LinearLayoutManager
        val pos = (lm?.findFirstVisibleItemPosition() ?: curPage).coerceIn(0, pageStarts.size - 1)
        curPage = pos
        val pageOffset = pageStarts[pos]
        lastReadOffset = pageOffset
        val pct = (pageOffset * 100 / ft.length.coerceAtLeast(1)).coerceIn(0, 100)
        readingProgressLabel?.text = "第 ${pos + 1}/${pageStarts.size} 页 · $pct%"
        topProgress?.text = "$pct%"
    }

    // ---- 位置 ----

    private fun currentReadingOffset(): Int {
        val ft = fullText ?: return 0
        val off = pageStarts.getOrNull(curPage) ?: lastReadOffset
        return off.coerceIn(0, (ft.length - 1).coerceAtLeast(0))
    }

    private fun chapterIndexFor(offset: Int): Int {
        var idx = 0
        for (i in chapters.indices) if (chapters[i].start <= offset) idx = i
        return idx
    }

    private fun jumpToPercent(value: Int) {
        val ft = fullText ?: return
        val target = ((ft.length - 1).coerceAtLeast(0)) * value / 100
        lastReadOffset = target
        curChapter = chapterIndexFor(target)
        pendingJumpOffset = target
        paginateCurrent(-1)
    }

    // ---- 自动阅读 ----

    private fun stopAuto() {
        mainHandler.removeCallbacks(autoFlipRunnable)
        mainHandler.removeCallbacks(autoScrollRunnable)
    }

    private val autoFlipRunnable = object : Runnable {
        override fun run() {
            if (!readingMode || rPageMode != 1 || rAutoIdx <= 0) return
            autoAdvanceOrStop()
            if (readingMode && rAutoIdx > 0) mainHandler.postDelayed(this, AUTO_FLIP_S[rAutoIdx] * 1000L)
        }
    }

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (!readingMode || rPageMode != 0 || rAutoIdx <= 0) return
            val rv = contentRecycler ?: return
            if (pageStarts.isEmpty()) return
            val lm = rv.layoutManager as? LinearLayoutManager
            val last = lm?.findLastVisibleItemPosition() ?: 0
            if (last >= pageStarts.size - 1 && !paginating) {
                // 本章读完：自动进入下一章
                if (curChapter + 1 < chapters.size) {
                    curChapter++
                    pendingJumpOffset = -1
                    paginateCurrent(-1)
                } else {
                    toggleAuto()
                    return
                }
            } else if (!paginating) {
                rv.smoothScrollBy(0, AUTO_SCROLL_PX[rAutoIdx] / 20)
            }
            if (readingMode && rAutoIdx > 0) mainHandler.postDelayed(this, 50L)
        }
    }

    private fun autoRunnableForMode(): Runnable = if (rPageMode == 1) autoFlipRunnable else autoScrollRunnable

    private fun autoAdvanceOrStop() {
        if (paginating) return
        val rv = contentRecycler ?: return
        if (pageStarts.isEmpty()) return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val cur = lm.findFirstVisibleItemPosition()
        if (cur >= pageStarts.size - 1) {
            if (curChapter + 1 < chapters.size) {
                curChapter++
                pendingJumpOffset = -1
                paginateCurrent(-1)
            } else {
                toggleAuto()
            }
        } else {
            pageTurn(1)
        }
    }

    private fun toggleAuto() {
        if (rAutoIdx > 0) {
            autoSpeedMemo = rAutoIdx
            rAutoIdx = 0
            stopAuto()
        } else {
            rAutoIdx = autoSpeedMemo.coerceIn(1, 3)
            mainHandler.postDelayed(autoRunnableForMode(), 300)
        }
        saveReaderPrefs()
        refreshAutoLabel()
    }

    private fun refreshAutoLabel() { autoIconLabel?.text = autoLabel() }

    private fun autoLabel(): String = if (rAutoIdx == 0) "▶ 自动" else "⏸ 停止"

    // ---- 目录 ----

    private fun toggleToc() {
        if (tocPanelVisible()) hideToc() else showToc()
    }

    private fun tocPanelVisible(): Boolean = tocPanel?.visibility == View.VISIBLE

    private fun showToc() {
        settingsPanel?.visibility = View.GONE
        val root = readingRoot
        val mask = tocMask ?: return
        val panel = tocPanel ?: return
        val cbIdx = controlBar?.let { root.indexOfChild(it) } ?: root.childCount
        if (mask.parent == null) root.addView(mask, cbIdx, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (panel.parent == null) root.addView(panel, cbIdx + 1, FrameLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        rebuildTocList()
        mask.visibility = View.VISIBLE
        mask.alpha = 0f
        mask.animate().alpha(1f).setDuration(200).start()
        panel.visibility = View.VISIBLE
        panel.translationX = -dp(300).toFloat()
        panel.animate().translationX(0f).setDuration(200).start()
    }

    private fun hideToc() {
        tocPanel?.animate()?.translationX(-dp(300).toFloat())?.setDuration(180)?.withEndAction {
            tocPanel?.visibility = View.GONE
        }?.start()
        tocMask?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            tocMask?.visibility = View.GONE
        }?.start()
    }

    private fun buildTocPanel(): LinearLayout {
        val night = nightNow()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (night) 0xFF1E2126.toInt() else 0xFFFFFFFF.toInt())
            isClickable = true
        }
        panel.addView(TextView(this).apply {
            text = "目录"; textSize = 17f
            setTextColor(if (night) 0xFFEEEEEE.toInt() else 0xFF1D1B20.toInt())
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(18), dp(16), dp(18), dp(10))
        })
        tocCountLabel = TextView(this).apply {
            textSize = 12f; setTextColor(chromeFgSub)
            setPadding(dp(18), 0, dp(18), dp(8))
        }
        panel.addView(tocCountLabel)
        tocList = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@TextEditorActivity) }
        panel.addView(tocList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return panel
    }

    private class TocHolder(val view: TextView) : RecyclerView.ViewHolder(view)

    private fun rebuildTocList() {
        val list = tocList ?: return
        if (chapters.isEmpty()) return
        val curOff = currentReadingOffset()
        var curIdx = 0
        for (i in chapters.indices) if (chapters[i].start <= curOff) curIdx = i
        tocCountLabel?.text = "共 ${chapters.size} 章 · ${(fullText?.length ?: 0)} 字"
        val night = nightNow()
        list.adapter = object : RecyclerView.Adapter<TocHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TocHolder {
                val tv = TextView(this@TextEditorActivity).apply {
                    textSize = 14f
                    setPadding(dp(18), dp(12), dp(18), dp(12))
                }
                return TocHolder(tv)
            }

            override fun getItemCount() = chapters.size

            override fun onBindViewHolder(h: TocHolder, pos: Int) {
                val ch = chapters[pos]
                val cur = pos == curIdx
                h.view.text = ch.title
                h.view.setTextColor(
                    if (cur) 0xFFFF8A34.toInt()
                    else if (night) 0xFFDDDDDD.toInt() else 0xFF333333.toInt(),
                )
                h.view.setTypeface(h.view.typeface, if (cur) Typeface.BOLD else Typeface.NORMAL)
                h.view.setOnClickListener {
                    hideToc()
                    jumpToOffset(ch.start)
                }
            }
        }
        list.scrollToPosition(curIdx)
    }

    /** 跳转到指定字符偏移所在章（自动定位页） */
    private fun jumpToOffset(offset: Int) {
        val ft = fullText ?: return
        val target = offset.coerceIn(0, (ft.length - 1).coerceAtLeast(0))
        lastReadOffset = target
        curChapter = chapterIndexFor(target)
        pendingJumpOffset = target
        paginateCurrent(-1)
    }

    // ---- 设置面板 ----

    private fun buildSettingsPanel(): ScrollView {
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(chromeBg)
        panel.setPadding(dp(18), dp(14), dp(18), dp(18))
        panel.isClickable = true
        chipRefreshers.clear()

        val label: (String) -> TextView = { s ->
            TextView(this).apply {
                text = s; textSize = 12f; setTextColor(chromeFgSub)
                setPadding(0, dp(10), 0, dp(4))
            }
        }

        fun chipRow(options: List<String>, currentIdx: () -> Int, onPick: (Int) -> Unit): LinearLayout {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val chips = ArrayList<TextView>()
            fun refresh() {
                for (i in chips.indices) {
                    val sel = i == currentIdx()
                    chips[i].setTextColor(if (sel) 0xFF64B5FF.toInt() else chipFg)
                    chips[i].setBackgroundResource(if (sel) R.drawable.chip_selected else R.drawable.chip_normal)
                }
            }
            for (i in options.indices) {
                val c = TextView(this)
                c.text = options[i]
                c.textSize = 13f
                c.setPadding(dp(14), dp(6), dp(14), dp(6))
                c.gravity = Gravity.CENTER
                c.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }
                c.setOnClickListener {
                    onPick(i)
                    refresh()
                    for (r in chipRefreshers) r()
                }
                chips.add(c)
                row.addView(c)
            }
            refresh()
            chipRefreshers.add { refresh() }
            return row
        }

        panel.addView(label("亮度（护眼遮罩）"))
        val bright = SeekBar(this)
        bright.max = 100
        bright.progress = ((rBrightness - 0.15f) / 0.85f * 100).toInt()
        bright.setPadding(0, dp(6), 0, dp(6))
        bright.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                rBrightness = 0.15f + value / 100f * 0.85f
                this@TextEditorActivity.overlay?.setAlpha((1f - rBrightness) * 0.72f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { saveReaderPrefs() }
        })
        panel.addView(bright)

        panel.addView(label("字体"))
        panel.addView(chipRow(TF_NAMES.toList(), { rTypefaceIdx }) {
            rTypefaceIdx = it; onReaderSettingChanged(true)
        })

        panel.addView(label("字号"))
        val fontRow = LinearLayout(this)
        fontRow.orientation = LinearLayout.HORIZONTAL
        fontRow.gravity = Gravity.CENTER
        fontRow.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        val fontMinus = TextView(this)
        fontMinus.text = "A−"; fontMinus.textSize = 16f; fontMinus.setTextColor(chipFg)
        fontMinus.gravity = Gravity.CENTER
        fontMinus.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        fontMinus.setPadding(0, dp(6), 0, dp(6))
        fontMinus.setOnClickListener {
            rFontSize = (rFontSize - 2f).coerceAtLeast(12f)
            fontSizeLabel?.text = "${rFontSize.toInt()}"
            onReaderSettingChanged(true)
        }
        fontRow.addView(fontMinus)
        fontSizeLabel = TextView(this)
        fontSizeLabel!!.text = "${rFontSize.toInt()}"; fontSizeLabel!!.textSize = 15f
        fontSizeLabel!!.setTextColor(0xFF64B5FF.toInt()); fontSizeLabel!!.gravity = Gravity.CENTER
        fontSizeLabel!!.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        fontRow.addView(fontSizeLabel)
        val fontPlus = TextView(this)
        fontPlus.text = "A＋"; fontPlus.textSize = 20f; fontPlus.setTextColor(chipFg)
        fontPlus.gravity = Gravity.CENTER
        fontPlus.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        fontPlus.setPadding(0, dp(6), 0, dp(6))
        fontPlus.setOnClickListener {
            rFontSize = (rFontSize + 2f).coerceAtMost(50f)
            fontSizeLabel?.text = "${rFontSize.toInt()}"
            onReaderSettingChanged(true)
        }
        fontRow.addView(fontPlus)
        panel.addView(fontRow)

        panel.addView(label("行距"))
        panel.addView(chipRow(LINE_NAMES.toList(), { rLineIdx }) {
            rLineIdx = it; onReaderSettingChanged(true)
        })

        panel.addView(label("背景（跟随 = 随系统日夜自动切换）"))
        val bgRow = LinearLayout(this)
        bgRow.orientation = LinearLayout.HORIZONTAL
        bgRow.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        val bgChips = ArrayList<Pair<TextView, Int>>()
        fun refreshBg() {
            for ((c, idx) in bgChips) {
                val sel = idx == rBgIndex
                if (idx == FOLLOW_SYSTEM) {
                    c.setTextColor(if (sel) 0xFF0D2B52.toInt() else chipFg)
                    c.setBackgroundColor(if (sel) 0xFF64B5FF.toInt() else 0xFF888888.toInt())
                } else {
                    c.setTextColor(if (sel) 0xFF64B5FF.toInt() else BG_TEXT_COLORS[idx])
                    c.setBackgroundColor(BG_COLORS[idx])
                }
            }
        }
        fun bgChip(labelStr: String, idx: Int) {
            val c = TextView(this)
            c.text = labelStr; c.textSize = 13f
            c.setPadding(dp(14), dp(8), dp(14), dp(8))
            c.gravity = Gravity.CENTER
            c.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }
            c.setOnClickListener {
                rBgIndex = idx
                onReaderSettingChanged(false) // 颜色不影响排版，即时生效
                refreshBg()
            }
            bgChips.add(c to idx)
            bgRow.addView(c)
        }
        bgChip("跟随", FOLLOW_SYSTEM)
        BG_NAMES.forEachIndexed { i, name -> bgChip(name, i) }
        refreshBg()
        chipRefreshers.add { refreshBg() }
        panel.addView(bgRow)

        panel.addView(label("翻页方式"))
        panel.addView(chipRow(listOf("上下滚动", "左右翻页"), { rPageMode }) {
            rPageMode = it
            saveReaderPrefs()
            applyPageMode()
            readingRoot.post { measurePageBoxAndPaginate(true) }
        })

        panel.addView(label("自动阅读（翻页：慢20s/中10s/快5s · 滚动：匀速）"))
        panel.addView(chipRow(AUTO_NAMES.toList(), { rAutoIdx }) { i ->
            rAutoIdx = i
            saveReaderPrefs()
            refreshAutoLabel()
            stopAuto()
            if (i > 0) mainHandler.postDelayed(autoRunnableForMode(), 400)
        })

        panel.addView(label("进度跳转（拖动选择，松手跳转）"))
        val jump = SeekBar(this)
        jump.max = 100
        jump.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) readingProgressLabel?.text = "跳转到 $value%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { jumpToPercent(sb?.progress ?: 0) }
        })
        panel.addView(jump)
        readingProgressLabel = TextView(this)
        readingProgressLabel!!.textSize = 12f
        readingProgressLabel!!.setTextColor(chromeFgSub)
        readingProgressLabel!!.gravity = Gravity.CENTER
        readingProgressLabel!!.setPadding(0, dp(8), 0, 0)
        panel.addView(readingProgressLabel)
        scroll.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun exitReadingMode() {
        readingMode = false
        stopAuto()
        mainHandler.removeCallbacks(relayoutRunnable)
        if (pageStarts.isNotEmpty()) lastReadOffset = currentReadingOffset()
        saveReadingBookmark()
        paginating = false
        pendingPaginate = null
        contentRecycler?.adapter = null
        pageAdapter = null
        contentRecycler = null
        overlay = null
        topBar = null
        controlBar = null
        jumpSeek = null
        settingsPanel = null
        tocMask = null
        tocPanel = null
        tocList = null
        tocCountLabel = null
        readingProgressLabel = null
        topProgress = null
        fontSizeLabel = null
        autoIconLabel = null
        chipRefreshers.clear()
        setContentView(editContainer)
        saveBtn.visibility = View.VISIBLE
        bar.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        edit.visibility = View.VISIBLE
        updateStatus()
    }

    /** 阅读书签：按文件名记录章节/偏移，退出阅读或离开页面时保存 */
    private fun saveReadingBookmark() {
        if (chapters.isEmpty()) return
        prefs.edit()
            .putInt("rp_off_" + file.name, lastReadOffset)
            .putInt("rp_ch_" + file.name, curChapter)
            .apply()
    }

    override fun onPause() {
        if (readingMode && pageStarts.isNotEmpty()) {
            lastReadOffset = currentReadingOffset()
            saveReadingBookmark()
        }
        super.onPause()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PATH = "path"
        private const val CHUNK_CHARS = 65_536
        private const val CHUNK_DELAY_MS = 24L
        private const val LIVE_STAT_LIMIT = 500_000
        private const val PREFS = "reader_prefs"
    private const val FOLLOW_SYSTEM = -1

        private val BG_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(), // 白
            0xFFF5EFDC.toInt(), // 米
            0xFFE8DCC8.toInt(), // 棕
            0xFFC7EDCC.toInt(), // 护眼绿
            0xFF1E1E1E.toInt(), // 黑
        )
        private val BG_TEXT_COLORS = intArrayOf(
            0xFF1D1B20.toInt(),
            0xFF3B2F20.toInt(),
            0xFF4A3728.toInt(),
            0xFF2D3B2D.toInt(),
            0xFFB8B8B8.toInt(),
        )
        private val BG_NAMES = arrayOf("白", "米", "棕", "绿", "黑")
        private val LINE_NAMES = arrayOf("紧凑", "标准", "宽松")
        private val LINE_MULTS = floatArrayOf(1.05f, 1.25f, 1.5f)
        private val TF_NAMES = arrayOf("衬线", "无衬线", "等宽")
        private val TYPEFACES = arrayOf(Typeface.SERIF, Typeface.SANS_SERIF, Typeface.MONOSPACE)
        private val AUTO_NAMES = arrayOf("停", "慢", "中", "快")
        private val AUTO_SCROLL_PX = intArrayOf(0, 60, 140, 280) // px/s（滚动模式）
        private val AUTO_FLIP_S = intArrayOf(0, 20, 10, 5) // 秒/页（翻页模式）
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    /** 以当前 Activity 的实时配置判断日夜（appCtx 缓存配置在系统切换日夜后不更新） */
    private fun nightNow(): Boolean = try {
        // AppCompat/Application 会缓存 uiMode 配置；读系统级设置才准确
        when (android.provider.Settings.Secure.getInt(
            contentResolver, "ui_night_mode", -1)) {
            2 -> true
            1 -> false
            else -> (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    } catch (t: Throwable) {
        (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    // 阅读器界面配色：跟随系统日夜（设置面板/控制栏/顶栏/目录一致）
    private val chromeBg get() = if (nightNow()) 0xF2151517.toInt() else 0xFFFFFFFF.toInt()
    private val chromeFg get() = if (nightNow()) 0xFFEEEEEE.toInt() else 0xFF1D1B20.toInt()
    private val chromeFgSub get() = if (nightNow()) 0xFF999999.toInt() else 0xFF888888.toInt()
    private val chipFg get() = if (nightNow()) 0xFFDDDDDD.toInt() else 0xFF1D1B20.toInt()
    private fun saveReaderPrefs() {
        prefs.edit()
            .putFloat("rf", rFontSize)
            .putInt("bg_follow", rBgIndex)
            .putInt("ln", rLineIdx)
            .putInt("tf", rTypefaceIdx)
            .putInt("pm", rPageMode)
            .putInt("au", rAutoIdx)
            .putFloat("br2", rBrightness)
            .apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 读取持久化的阅读器设置（翻页模式/字号/背景/亮度等）
        rFontSize = prefs.getFloat("rf", 20f)
        rBgIndex = prefs.getInt("bg_follow", FOLLOW_SYSTEM) // v0.9.39 迁移：旧"bg"键废弃（清除历史测试残留的黑底选择）
        rLineIdx = prefs.getInt("ln", 1)
        rTypefaceIdx = prefs.getInt("tf", 0)
        rPageMode = prefs.getInt("pm", 0)
        rAutoIdx = prefs.getInt("au", 0)
        rBrightness = prefs.getFloat("br2", 1.0f) // v0.9.39 迁移：亮度遮罩测试残留重置
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        file = File(path)
        val night = nightNow()
        val bgEditor = if (night) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt()
        val bgBar = if (night) 0xFF2B2B2B.toInt() else 0xFFF5F6F8.toInt()
        val fgPrimary = if (night) 0xFFEEEEEE.toInt() else 0xFF1D1B20.toInt()
        val fgSecondary = if (night) 0xFFDDDDDD.toInt() else 0xFF1D1B20.toInt()
        val fgMuted = if (night) 0xFF888888.toInt() else 0xFF666666.toInt()
        val accent = if (night) 0xFF82AAFF.toInt() else 0xFF1296DB.toInt()

        editContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgEditor)
            fitsSystemWindows = true
        }
        bar = LinearLayout(this).apply {
            setBackgroundColor(bgBar)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(this).apply {
            text = "‹"; textSize = 26f; setTextColor(fgSecondary); setPadding(dp(6), 0, dp(14), 0)
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        })
        titleView = TextView(this).apply {
            text = file.name; textSize = 16f; setTextColor(fgPrimary)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(titleView)
        bar.addView(TextView(this).apply {
            text = "📖"; textSize = 20f; setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { toggleReadingMode() }
        })
        saveBtn = TextView(this).apply {
            text = getString(R.string.editor_save); textSize = 15f; setTextColor(accent); setPadding(dp(8), 0, 0, 0)
            setOnClickListener { save() }
        }
        bar.addView(saveBtn)
        editContainer.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        edit = EditText(this).apply {
            setBackgroundColor(bgEditor)
            setTextColor(fgSecondary)
            setHintTextColor(fgMuted)
            textSize = 14f
            setTypeface(Typeface.MONOSPACE)
            gravity = Gravity.TOP
            setPadding(20, 16, 20, 16)
            isVerticalScrollBarEnabled = true
        }
        editContainer.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        status = TextView(this).apply {
            textSize = 12f; setTextColor(fgMuted)
            setBackgroundColor(bgBar)
            setPadding(20, 12, 20, 12)
        }
        editContainer.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(editContainer)

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (loaded && !suppressDirty) { dirty = true; updateStatus() }
            }
        })

        if (!file.exists()) { loaded = true; fullText = ""; updateStatus(); return }

        // Office（docx/xlsx/pptx）：内建查看器已在 FileOpener 分派；直接进入本页时按只读预览处理
        if (OfficeText.supported(file.name)) {
            readOnly = true
            isOffice = true
            Thread {
                val (text, full) = OfficeText.extract(file)
                runOnUiThread {
                    val truncNote = if (full) "" else "\n\n…（内容过长，已截断）"
                    suppressDirty = true
                    edit.setText(text + truncNote)
                    suppressDirty = false
                    lockIfReadonly()
                    loaded = true; dirty = false; updateStatus()
                }
            }.start()
            return
        }

        // 全部大小可编辑；>50MB 无法打开
        if (file.length() > 50L * 1024 * 1024) {
            Toast.makeText(this, getString(R.string.editor_too_large), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        readOnly = false
        val startTime = System.currentTimeMillis()
        Thread {
            val sample = ByteArray(8192)
            val sampleLen = runCatching {
                FileInputStream(file).use { it.read(sample) }
            }.getOrDefault(0)
            charset = detectCharset(sample, sampleLen)
            // 超长单行检测（压缩 JSON/日志等）：EditText 排版 O(n²)，降级只读预览前 1MB
            val hasNewline = sample.indexOf(0x0A) >= 0
            if (!hasNewline && file.length() > 2L * 1024 * 1024) {
                val previewLen = minOf(file.length(), 1L * 1024 * 1024)
                val pv = StringBuilder((previewLen / 2).toInt())
                runCatching {
                    BufferedReader(InputStreamReader(FileInputStream(file), charset), 65536).use { reader ->
                        val buf = CharArray(65536)
                        var filled = 0
                        while (filled < previewLen) {
                            val n = reader.read(buf)
                            if (n < 0) break
                            pv.append(buf, 0, n)
                            filled += n
                        }
                    }
                }
                cachedChars = file.length().toInt()
                cachedLines = 1
                mainHandler.post {
                    Toast.makeText(this, R.string.editor_single_line_preview, Toast.LENGTH_LONG).show()
                    fullText = pv.toString()
                    pendingText = pv.toString()
                    mainHandler.postDelayed({ pumpChunks() }, CHUNK_DELAY_MS)
                }
                return@Thread
            }
            // 单次流式读取
            val sb = StringBuilder(if (file.length() > 1_000_000) (file.length() / 2).toInt() else 4096)
            runCatching {
                BufferedReader(InputStreamReader(FileInputStream(file), charset), 65536).use { reader ->
                    val buf = CharArray(65536)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        sb.append(buf, 0, n)
                    }
                }
            }
            val text = sb.toString()
            cachedChars = text.length
            cachedLines = text.count { it == '\n' } + if (text.isNotEmpty()) 1 else 0
            sb.setLength(0)
            android.util.Log.d("TextEditor", "loaded ${file.length()} bytes in ${System.currentTimeMillis() - startTime}ms charset=${charset.name()}")
            // fullText 先就绪：阅读器即刻可用（无需等编辑框灌完）
            fullText = text
            pendingText = text
            mainHandler.postDelayed({ pumpChunks() }, CHUNK_DELAY_MS)
        }.start()
    }

    /** 渐进式把 pendingText 灌入编辑框：首块 setText，其余块 append（尾部增量排版）。 */
    private fun pumpChunks() {
        val src = pendingText ?: return
        if (src.isEmpty()) {
            pendingText = null
            loaded = true; dirty = false; lockIfReadonly(); updateStatus()
            return
        }
        val chunk = if (src.length <= CHUNK_CHARS) src else src.substring(0, CHUNK_CHARS)
        suppressDirty = true
        if (edit.length() == 0) edit.setText(chunk) else edit.append(chunk)
        suppressDirty = false
        pendingText = if (chunk.length >= src.length) null else src.substring(chunk.length)
        if (pendingText == null) {
            loaded = true; dirty = false; lockIfReadonly(); updateStatus()
        } else {
            mainHandler.postDelayed({ pumpChunks() }, CHUNK_DELAY_MS)
        }
    }

    /** 采样式编码检测：仅用前 8KB 判定 UTF-8/GBK，避免大文件全文双重解码。
     *  采样末尾可能截断多字节字符：依次容忍尾部 0-3 字节的不完整序列。 */
    private fun detectCharset(sample: ByteArray, len: Int): Charset {
        if (len >= 3 && sample[0] == 0xEF.toByte() && sample[1] == 0xBB.toByte() && sample[2] == 0xBF.toByte())
            return StandardCharsets.UTF_8 // BOM
        fun decodes(cs: Charset, l: Int): Boolean = runCatching {
            cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(sample, 0, l))
        }.isSuccess
        val utf8 = StandardCharsets.UTF_8
        for (trim in 0..3) {
            val l = len - trim
            if (l <= 0) break
            if (decodes(utf8, l)) return utf8
        }
        val gbk = runCatching { Charset.forName("GBK") }.getOrNull()
        if (gbk != null) {
            for (trim in 0..3) {
                val l = len - trim
                if (l <= 0) break
                if (decodes(gbk, l)) return gbk
            }
        }
        return StandardCharsets.ISO_8859_1
    }

    private fun lockIfReadonly() { if (readOnly) edit.isEnabled = false }

    private fun updateStatus() {
        val len = edit.length()
        val lines: Int
        val chars: Int
        if (len <= LIVE_STAT_LIMIT) {
            val text = edit.text ?: return
            lines = text.count { it == '\n' } + if (text.isNotEmpty()) 1 else 0
            chars = text.length
            cachedLines = lines; cachedChars = chars
        } else {
            lines = cachedLines; chars = len
        }
        status.text = getString(R.string.editor_status, lines, chars) + " · ${charset.name()}"
        titleView.text = if (dirty) "${file.name} ${getString(R.string.editor_modified)}" else file.name
    }

    // ---------- 保存 ----------

    /** 原子保存：先写临时文件再重命名，避免中途失败损坏原文件。后台线程执行，不阻塞 UI。 */
    private fun save() {
        if (readOnly) {
            Toast.makeText(this, R.string.editor_readonly, Toast.LENGTH_SHORT).show()
            return
        }
        if (!loaded) {
            Toast.makeText(this, R.string.editor_loading, Toast.LENGTH_SHORT).show()
            return
        }
        val text = edit.text.toString()
        Thread {
            val result = runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".polaris.tmp")
                tmp.writeText(text, charset)
                if (!tmp.renameTo(file)) throw java.io.IOException("rename failed")
            }
            runOnUiThread {
                if (result.isSuccess) {
                    dirty = false; updateStatus()
                    Toast.makeText(this, getString(R.string.editor_saved), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.editor_save_failed), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onBackPressed() {
        if (readingMode) { exitReadingMode(); return }
        if (!dirty) { super.onBackPressed(); return }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.editor_back_save))
            .setPositiveButton(getString(R.string.editor_save_btn)) { _, _ ->
                dirty = false
                save() // 后台原子写入，不阻塞返回
                super.onBackPressed()
            }
            .setNegativeButton(getString(R.string.editor_discard)) { _, _ -> dirty = false; super.onBackPressed() }
            .setNeutralButton(getString(R.string.btn_cancel), null)
            .show()
    }
}
