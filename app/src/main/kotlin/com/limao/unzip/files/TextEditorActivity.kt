package com.limao.unzip.files

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
 *  - 编辑模式：UTF-8/GBK 自适应，所有大小可编辑（去掉 5MB 限制），原子保存
 *  - 阅读模式：番茄小说风格——大字体、可选背景（白/米/绿/黑）、字号调节、进度显示、
 *    点击中央切换控制层显隐、流利滚动；与编辑模式一键切换
 *  - 打开速度：采样式编码检测（仅前 8KB）+ 单次流式读取，大文件秒开
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

    // ---- 阅读模式 ----
    private var readingMode = false
    private var readingFontSize = 20f
    private var readingBgIndex = 0 // 0=白 1=米 2=绿 3=黑
    private lateinit var readingPanel: LinearLayout
    private lateinit var readingScroll: ScrollView
    private lateinit var readingText: TextView
    private lateinit var readingProgress: TextView
    private var controlsVisible = true
    private var readingInitDone = false
    private var pendingReading: String? = null

    companion object {
        const val EXTRA_PATH = "path"
        private const val CHUNK_CHARS = 65_536
        private const val CHUNK_DELAY_MS = 24L
        private const val LIVE_STAT_LIMIT = 500_000
        private val BG_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(), // 白
            0xFFF5EFDC.toInt(), // 米
            0xFFC7EDCC.toInt(), // 护眼绿
            0xFF1E1E1E.toInt(), // 黑
        )
        private val BG_TEXT_COLORS = intArrayOf(
            0xFF1D1B20.toInt(),
            0xFF3B2F20.toInt(),
            0xFF2D3B2D.toInt(),
            0xFFCCCCCC.toInt(),
        )
        private val BG_NAMES = arrayOf("白色", "米色", "护眼", "夜间")
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
            setPadding(24, 32, 24, 32)
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(this).apply {
            text = "‹"; textSize = 26f; setTextColor(fgSecondary); setPadding(0, 0, 28, 0)
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        })
        titleView = TextView(this).apply {
            text = file.name; textSize = 16f; setTextColor(fgPrimary)
            setTypeface(Typeface.MONOSPACE)
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

        if (!file.exists()) { loaded = true; updateStatus(); return }

        // Office（docx/xlsx/pptx）：提取文本只读预览
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

        // 全部大小可编辑（去掉 5MB 限制）；>50MB 无法打开
        if (file.length() > 50L * 1024 * 1024) {
            Toast.makeText(this, getString(R.string.editor_too_large), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        readOnly = false
        val startTime = System.currentTimeMillis()
        Thread {
            // 采样式编码检测（仅前 8KB）——大文件不做全文解码试探
            val sample = ByteArray(8192)
            val sampleLen = runCatching {
                FileInputStream(file).use { it.read(sample) }
            }.getOrDefault(0)
            charset = detectCharset(sample, sampleLen)
            // 超长单行检测（压缩 JSON/日志等）：首段样本无换行且文件 > 2MB 时，
            // EditText 排版是 O(n²)，降级为只读预览前 1MB
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
                    pendingText = pv.toString()
                    mainHandler.postDelayed({ pumpChunks() }, CHUNK_DELAY_MS)
                }
                return@Thread
            }
            // 单次流式读取（BufferedReader 按行高效读取）
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
            // 后台预统计（大文件避免每次按键全量统计）
            cachedChars = text.length
            cachedLines = text.count { it == '\n' } + if (text.isNotEmpty()) 1 else 0
            sb.setLength(0) // 提前释放中间 buffer
            val elapsed = System.currentTimeMillis() - startTime
            android.util.Log.d("TextEditor", "loaded ${file.length()} bytes in ${elapsed}ms charset=${charset.name()}")
            // 大文本一次性 setText 会因整段排版阻塞主线程（10MB > 5s → ANR）。
            // 分块渐进 append + 块间留出主线程空闲间隙（OPPO watchdog 检测连续占线）。
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

    /** 采样式编码检测：仅用前 8KB 判定 UTF-8/GBK，避免大文件全文双重解码。 */
    private fun detectCharset(sample: ByteArray, len: Int): Charset {
        if (len >= 3 && sample[0] == 0xEF.toByte() && sample[1] == 0xBB.toByte() && sample[2] == 0xBF.toByte())
            return StandardCharsets.UTF_8 // BOM
        // 严格 UTF-8 试解码样本
        val utf8 = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        runCatching { utf8.decode(ByteBuffer.wrap(sample, 0, len)); return StandardCharsets.UTF_8 }
        // GBK 试解码样本
        val gbk = runCatching { Charset.forName("GBK") }.getOrNull()
        if (gbk != null) {
            val strictGbk = gbk.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            runCatching { strictGbk.decode(ByteBuffer.wrap(sample, 0, len)); return gbk }
        }
        return StandardCharsets.ISO_8859_1
    }

    private fun lockIfReadonly() { if (readOnly) edit.isEnabled = false }

    private fun updateStatus() {
        val len = edit.length()
        // 小文件实时统计；大文件用加载时缓存的统计（避免每次按键全量扫描）
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

    // ---------- 阅读模式（番茄小说风格） ----------

    private fun toggleReadingMode() {
        if (isOffice) { Toast.makeText(this, R.string.reader_office_unsupported, Toast.LENGTH_SHORT).show(); return }
        if (!loaded) { Toast.makeText(this, R.string.editor_loading, Toast.LENGTH_SHORT).show(); return }
        readingMode = !readingMode
        if (readingMode) enterReadingMode() else exitReadingMode()
    }

    private fun enterReadingMode() {
        // 仅首次进入时按主题取默认背景/字号；切换背景时保留用户设置
        if (!readingInitDone) {
            readingBgIndex = if (com.limao.unzip.UiTheme.isNight()) 3 else 0
            readingFontSize = 20f
            readingInitDone = true
        }
        // 隐藏编辑 UI
        bar.visibility = View.GONE
        status.visibility = View.GONE
        edit.visibility = View.GONE

        // 阅读容器
        val bgColor = BG_COLORS[readingBgIndex]
        val fgColor = BG_TEXT_COLORS[readingBgIndex]

        readingScroll = ScrollView(this).apply {
            setBackgroundColor(bgColor)
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(40), dp(28), dp(80))
        }
        readingText = TextView(this).apply {
            textSize = readingFontSize
            setTextColor(fgColor)
            setLineSpacing(dp(8).toFloat(), 1.3f)
            setTypeface(Typeface.SERIF)
        }
        content.addView(readingText)
        // 大文本分块灌入，避免整段排版阻塞主线程
        val full = edit.text?.toString() ?: ""
        pendingReading = null
        val head = if (full.length <= CHUNK_CHARS) full else full.substring(0, CHUNK_CHARS)
        if (head.isNotEmpty()) readingText.text = head
        if (head.length < full.length) {
            pendingReading = full.substring(head.length)
            mainHandler.postDelayed({ pumpReadingChunks() }, CHUNK_DELAY_MS)
        }
        readingScroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 控制面板（底部固定）
        readingPanel = buildReadingPanel(bgColor, fgColor)

        val root = FrameLayout(this).apply { setBackgroundColor(bgColor) }
        root.addView(readingScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(readingPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        // 点击中央切换控制层；滚动实时更新进度
        readingScroll.setOnClickListener { controlsVisible = !controlsVisible; readingPanel.visibility = if (controlsVisible) View.VISIBLE else View.GONE }
        readingScroll.setOnScrollChangeListener { _, _, _, _, _ -> updateReadingProgress() }
        updateReadingProgress()
        saveBtn.visibility = View.GONE
    }

    private fun buildReadingPanel(bgColor: Int, fgColor: Int): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        // 进度
        readingProgress = TextView(this).apply {
            textSize = 12f; setTextColor(fgColor); gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        }
        panel.addView(readingProgress)
        // 字号控制行
        val fontRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun panelBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 14f; setTextColor(fgColor); gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { onClick() }
        }
        fontRow.addView(panelBtn("A-") {
            readingFontSize = (readingFontSize - 2f).coerceAtLeast(14f)
            readingText.textSize = readingFontSize
        })
        fontRow.addView(panelBtn("A+") {
            readingFontSize = (readingFontSize + 2f).coerceAtMost(40f)
            readingText.textSize = readingFontSize
        })
        // 背景切换
        BG_NAMES.forEachIndexed { i, name ->
            fontRow.addView(panelBtn(name) { readingBgIndex = i; enterReadingMode() })
        }
        panel.addView(fontRow)
        // 操作行
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        actionRow.addView(panelBtn("📖 " + getString(R.string.edit_mode)) { toggleReadingMode() })
        panel.addView(actionRow)
        return panel
    }

    private fun pumpReadingChunks() {
        val src = pendingReading ?: return
        if (src.isEmpty()) { pendingReading = null; return }
        val chunk = if (src.length <= CHUNK_CHARS) src else src.substring(0, CHUNK_CHARS)
        if (readingText.length() == 0) readingText.text = chunk else readingText.append(chunk)
        pendingReading = if (chunk.length >= src.length) null else src.substring(chunk.length)
        if (pendingReading != null) mainHandler.postDelayed({ pumpReadingChunks() }, CHUNK_DELAY_MS)
    }

    private fun updateReadingProgress() {
        if (::readingProgress.isInitialized) {
            val total = edit.text?.length ?: 0
            val scrollY = readingScroll.scrollY
            // 可滚动距离 = 内容高度 - 视口高度
            val maxScroll = maxOf(1, readingScroll.getChildAt(0).height - readingScroll.height)
            val pct = ((scrollY.toFloat() / maxScroll) * 100).toInt().coerceIn(0, 100)
            readingProgress.text = "$pct% · $total"
        }
    }

    private fun exitReadingMode() {
        pendingReading = null // 取消未完成的阅读灌入
        setContentView(editContainer)
        saveBtn.visibility = View.VISIBLE
        bar.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        edit.visibility = View.VISIBLE
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
        if (readingMode) { toggleReadingMode(); return }
        if (!dirty) { super.onBackPressed(); return }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.editor_back_save))
            .setPositiveButton(getString(R.string.editor_save_btn)) { _, _ ->
                dirty = false
                save() // 后台原子写入，线程随写随存，不阻塞返回
                super.onBackPressed()
            }
            .setNegativeButton(getString(R.string.editor_discard)) { _, _ -> dirty = false; super.onBackPressed() }
            .setNeutralButton(getString(R.string.btn_cancel), null)
            .show()
    }
}
