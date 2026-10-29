package com.limao.unzip.files

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.limao.unzip.ObjectGraph
import com.limao.unzip.R
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.api.ProgressState
import com.limao.unzip.engine.api.basis
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 解压进度弹层（半透明卡片）：实时百分比/字节进度，完成后"查看已解压文件"直达目录。
 * 遇到加密压缩包 → 弹层内嵌密码输入 → 带密码重试（无需返回列表）。
 */
class ProgressActivity : AppCompatActivity() {

    private lateinit var title: TextView
    private lateinit var percent: TextView
    private lateinit var bar: LinearProgressIndicator
    private lateinit var detail: TextView
    private lateinit var btnCancel: Button
    private lateinit var btnView: Button
    private lateinit var btnClose: Button
    private lateinit var passInput: EditText
    private lateinit var retryBtn: Button
    private var srcPath: String = ""
    private var dstPath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        srcPath = intent.getStringExtra(EXTRA_SRC) ?: ""
        dstPath = intent.getStringExtra(EXTRA_DST) ?: ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            fitsSystemWindows = true
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(40, 36, 40, 32)
        }
        title = TextView(this).apply {
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        card.addView(title)
        percent = TextView(this).apply {
            text = "0%"
            textSize = 34f
            setTextColor(0xFF1296DB.toInt())
            setPadding(0, 20, 0, 8)
        }
        card.addView(percent)
        bar = LinearProgressIndicator(this).apply {
            max = 100
            trackCornerRadius = 6
        }
        card.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)))
        detail = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF999999.toInt())
            setPadding(0, 12, 0, 0)
        }
        card.addView(detail)
        passInput = EditText(this).apply {
            hint = "密码"
            visibility = View.GONE
            maxLines = 1
        }
        card.addView(passInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        retryBtn = Button(this).apply {
            text = "带密码重试"
            textSize = 13f
            visibility = View.GONE
            setOnClickListener {
                val pwd = passInput.text.toString().takeIf { it.isNotEmpty() }
                ObjectGraph.activeJobId.value?.let { wid: UUID ->
                    runCatching { WorkManager.getInstance(this@ProgressActivity).cancelWorkById(wid) }
                }
                val req = OneTimeWorkRequestBuilder<com.limao.unzip.worker.ExtractionWorker>()
                    .setInputData(
                        workDataOf(
                            com.limao.unzip.worker.ExtractionWorker.KEY_SRC_PATH to srcPath,
                            com.limao.unzip.worker.ExtractionWorker.KEY_DST_PATH to dstPath,
                            com.limao.unzip.worker.ExtractionWorker.KEY_PASSWORD to pwd,
                        ),
                    )
                    .build()
                WorkManager.getInstance(this@ProgressActivity).enqueue(req)
                passInput.visibility = View.GONE
                retryBtn.visibility = View.GONE
                btnCancel.visibility = View.VISIBLE
            }
        }
        card.addView(retryBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        btnCancel = Button(this).apply {
            text = getString(R.string.progress_cancel)
            textSize = 13f
        }
        card.addView(btnCancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        btnView = Button(this).apply {
            text = getString(R.string.progress_view)
            textSize = 14f
            visibility = View.GONE
        }
        card.addView(btnView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        btnClose = Button(this).apply {
            text = getString(R.string.progress_close)
            textSize = 14f
            visibility = View.GONE
        }
        card.addView(btnClose, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        root.addView(card, LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        title.text = if (name.isNotEmpty()) name else getString(R.string.progress_title)
        btnCancel.setOnClickListener {
            ObjectGraph.activeJobId.value?.let { wid: UUID ->
                runCatching { WorkManager.getInstance(this).cancelWorkById(wid) }
            }
        }
        btnView.setOnClickListener {
            if (dstPath.isNotEmpty()) {
                startActivity(Intent(this, FileListActivity::class.java).apply {
                    putExtra(FileListActivity.EXTRA_MODE, "browser")
                    putExtra(FileListActivity.EXTRA_PATH, dstPath)
                })
            }
            finish()
        }
        btnClose.setOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ObjectGraph.activeProgress.collect { s ->
                        if (s == null || (s.state != ProgressState.RUNNING && s.state != ProgressState.ABORTING)) return@collect
                        val pct = when (s.basis()) {
                            com.limao.unzip.engine.api.ProgressBasis.OUTPUT_BYTES ->
                                (s.outputBytesDone * 100 / s.outputBytesTotal).toInt()
                            com.limao.unzip.engine.api.ProgressBasis.ENTRY_COUNT ->
                                (s.entriesDone * 100 / s.entriesTotal).toInt()
                            else -> 0
                        }
                        if (s.basis() == com.limao.unzip.engine.api.ProgressBasis.INDETERMINATE) {
                            bar.isIndeterminate = true
                            percent.text = "…"
                        } else {
                            bar.isIndeterminate = false
                            bar.setProgress(pct, true)
                            percent.text = "$pct%"
                        }
                        detail.text = buildString {
                            if (s.outputBytesTotal > 0) {
                                append(com.limao.unzip.engine.error.ErrorCatalog.formatBytes(s.outputBytesDone))
                                append(" / ")
                                append(com.limao.unzip.engine.error.ErrorCatalog.formatBytes(s.outputBytesTotal))
                            }
                            if (s.entriesTotal > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("${s.entriesDone}/${s.entriesTotal}")
                            }
                            s.currentEntryPath?.let { if (it.isNotEmpty()) { if (isNotEmpty()) append("\n"); append(it) } }
                        }
                    }
                }
                launch {
                    ObjectGraph.activeResult.collect { r ->
                        if (r == null) return@collect
                        ObjectGraph.activeResult.value = null
                        btnCancel.visibility = View.GONE
                        when (r) {
                            is ExtractResult.Completed -> {
                                percent.text = "100%"
                                bar.setProgress(100, true)
                                detail.text = "${r.entriesDone} · ${com.limao.unzip.engine.error.ErrorCatalog.formatBytes(r.bytesDone)}"
                                title.text = getString(R.string.progress_done)
                                btnView.visibility = if (dstPath.isNotEmpty()) View.VISIBLE else View.GONE
                                btnClose.visibility = View.VISIBLE
                            }
                            is ExtractResult.Failed -> {
                                if (r.error.id == com.limao.unzip.engine.error.ErrorId.NEED_PASSWORD && srcPath.isNotEmpty()) {
                                    passInput.visibility = View.VISIBLE
                                    retryBtn.visibility = View.VISIBLE
                                    title.text = "此压缩包已加密"
                                    detail.text = ""
                                } else {
                                    title.text = getString(R.string.progress_failed)
                                    detail.text = r.error.message
                                    btnClose.visibility = View.VISIBLE
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_SRC = "extra_src"
        const val EXTRA_DST = "extra_dst"
    }
}
