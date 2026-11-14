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

    // ---- 阅读器状态（设置持久化到 reader_prefs） ----
    private var readingMode = false
    private var readingInitDone = false
    private var rFontSize = 20f
    private var rBgIndex = FOLLOW_SYSTEM
    private var rLineIdx = 1 // 0 紧凑 1 标准 2 宽松
    private var rTypefaceIdx = 0 // 0 衬线 1 无衬线 2 等宽
    private var rPageMode = 0 // 0 滚动 1 翻页
    private var rAutoIdx = 0 // 0 关 1 慢 2 中 3 快
    private var rBrightness = 1.0f // 0.15..1.0
    private var controlsVisible = true

    // 阅读器视图
    private lateinit var readingRoot: FrameLayout
    private var readingScroll: ScrollView? = null
    private var readingText: TextView? = null
    private var flipBox: FrameLayout? = null
    private var overlay: View? = null
    private var topBar: LinearLayout? = null
    private var quickBar: LinearLayout? = null
    private var settingsPanel: LinearLayout? = null
    private var readingProgress: TextView? = null
    private var topProgress: TextView? = null

    // 翻页状态
    private val pageStarts = ArrayList<Int>()
    private var curPage = 0
    private var pageBoxW = 0
    private var pageBoxH = 0
    private var readingPumpPos = 0
    private var lastReadOffset = 0
    private var lastScrollY = 0
    private val readerPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG)

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
    private fun saveReaderPrefs() {
        prefs.edit()
            .putFloat("rf", rFontSize)
            .putInt("bg", rBgIndex)
            .putInt("ln", rLineIdx)
            .putInt("tf", rTypefaceIdx)
            .putInt("pm", rPageMode)
            .putInt("au", rAutoIdx)
            .putFloat("br", rBrightness)
            .apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        file = File(path)
        val night = com.limao.unzip.UiTheme.isNight()
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

    // ==================== 阅读器（番茄小说风格） ====================

    private fun toggleReadingMode() {
        if (isOffice) { Toast.makeText(this, R.string.reader_office_unsupported, Toast.LENGTH_SHORT).show(); return }
        if (fullText == null) { Toast.makeText(this, R.string.editor_loading, Toast.LENGTH_SHORT).show(); return }
        if (readingMode) exitReadingMode() else enterReadingMode()
    }

    private fun enterReadingMode() {
        readingMode = true
        stopAuto()
        if (!readingInitDone) {
            rBgIndex = FOLLOW_SYSTEM // 底色默认跟随系统日夜
            readingInitDone = true
        }
        bar.visibility = View.GONE
        status.visibility = View.GONE
        edit.visibility = View.GONE
        saveBtn.visibility = View.GONE
        buildReadingRoot()
        applyReaderTheme()
        if (rPageMode == 1) {
            setupFlipReader()
        } else {
            setupScrollReader()
        }
        updateReadingProgress()
        controlsVisible = true
        topBar?.visibility = View.VISIBLE
        quickBar?.visibility = View.VISIBLE
        settingsPanel?.visibility = View.GONE
    }

    /** 阅读根视图：内容层 + 亮度遮罩 + 顶栏/快捷栏 + 设置面板 */
    private fun buildReadingRoot() {
        readingRoot = FrameLayout(this).apply { fitsSystemWindows = true }
        // 滚动模式容器（翻页模式时隐藏）
        readingScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            setFillViewport(true)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(34), dp(26), dp(70))
        }
        readingText = TextView(this).apply {
            setPadding(0, 0, 0, dp(40))
        }
        content.addView(readingText)
        readingScroll!!.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        readingRoot.addView(readingScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 翻页模式容器
        flipBox = FrameLayout(this)
        readingRoot.addView(flipBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 亮度遮罩（仅创建，稍后按层级插入到内容之上、控制层之下）
        overlay = View(this).apply { setBackgroundColor(Color.BLACK); isClickable = false }

        // 顶栏
        topBar = LinearLayout(this).apply {
            setBackgroundColor(0x66000000)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar!!.addView(TextView(this).apply {
            text = "‹"; textSize = 24f; setTextColor(0xFFFFFFFF.toInt()); setPadding(dp(8), 0, dp(14), 0)
            setOnClickListener { exitReadingMode() }
        })
        topBar!!.addView(TextView(this).apply {
            text = file.name; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        topProgress = TextView(this).apply {
            textSize = 12f; setTextColor(0xEEFFFFFF.toInt()); setPadding(dp(8), 0, dp(4), 0)
        }
        topBar!!.addView(topProgress)

        // 快捷栏：编辑 / 自动阅读 / 设置
        quickBar = LinearLayout(this).apply {
            setBackgroundColor(0xF2151517.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            gravity = Gravity.CENTER
        }
        fun quick(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { onClick() }
        }
        quickBar!!.addView(quick("📖 " + getString(R.string.edit_mode)) { exitReadingMode() })
        quickBar!!.addView(quick(autoLabel()) { toggleAuto() })
        quickBar!!.addView(quick("⚙ 设置") { toggleSettings() })

        // 点击/手势：中央切换控制层；翻页模式下左右区域翻页
        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // 设置面板打开时：点击面板外区域只关闭面板（保留控制层）
                if (settingsPanel?.visibility == View.VISIBLE) {
                    settingsPanel?.visibility = View.GONE
                    return true
                }
                val w = readingRoot.width
                if (rPageMode == 1) {
                    when {
                        e.x < w / 3f -> turnPage(-1)
                        e.x > w * 2f / 3f -> turnPage(1)
                        else -> toggleControls()
                    }
                } else {
                    toggleControls()
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (rPageMode == 1 && kotlin.math.abs(vx) > kotlin.math.abs(vy) && kotlin.math.abs(vx) > 600) {
                    if (vx < 0) turnPage(1) else turnPage(-1)
                    return true
                }
                return false
            }
        })
        val touchInterceptor = View(this).apply {
            setOnTouchListener { v, e ->
                gesture.onTouchEvent(e)
                // 滚动模式把触摸交给 ScrollView 自身滚动
                if (rPageMode == 0) readingScroll!!.dispatchTouchEvent(e)
                true
            }
        }
        // 层级：内容(0,1) → 触摸拦截(2) → 亮度遮罩(3) → 顶栏(4)/快捷栏(5)/设置面板(6)
        readingRoot.addView(touchInterceptor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        readingRoot.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        readingRoot.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        readingRoot.addView(quickBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // 设置面板（上滑显示，置于最上层）
        settingsPanel = buildSettingsPanel()
        settingsPanel!!.visibility = View.GONE
        // 关键：面板/栏自身可点击——空隙区域的触摸不会穿透到内容层（否则点一下全收起）
        settingsPanel!!.isClickable = true
        topBar!!.isClickable = true
        quickBar!!.isClickable = true
        readingRoot.addView(settingsPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(readingRoot)
        // 翻页模式下滚动容器不参与（隐藏）
        readingScroll!!.visibility = if (rPageMode == 1) View.GONE else View.VISIBLE
        flipBox!!.visibility = if (rPageMode == 1) View.VISIBLE else View.GONE
    }

    private fun applyReaderTheme() {
        // rBgIndex == FOLLOW_SYSTEM(-1)：底色跟随系统日夜模式
        val effectiveBg = if (rBgIndex == FOLLOW_SYSTEM) (if (com.limao.unzip.UiTheme.isNight()) 4 else 0) else rBgIndex
        val bg = BG_COLORS[effectiveBg]
        val fg = BG_TEXT_COLORS[effectiveBg]
        readingRoot.setBackgroundColor(bg)
        readingScroll?.setBackgroundColor(bg)
        readingText?.apply {
            setTextColor(fg)
            textSize = rFontSize
            setTypeface(TYPEFACES[rTypefaceIdx])
            setLineSpacing(dp(3).toFloat(), LINE_MULTS[rLineIdx])
        }
        readerPaint.textSize = rFontSize * resources.displayMetrics.scaledDensity
        readerPaint.typeface = TYPEFACES[rTypefaceIdx]
        flipBox?.setBackgroundColor(bg)
        overlay?.setAlpha((1f - rBrightness) * 0.72f)
        topProgress?.setTextColor(0xEEFFFFFF.toInt())
    }

    // ---- 滚动模式 ----

    private fun setupScrollReader() {
        readingScroll?.visibility = View.VISIBLE
        flipBox?.visibility = View.GONE
        val ft = fullText ?: return
        val tv = readingText ?: return
        readingPumpPos = 0
        mainHandler.removeCallbacks(readingPumpRunnable)
        // 首屏同步显示
        val head = ft.substring(0, minOf(CHUNK_CHARS, ft.length))
        tv.text = head
        readingPumpPos = head.length
        if (readingPumpPos < ft.length) mainHandler.postDelayed(readingPumpRunnable, CHUNK_DELAY_MS)
        // 恢复上次位置
        if (lastScrollY > 0) readingScroll?.post { readingScroll?.scrollTo(0, lastScrollY) }
        readingScroll?.setOnScrollChangeListener { _, _, sy, _, _ ->
            lastScrollY = sy
            updateReadingProgress()
        }
        updateReadingProgress()
    }

    private val readingPumpRunnable = Runnable { pumpReading() }

    private fun pumpReading() {
        val ft = fullText ?: return
        val tv = readingText ?: return
        val end = minOf(readingPumpPos + CHUNK_CHARS, ft.length)
        tv.append(ft.substring(readingPumpPos, end))
        readingPumpPos = end
        if (end < ft.length) {
            mainHandler.postDelayed(readingPumpRunnable, CHUNK_DELAY_MS)
        } else if (repumpRestoreFrac >= 0) {
            // 重灌完成：按比例恢复滚动位置
            val frac = repumpRestoreFrac
            repumpRestoreFrac = -1f
            readingScroll?.post {
                val maxScroll = maxOf(1, (readingScroll?.getChildAt(0)?.height ?: 1) - (readingScroll?.height ?: 1))
                readingScroll?.scrollTo(0, (maxScroll * frac).toInt())
            }
        }
    }

    // ---- 翻页模式 ----

    private fun setupFlipReader() {
        readingScroll?.visibility = View.GONE
        flipBox?.visibility = View.VISIBLE
        readingRoot.post {
            val padL = dp(26); val padR = dp(26)
            val padT = dp(34); val padB = dp(34)
            pageBoxW = readingRoot.width - padL - padR
            pageBoxH = readingRoot.height - padT - padB
            if (pageBoxW <= 0 || pageBoxH <= 0) return@post
            pageStarts.clear()
            pageStarts.add(0)
            // 恢复上次阅读位置（字符偏移）
            jumpToChar(if (lastReadOffset in 1..(fullText?.length ?: 0)) lastReadOffset - 1 else 0)
        }
    }

    /**
     * 设置变更入口：applyReaderTheme 即时生效（视觉零延迟）；
     * 需要重排的项（字号/行距/字体）走防抖调度：
     *   翻页模式 → 锚点单页重分页（一次 computePageEnd）
     *   滚动模式 → 小文本直接重排；大文本清空分块重灌（不阻塞主线程）
     */
    private val relayoutFlipRunnable = Runnable { doRelayoutFlip() }
    private var repumpRestoreFrac = -1f

    private fun onReaderSettingChanged(needsRelayout: Boolean) {
        applyReaderTheme()
        saveReaderPrefs()
        if (!needsRelayout) return
        if (rPageMode == 1) {
            mainHandler.removeCallbacks(relayoutFlipRunnable)
            mainHandler.postDelayed(relayoutFlipRunnable, 120)
        } else {
            val len = fullText?.length ?: 0
            if (len > 150_000) repumpScroll() // 大文本分块重灌，避免整段重排卡顿
        }
    }

    private fun doRelayoutFlip() {
        val ft = fullText ?: return
        val anchor = (if (curPage < pageStarts.size) pageStarts[curPage] else lastReadOffset)
            .coerceIn(0, (ft.length - 1).coerceAtLeast(0))
        pageStarts.clear()
        pageStarts.add(anchor)
        curPage = 0
        flipBox?.removeAllViews()
        renderFlipPage(anchor)
        updateReadingProgress()
    }

    private fun effectiveBgIndex(): Int =
        if (rBgIndex == FOLLOW_SYSTEM) (if (com.limao.unzip.UiTheme.isNight()) 4 else 0) else rBgIndex

    /** 渲染指定起点的一页（页尾现算一次并缓存为下一页起点） */
    private fun renderFlipPage(start: Int) {
        val ft = fullText ?: return
        val end = if (pageStarts.size >= 2 && pageStarts[1] > start) pageStarts[1]
        else computePageEnd(start).also { e ->
            if (e > start && pageStarts.size == 1 && e < ft.length) pageStarts.add(e)
        }
        val box = flipBox ?: return
        box.removeAllViews()
        val page = TextView(this).apply {
            text = ft.subSequence(start, end)
            setTextColor(BG_TEXT_COLORS[effectiveBgIndex()])
            textSize = rFontSize
            setTypeface(TYPEFACES[rTypefaceIdx])
            setLineSpacing(dp(3).toFloat(), LINE_MULTS[rLineIdx])
            setPadding(dp(26), dp(34), dp(26), dp(34))
            includeFontPadding = false
        }
        box.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** 滚动模式大文本：清空 + 分块重灌（每帧增量布局不卡顿），完成后按比例恢复滚动位置 */
    private fun repumpScroll() {
        val oldMax = maxOf(1, (readingScroll?.getChildAt(0)?.height ?: 1) - (readingScroll?.height ?: 1))
        repumpRestoreFrac = (readingScroll?.scrollY ?: 0).toFloat() / oldMax
        readingPumpPos = 0
        mainHandler.removeCallbacks(readingPumpRunnable)
        readingText?.text = ""
        mainHandler.postDelayed(readingPumpRunnable, CHUNK_DELAY_MS)
    }

    /** 二分搜索：从 start 起最多能容纳多少字符（StaticLayout 高度 ≤ 页盒） */
    private fun computePageEnd(start: Int): Int {
        val ft = fullText ?: return start
        val total = ft.length
        if (start >= total) return start
        var lo = start + 1
        var hi = total
        var best = start + 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val seq = ft.subSequence(start, mid)
            val layout = android.text.StaticLayout.Builder
                .obtain(seq, 0, seq.length, readerPaint, pageBoxW)
                .setLineSpacing(dp(3).toFloat(), LINE_MULTS[rLineIdx])
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
            if (layout.height <= pageBoxH) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }

    /** 窗口均每页字符数（用于进度估算与跳转外推） */
    private fun avgPageChars(): Int {
        val ft = fullText ?: return 2000
        if (pageStarts.size >= 2) {
            val span = pageStarts.last() - pageStarts.first()
            if (span > 0) return (span / (pageStarts.size - 1)).coerceAtLeast(200)
        }
        return 2000
    }

    /** 翻页：dir +1 下一页 / -1 上一页；返回是否成功 */
    private fun turnPage(dir: Int): Boolean {
        val ft = fullText ?: return false
        if (dir > 0) {
            val nextStart: Int
            if (curPage + 1 < pageStarts.size) {
                nextStart = pageStarts[curPage + 1]
            } else {
                val curStart = pageStarts[curPage]
                val end = computePageEnd(curStart)
                if (end >= ft.length) return false // 已是最后一页
                pageStarts.add(end)
                nextStart = end
            }
            animateTo(curPage + 1, nextStart, +1)
            return true
        } else {
            if (curPage > 0) {
                animateTo(curPage - 1, pageStarts[curPage - 1], -1)
                return true
            }
            // 窗口开头：向前补一页（在 pageStarts[0] 之前找页首）
            val windowStart = pageStarts.firstOrNull() ?: 0
            if (windowStart <= 0) return false
            val est = avgPageChars()
            var lo = (windowStart - est * 3).coerceAtLeast(0)
            var hi = windowStart - 1
            var best = lo
            // 最大 s 使 end(s) ≤ windowStart（该页恰好结束于窗口首页之前）
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (computePageEnd(mid) <= windowStart) { best = mid; lo = mid + 1 } else hi = mid - 1
            }
            if (best >= windowStart) return false
            pageStarts.add(0, best)
            curPage = 0
            animateTo(0, best, -1)
            return true
        }
    }

    private fun animateTo(pageIdx: Int, start: Int, dir: Int) {
        val ft = fullText ?: return
        curPage = pageIdx
        lastReadOffset = start
        // 页尾：缓存中存在则用缓存；否则现算（并缓存为下一页起点）
        val end: Int = if (pageIdx + 1 < pageStarts.size) pageStarts[pageIdx + 1]
        else computePageEnd(start).also { e ->
            if (e > start && pageStarts.size == pageIdx + 1 && e < ft.length) pageStarts.add(e)
        }
        val seq = ft.subSequence(start, end)
        val page = TextView(this).apply {
            text = seq
            setTextColor(BG_TEXT_COLORS[rBgIndex])
            textSize = rFontSize
            setTypeface(TYPEFACES[rTypefaceIdx])
            setLineSpacing(dp(3).toFloat(), LINE_MULTS[rLineIdx])
            setPadding(dp(26), dp(34), dp(26), dp(34))
            includeFontPadding = false
        }
        val box = flipBox ?: return
        box.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val out = if (box.childCount > 1) box.getChildAt(0) else null
        val off = box.width.toFloat()
        page.translationX = off * dir
        page.animate().translationX(0f).setDuration(180).start()
        out?.animate()?.translationX(-off * dir)?.setDuration(180)?.withEndAction {
            box.removeView(out)
        }?.start()
        updateReadingProgress()
    }

    /** 跳转到指定字符偏移所在页：以估计起点开窗，向后扩展数页即覆盖目标（不枚举全文档） */
    private fun jumpToChar(offset: Int) {
        val ft = fullText ?: return
        val target = offset.coerceIn(0, (ft.length - 1).coerceAtLeast(0))
        val est = avgPageChars()
        // 开窗：从 target 前约两页处开始，向前扩展最多几页即可覆盖 target
        var s = (target - est * 2).coerceAtLeast(0)
        if (s > 0) {
            // 外推点可能落在页中间，向前回退直到成为合法页首（end(s) 会随 s 单调右移）
            var guard = 0
            while (guard++ < 8 && s > 0 && computePageEnd(s) <= s) s = (s - est).coerceAtLeast(0)
        }
        pageStarts.clear()
        pageStarts.add(s)
        curPage = 0
        var guard = 0
        while (guard++ < 60) {
            val e = computePageEnd(pageStarts.last())
            if (e > target || e >= ft.length) break
            if (e <= pageStarts.last()) break
            pageStarts.add(e)
        }
        // 定位 target 所在页
        var idx = pageStarts.size - 1
        for (i in pageStarts.indices) {
            val next = if (i + 1 < pageStarts.size) pageStarts[i + 1] else ft.length
            if (target in pageStarts[i] until next) { idx = i; break }
        }
        curPage = idx
        lastReadOffset = target
        val start = pageStarts[idx]
        val end = if (idx + 1 < pageStarts.size) pageStarts[idx + 1] else ft.length
        val box = flipBox ?: return
        box.removeAllViews()
        val page = TextView(this).apply {
            text = ft.subSequence(start, end)
            setTextColor(BG_TEXT_COLORS[rBgIndex])
            textSize = rFontSize
            setTypeface(TYPEFACES[rTypefaceIdx])
            setLineSpacing(dp(3).toFloat(), LINE_MULTS[rLineIdx])
            setPadding(dp(26), dp(34), dp(26), dp(34))
            includeFontPadding = false
        }
        box.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateReadingProgress()
    }

    // ---- 自动阅读 ----

    private val autoRunnable = object : Runnable {
        override fun run() {
            if (!readingMode || rAutoIdx == 0) return
            if (rPageMode == 1) {
                if (!turnPage(1)) { stopAuto(); refreshQuickBar(); return }
                mainHandler.postDelayed(this, AUTO_FLIP_S[rAutoIdx] * 1000L)
            } else {
                readingScroll?.smoothScrollBy(0, AUTO_SCROLL_PX[rAutoIdx] / 6)
                mainHandler.postDelayed(this, 16)
            }
        }
    }

    private fun toggleAuto() {
        if (rAutoIdx == 0) rAutoIdx = 2 else { rAutoIdx = 0; stopAuto() }
        saveReaderPrefs()
        refreshQuickBar()
        if (rAutoIdx > 0) mainHandler.postDelayed(autoRunnable, 500)
        Toast.makeText(this, if (rAutoIdx == 0) "自动阅读已停止" else "自动阅读：${AUTO_NAMES[rAutoIdx]}", Toast.LENGTH_SHORT).show()
    }

    private fun stopAuto() { mainHandler.removeCallbacks(autoRunnable) }

    private fun autoLabel(): String =
        if (rAutoIdx == 0) "▶ 自动阅读" else "⏸ 自动阅读·${AUTO_NAMES[rAutoIdx]}"

    private fun refreshQuickBar() {
        quickBar?.let { qb ->
            if (qb.childCount >= 2) (qb.getChildAt(1) as TextView).text = autoLabel()
        }
    }

    // ---- 控制层显隐 / 进度 ----

    private fun toggleControls() {
        controlsVisible = !controlsVisible
        topBar?.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        quickBar?.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        if (!controlsVisible) settingsPanel?.visibility = View.GONE
    }

    private fun toggleSettings() {
        val p = settingsPanel ?: return
        p.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun updateReadingProgress() {
        val ft = fullText ?: return
        val total = ft.length
        val pct: Int
        if (rPageMode == 1) {
            val start = if (curPage < pageStarts.size) pageStarts[curPage] else 0
            pct = (start * 100 / total.coerceAtLeast(1)).coerceIn(0, 100)
            val avg = if (pageStarts.size > 1) pageStarts.last() / pageStarts.size else 2000
            val est = (total + avg - 1) / avg.coerceAtLeast(1)
            readingProgress?.text = "${curPage + 1}/$est · $pct%"
            topProgress?.text = "$pct%"
        } else {
            val sv = readingScroll?.scrollY ?: 0
            val maxScroll = maxOf(1, (readingScroll?.getChildAt(0)?.height ?: 1) - (readingScroll?.height ?: 1))
            pct = (sv * 100 / maxScroll).coerceIn(0, 100)
            readingProgress?.text = "$pct% · ${cachedChars} 字"
            topProgress?.text = "$pct%"
        }
    }

    // ---- 设置面板 ----

    private fun buildSettingsPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2151517.toInt())
            setPadding(dp(18), dp(14), dp(18), dp(18))
            // 面板自身消费点击（空隙区域不穿透到内容层）
            isClickable = true
        }
        val chipRefreshers = ArrayList<() -> Unit>()

        fun label(labelStr: String) = TextView(this).apply {
            text = labelStr; textSize = 12f; setTextColor(0xFF999999.toInt())
            setPadding(0, dp(10), 0, dp(4))
        }

        /** 芯片组：点击就地刷新选中态（不重建面板，零延迟） */
        fun chipRow(options: List<String>, current: () -> Int, onPick: (Int) -> Unit): LinearLayout {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val chips = ArrayList<TextView>()
            fun refresh() {
                chips.forEachIndexed { i, c ->
                    val sel = i == current()
                    c.setTextColor(if (sel) 0xFF64B5FF.toInt() else 0xFFDDDDDD.toInt())
                    c.setBackgroundResource(if (sel) R.drawable.chip_selected else R.drawable.chip_normal)
                }
            }
            options.forEachIndexed { i, opt ->
                chips.add(TextView(this).apply {
                    text = opt; textSize = 13f
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                    setBackgroundResource(if (i == current()) R.drawable.chip_selected else R.drawable.chip_normal)
                    setTextColor(if (i == current()) 0xFF64B5FF.toInt() else 0xFFDDDDDD.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        onPick(i)
                        refresh()
                        chipRefreshers.forEach { it() } // 联动刷新（如跟随/固定背景互斥）
                    }
                })
            }
            refresh()
            chipRefreshers.add { refresh() }
            return row
        }

        panel.addView(label("亮度（护眼遮罩）"))
        val bright = SeekBar(this).apply {
            max = 100
            progress = ((rBrightness - 0.15f) / 0.85f * 100).toInt()
            setPadding(0, dp(6), 0, dp(6))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    rBrightness = 0.15f + value / 100f * 0.85f
                    this@TextEditorActivity.overlay?.setAlpha((1f - rBrightness) * 0.72f)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { saveReaderPrefs() }
            })
        }
        panel.addView(bright)

        panel.addView(label("字体"))
        panel.addView(chipRow(TF_NAMES.toList(), { rTypefaceIdx }) { rTypefaceIdx = it; onReaderSettingChanged(needsRelayout = true) })

        panel.addView(label("字号"))
        val fontRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val fontSizeLabel = TextView(this).apply {
            text = "${rFontSize.toInt()}"; textSize = 15f; setTextColor(0xFF64B5FF.toInt()); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fontRow.addView(TextView(this).apply {
            text = "A−"; textSize = 16f; setTextColor(0xFFDDDDDD.toInt()); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { rFontSize = (rFontSize - 2f).coerceAtLeast(12f); fontSizeLabel.text = "${rFontSize.toInt()}"; onReaderSettingChanged(true) }
        })
        fontRow.addView(fontSizeLabel)
        fontRow.addView(TextView(this).apply {
            text = "A＋"; textSize = 20f; setTextColor(0xFFDDDDDD.toInt()); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { rFontSize = (rFontSize + 2f).coerceAtMost(50f); fontSizeLabel.text = "${rFontSize.toInt()}"; onReaderSettingChanged(true) }
        })
        panel.addView(fontRow)

        panel.addView(label("行距"))
        panel.addView(chipRow(LINE_NAMES.toList(), { rLineIdx }) { rLineIdx = it; onReaderSettingChanged(true) })

        panel.addView(label("背景（跟随 = 随系统日夜自动切换）"))
        val bgRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bgChips = ArrayList<Pair<TextView, Int>>() // chip + 对应 rBgIndex
        fun refreshBg() {
            bgChips.forEach { (c, idx) ->
                val sel = idx == rBgIndex
                if (idx == FOLLOW_SYSTEM) {
                    c.setTextColor(if (sel) 0xFF0D2B52.toInt() else 0xFF1D1B20.toInt())
                    c.setBackgroundColor(if (sel) 0xFF64B5FF.toInt() else 0xFF888888.toInt())
                } else {
                    c.setTextColor(if (sel) 0xFF64B5FF.toInt() else 0xFF1D1B20.toInt())
                    c.setBackgroundColor(BG_COLORS[idx])
                }
            }
        }
        fun bgChip(label: String, idx: Int) {
            val c = TextView(this).apply {
                text = label; textSize = 13f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }
                gravity = Gravity.CENTER
                setOnClickListener {
                    rBgIndex = idx
                    onReaderSettingChanged(false) // 颜色不影响排版，即时生效
                    refreshBg()
                }
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
        val pmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val pmChips = ArrayList<TextView>()
        fun refreshPm() {
            pmChips.forEachIndexed { i, c ->
                c.setTextColor(if (i == rPageMode) 0xFF64B5FF.toInt() else 0xFFDDDDDD.toInt())
            }
        }
        listOf("上下滚动", "左右翻页").forEachIndexed { i, name ->
            pmChips.add(TextView(this).apply {
                text = name; textSize = 13f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }
                gravity = Gravity.CENTER
                setOnClickListener {
                    lastReadOffset = currentReadingOffset()
                    rPageMode = i
                    saveReaderPrefs()
                    refreshPm()
                    readingScroll?.visibility = if (i == 0) View.VISIBLE else View.GONE
                    flipBox?.visibility = if (i == 1) View.VISIBLE else View.GONE
                    if (i == 1) setupFlipReader() else setupScrollReader()
                    updateReadingProgress()
                }
            })
            refreshPm()
        }
        panel.addView(pmRow)

        panel.addView(label("自动阅读（翻页：慢20s/中10s/快5s · 滚动：匀速）"))
        panel.addView(chipRow(AUTO_NAMES.toList(), { rAutoIdx }) { i ->
            rAutoIdx = i
            saveReaderPrefs()
            refreshQuickBar()
            stopAuto()
            if (i > 0) mainHandler.postDelayed(autoRunnable, 400)
        })

        panel.addView(label("进度跳转（拖动选择，松手跳转）"))
        val jump = SeekBar(this).apply { max = 100; progress = 0 }
        jump.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                // 拖动中只更新提示文本（实时跳页在大文档上开销大）
                readingProgress?.text = "跳转到 $value%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val value = sb?.progress ?: return
                val ft = fullText ?: return
                val target = ((ft.length - 1).coerceAtLeast(0)) * value / 100
                lastReadOffset = target
                if (rPageMode == 1) {
                    flipBox?.removeAllViews()
                    jumpToChar(target)
                } else {
                    val maxScroll = maxOf(1, (readingScroll?.getChildAt(0)?.height ?: 1) - (readingScroll?.height ?: 1))
                    readingScroll?.smoothScrollTo(0, maxScroll * value / 100)
                }
                updateReadingProgress()
            }
        })
        panel.addView(jump)
        readingProgress = TextView(this).apply {
            textSize = 12f; setTextColor(0xFF999999.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        panel.addView(readingProgress)
        return panel
    }

    private fun currentReadingOffset(): Int {
        return if (rPageMode == 1) {
            if (curPage < pageStarts.size) pageStarts[curPage] else lastReadOffset
        } else {
            val sv = readingScroll?.scrollY ?: 0
            val maxScroll = maxOf(1, (readingScroll?.getChildAt(0)?.height ?: 1) - (readingScroll?.height ?: 1))
            val ft = fullText ?: return 0
            (sv.toLong() * ft.length / maxScroll).toInt().coerceIn(0, ft.length - 1)
        }
    }

    private fun exitReadingMode() {
        readingMode = false
        stopAuto()
        lastReadOffset = currentReadingOffset()
        mainHandler.removeCallbacks(readingPumpRunnable)
        setContentView(editContainer)
        saveBtn.visibility = View.VISIBLE
        bar.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        edit.visibility = View.VISIBLE
        readingScroll = null
        readingText = null
        flipBox = null
        overlay = null
        topBar = null
        quickBar = null
        settingsPanel = null
        readingProgress = null
        topProgress = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
