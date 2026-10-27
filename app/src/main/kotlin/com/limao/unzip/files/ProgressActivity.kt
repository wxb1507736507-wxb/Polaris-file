package com.limao.unzip.files

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkManager
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.limao.unzip.ObjectGraph
import com.limao.unzip.R
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.api.ProgressState
import com.limao.unzip.engine.api.basis
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 解压进度弹层（半透明卡片）：实时百分比/字节进度，完成后提供"查看已解压文件"直达目录。
 * 数据源 = ObjectGraph 单活动任务通道（Worker 写入）。
 */
class ProgressActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""

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
        val title = TextView(this).apply {
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        card.addView(title)
        val percent = TextView(this).apply {
            text = "0%"
            textSize = 34f
            setTextColor(0xFF1296DB.toInt())
            setPadding(0, 20, 0, 8)
        }
        card.addView(percent)
        val bar = LinearProgressIndicator(this).apply {
            max = 100
            trackCornerRadius = 6
        }
        card.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)))
        val detail = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF999999.toInt())
            setPadding(0, 12, 0, 0)
        }
        card.addView(detail)
        val btnCancel = Button(this).apply {
            text = getString(R.string.progress_cancel)
            textSize = 13f
        }
        card.addView(btnCancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        val btnView = Button(this).apply {
            text = getString(R.string.progress_view)
            textSize = 14f
            visibility = android.view.View.GONE
        }
        card.addView(btnView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        val btnClose = Button(this).apply {
            text = getString(R.string.progress_close)
            textSize = 14f
            visibility = android.view.View.GONE
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
            val dst = ObjectGraph.activeDstPath
            if (dst != null) {
                startActivity(Intent(this, FileListActivity::class.java).apply {
                    putExtra(FileListActivity.EXTRA_MODE, "browser")
                    putExtra(FileListActivity.EXTRA_PATH, dst)
                })
            }
            finish()
        }
        btnClose.setOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ObjectGraph.activeProgress.collect { s ->
                        if (s == null) return@collect
                        val pct = when (s.basis()) {
                            com.limao.unzip.engine.api.ProgressBasis.OUTPUT_BYTES ->
                                (s.outputBytesDone * 100 / s.outputBytesTotal).toInt()
                            com.limao.unzip.engine.api.ProgressBasis.ENTRY_COUNT ->
                                (s.entriesDone * 100 / s.entriesTotal).toInt()
                            else -> 0
                        }
                        if (s.state == ProgressState.RUNNING || s.state == ProgressState.ABORTING) {
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
                }
                launch {
                    ObjectGraph.activeResult.collect { r ->
                        if (r == null) return@collect
                        ObjectGraph.activeResult.value = null
                        btnCancel.visibility = android.view.View.GONE
                        when (r) {
                            is ExtractResult.Completed -> {
                                percent.text = "100%"
                                bar.setProgress(100, true)
                                detail.text = getString(R.string.mine_version).let { "${r.entriesDone} · ${com.limao.unzip.engine.error.ErrorCatalog.formatBytes(r.bytesDone)}" }
                                title.text = getString(R.string.progress_done)
                                btnView.visibility = if (ObjectGraph.activeDstPath != null) android.view.View.VISIBLE else android.view.View.GONE
                                btnClose.visibility = android.view.View.VISIBLE
                            }
                            is ExtractResult.Aborted -> {
                                title.text = getString(R.string.btn_cancel)
                                detail.text = "${r.entriesDone}"
                                btnClose.visibility = android.view.View.VISIBLE
                            }
                            is ExtractResult.Failed -> {
                                title.text = getString(R.string.progress_failed)
                                detail.text = r.error.message
                                btnClose.visibility = android.view.View.VISIBLE
                            }
                        }
                    }
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_NAME = "name"
    }
}
