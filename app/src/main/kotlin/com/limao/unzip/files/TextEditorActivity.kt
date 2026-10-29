package com.limao.unzip.files

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.limao.unzip.R
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 万能阅读器（MT 风格深色主题）：文本查看/编辑/保存。
 * 编码自适应：UTF-8 严格检测 → 失败回退 GBK → ISO-8859-1 兜底；保存按原编码回写。
 * ≤5MB 可编辑；更大文件只读提示。
 */
class TextEditorActivity : AppCompatActivity() {

    private lateinit var file: File
    private lateinit var edit: EditText
    private lateinit var status: TextView
    private lateinit var titleView: TextView
    private var dirty = false
    private var loaded = false
    private var charset: Charset = StandardCharsets.UTF_8
    private var readOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        file = File(path)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1E1E1E.toInt())
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            setBackgroundColor(0xFF2B2B2B.toInt())
            setPadding(24, 32, 24, 32)
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(this).apply {
            text = "‹"; textSize = 26f; setTextColor(0xFFDDDDDD.toInt()); setPadding(0, 0, 28, 0)
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        })
        titleView = TextView(this).apply {
            text = file.name; textSize = 16f; setTextColor(0xFFEEEEEE.toInt())
            setTypeface(android.graphics.Typeface.MONOSPACE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(titleView)
        bar.addView(TextView(this).apply {
            text = getString(R.string.editor_save); textSize = 15f; setTextColor(0xFF82AAFF.toInt()); setPadding(24, 0, 0, 0)
            setOnClickListener { save() }
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        edit = EditText(this).apply {
            setBackgroundColor(0xFF1E1E1E.toInt())
            setTextColor(0xFFDDDDDD.toInt())
            setHintTextColor(0xFF666666.toInt())
            textSize = 14f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            gravity = Gravity.TOP
            setPadding(20, 16, 20, 16)
            isVerticalScrollBarEnabled = true
        }
        root.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        status = TextView(this).apply {
            textSize = 12f; setTextColor(0xFF888888.toInt())
            setBackgroundColor(0xFF2B2B2B.toInt())
            setPadding(20, 12, 20, 12)
        }
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (loaded) {
                    dirty = true
                    updateStatus()
                }
            }
        })

        if (!file.exists()) {
            loaded = true
            updateStatus()
            return
        }

        // Office（docx/xlsx/pptx）：提取文本只读预览
        if (com.limao.unzip.files.OfficeText.supported(file.name)) {
            readOnly = true
            Thread {
                val (text, full) = com.limao.unzip.files.OfficeText.extract(file)
                runOnUiThread {
                    val truncNote = if (full) "" else "\n\n…（内容过长，已截断）"
                    edit.setText(text + truncNote)
                    lockIfReadonly()
                    loaded = true
                    dirty = false
                    updateStatus()
                }
            }.start()
            return
        }
        // 万能阅读：≤5MB 可编辑；5–30MB 只读全量查看；>30MB 提示
        if (file.length() > MAX_READ_BYTES) {
            Toast.makeText(this, getString(R.string.editor_too_large), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (file.length() > MAX_EDIT_BYTES) {
            readOnly = true
        }
        Thread {
            val bytes = runCatching { file.readBytes() }.getOrElse { ByteArray(0) }
            val (text, cs) = detectDecode(bytes)
            charset = cs
            runOnUiThread {
                edit.setText(text)
                lockIfReadonly()
                loaded = true
                dirty = false
                updateStatus()
            }
        }.start()
    }

    /** 只读（Office 预览/超大文本）：禁用编辑，避免用户改动后无法保存。 */
    private fun lockIfReadonly() {
        if (readOnly) edit.isEnabled = false
    }

    /** UTF-8 严格解码失败 → GBK → ISO-8859-1 兜底。 */
    private fun detectDecode(bytes: ByteArray): Pair<String, Charset> {
        val utf8 = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        runCatching { return utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString() to StandardCharsets.UTF_8 }
        val gbk = runCatching { charsetFor("GBK") }.getOrNull()
        if (gbk != null) {
            val strictGbk = gbk.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            runCatching { return strictGbk.decode(java.nio.ByteBuffer.wrap(bytes)).toString() to gbk }
            return String(bytes, gbk) to gbk
        }
        return String(bytes, StandardCharsets.ISO_8859_1) to StandardCharsets.ISO_8859_1
    }

    private fun updateStatus() {
        val text = edit.text ?: return
        val lines = text.count { it == '\n' } + if (text.isNotEmpty()) 1 else 0
        status.text = getString(R.string.editor_status, lines, text.length) + " · ${charset.name()}"
        titleView.text = if (dirty) "${file.name} ${getString(R.string.editor_modified)}" else file.name
    }

    /** 保存：UI 线程取文本 → 临时文件 + rename 原子落盘（写一半失败不会损坏原文件）。
     *  [waitMs] > 0 时阻塞等待写完（返回 true=已落盘），供返回键避免静默丢失。 */
    private fun save(waitMs: Long = 0, onDone: (Boolean) -> Unit = {}): Boolean {
        if (readOnly) {
            Toast.makeText(this, getString(R.string.editor_too_large), Toast.LENGTH_SHORT).show()
            return false
        }
        // 视图只能在主线程读；落盘走后台
        val text = edit.text.toString()
        var done: Thread? = null
        var ok = false
        done = Thread {
            val result = runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".polaris.tmp")
                tmp.writeText(text, charset)
                // 同目录 rename 原子替换（Linux rename 语义），失败时原文件与临时文件都保留
                if (!tmp.renameTo(file)) throw java.io.IOException("rename failed")
            }
            runOnUiThread {
                if (result.isSuccess) {
                    dirty = false
                    updateStatus()
                    Toast.makeText(this, getString(R.string.editor_saved), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.editor_save_failed), Toast.LENGTH_LONG).show()
                }
                onDone(result.isSuccess)
            }
            ok = result.isSuccess
        }.apply { start() }
        if (waitMs > 0) runCatching { done.join(waitMs) }
        return ok
    }

    override fun onBackPressed() {
        if (!dirty) {
            super.onBackPressed()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.editor_back_save))
            .setPositiveButton(getString(R.string.editor_save_btn)) { _, _ ->
                // 等待落盘完成再离开（≤5MB 文本，10s 上限足够），避免静默丢失
                save(waitMs = 10_000)
                super.onBackPressed()
            }
            .setNegativeButton(getString(R.string.editor_discard)) { _, _ -> dirty = false; super.onBackPressed() }
            .setNeutralButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun charsetFor(name: String): Charset = Charset.forName(name)

    companion object {
        const val EXTRA_PATH = "path"
        private const val MAX_EDIT_BYTES = 5L * 1024 * 1024
        private const val MAX_READ_BYTES = 30L * 1024 * 1024
    }
}
