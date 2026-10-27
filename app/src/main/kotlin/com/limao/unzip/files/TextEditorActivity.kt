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
        if (file.length() > MAX_EDIT_BYTES) {
            readOnly = true
            edit.isEnabled = false
            edit.setText(getString(R.string.editor_too_large))
            loaded = true
            status.text = "${file.length()} B"
            return
        }
        Thread {
            val bytes = runCatching { file.readBytes() }.getOrElse { ByteArray(0) }
            val (text, cs) = detectDecode(bytes)
            charset = cs
            runOnUiThread {
                edit.setText(text)
                loaded = true
                dirty = false
                updateStatus()
            }
        }.start()
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

    private fun save() {
        if (readOnly) {
            Toast.makeText(this, getString(R.string.editor_too_large), Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val ok = runCatching {
                file.parentFile?.mkdirs()
                file.writeText(edit.text.toString(), charset)
            }.isSuccess
            runOnUiThread {
                Toast.makeText(this, getString(if (ok) R.string.editor_saved else R.string.toast_cant_open), Toast.LENGTH_SHORT).show()
                if (ok) {
                    dirty = false
                    updateStatus()
                }
            }
        }.start()
    }

    override fun onBackPressed() {
        if (!dirty) {
            super.onBackPressed()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.editor_back_save))
            .setPositiveButton(getString(R.string.editor_save_btn)) { _, _ -> save(); super.onBackPressed() }
            .setNegativeButton(getString(R.string.editor_discard)) { _, _ -> dirty = false; super.onBackPressed() }
            .setNeutralButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun charsetFor(name: String): Charset = Charset.forName(name)

    companion object {
        const val EXTRA_PATH = "path"
        private const val MAX_EDIT_BYTES = 5L * 1024 * 1024
    }
}
