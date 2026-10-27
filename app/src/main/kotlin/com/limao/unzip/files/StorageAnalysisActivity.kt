package com.limao.unzip.files

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.limao.unzip.R
import com.limao.unzip.engine.error.ErrorCatalog
import java.io.File

/**
 * WizTree 式存储分析：treemap 色块按文件/目录大小占比直观展示，
 * 点击目录块进入下级，点击文件块可删除/打开；顶栏返回上级目录。
 */
class StorageAnalysisActivity : AppCompatActivity() {

    private lateinit var treeView: TreemapView
    private lateinit var pathView: TextView
    private lateinit var loading: TextView
    private var currentDir: File = Environment.getExternalStorageDirectory()
    private var scanning = false

    data class Item(val path: String, val name: String, val size: Long, val isDir: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF16181D.toInt())
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            setBackgroundColor(0xFF22252B.toInt())
            setPadding(24, 36, 24, 36)
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(this).apply {
            text = "‹"; textSize = 26f; setTextColor(0xFFDDDDDD.toInt()); setPadding(0, 0, 28, 0)
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        })
        pathView = TextView(this).apply {
            textSize = 14f; setTextColor(0xFFEEEEEE.toInt())
            setTypeface(android.graphics.Typeface.MONOSPACE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(pathView)
        bar.addView(TextView(this).apply {
            text = "⬆"; textSize = 20f; setTextColor(0xFF82AAFF.toInt()); setPadding(24, 0, 0, 0)
            setOnClickListener { goUp() }
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        loading = TextView(this).apply {
            text = "扫描中…"
            textSize = 14f
            setTextColor(0xFF999999.toInt())
            gravity = Gravity.CENTER
        }
        treeView = TreemapView(this)
        root.addView(treeView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(loading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        treeView.onBlockClick = { block ->
            val f = File(block.path)
            if (f.isDirectory) {
                currentDir = f
                rescan()
            } else {
                showFileMenu(f, block.size)
            }
        }
        rescan()
    }

    private fun goUp() {
        val parent = currentDir.parentFile ?: return
        if (parent.absolutePath == "/storage/emulated") return
        currentDir = parent
        rescan()
    }

    private fun rescan() {
        if (scanning) return
        scanning = true
        treeView.visibility = View.GONE
        loading.visibility = View.VISIBLE
        pathView.text = currentDir.absolutePath
        val dir = currentDir
        Thread {
            val children = dir.listFiles()
                ?.filter { !it.name.startsWith(".") }
                ?.map { Item(it.absolutePath, it.name, dirSize(it, 0), it.isDirectory) }
                ?.sortedByDescending { it.size }
                ?.take(40)
                ?: emptyList()
            runOnUiThread {
                scanning = false
                loading.visibility = View.GONE
                treeView.visibility = View.VISIBLE
                treeView.show(children)
            }
        }.start()
    }

    private fun dirSize(f: File, depth: Int): Long {
        if (depth > 8) return 0
        if (f.isFile) return f.length()
        var sum = 0L
        val kids = runCatching { f.listFiles() }.getOrNull() ?: return 0
        for (k in kids) {
            sum += dirSize(k, depth + 1)
            if (sum > MAX_ITEM_BYTES) break
        }
        return sum
    }

    private fun showFileMenu(f: File, size: Long) {
        val renameAction = {
            val input = android.widget.EditText(this).apply { setText(f.name); setSelectAllOnFocus(true) }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.ops_rename))
                .setView(input)
                .setPositiveButton("OK") { _, _ ->
                    val newName = input.text.toString().trim()
                    val target = f.parentFile?.let { File(it, newName) }
                    if (newName.isNotEmpty() && target != null && !target.exists() && f.renameTo(target)) {
                        android.widget.Toast.makeText(this, getString(R.string.renamed_ok), android.widget.Toast.LENGTH_SHORT).show()
                        rescan()
                    } else {
                        android.widget.Toast.makeText(this, getString(R.string.name_exists), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setMessage("${ErrorCatalog.formatBytes(size)}\n${f.absolutePath}")
            .setPositiveButton(getString(R.string.ops_delete)) { _, _ ->
                Thread {
                    val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                    runOnUiThread {
                        android.widget.Toast.makeText(
                            this,
                            getString(if (ok) R.string.deleted_ok else R.string.op_failed),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        if (ok) rescan()
                    }
                }.start()
            }
            .setNeutralButton(getString(R.string.ops_rename)) { _, _ -> renameAction() }
            .setNegativeButton(getString(R.string.ops_open)) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(this, FileListActivity::class.java).apply {
                            putExtra(FileListActivity.EXTRA_MODE, "browser")
                            putExtra(FileListActivity.EXTRA_PATH, if (f.isDirectory) f.absolutePath else currentDir.absolutePath)
                        },
                    )
                }
            }
            .show()
    }

    /** treemap 自绘：按大小占比切分矩形，色相按序分布。 */
    class TreemapView(context: android.content.Context) : View(context) {

        data class Block(val rect: RectF, val path: String, val name: String, val size: Long, val color: Int)

        var blocks: List<Block> = emptyList()
        var onBlockClick: ((Block) -> Unit)? = null
        private var pending: List<Item>? = null

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }

        fun show(items: List<Item>) {
            pending = items
            recalc()
        }

        private fun recalc() {
            val items = pending ?: return
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 10f || h <= 10f) return
            pending = null
            val positive = items.filter { it.size > 0 }
            val laid = layout(positive.map { it.path to it.size }, 3f, 3f, w - 6f, h - 6f)
            blocks = laid.mapNotNull { (rect, path) ->
                val item = positive.firstOrNull { it.path == path } ?: return@mapNotNull null
                val idx = positive.indexOf(item)
                val hue = (idx * 53f) % 360f
                Block(rect, path, item.name, item.size, Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.78f)))
            }
            invalidate()
        }

        /** slice-and-dice：按占比沿长边递归切分。 */
        private fun layout(
            items: List<Pair<String, Long>>,
            x: Float, y: Float, w: Float, h: Float,
        ): List<Pair<RectF, String>> {
            if (items.isEmpty() || w <= 0f || h <= 0f) return emptyList()
            if (items.size == 1) {
                return listOf(RectF(x + 2f, y + 2f, x + w - 2f, y + h - 2f) to items[0].first)
            }
            val total = items.sumOf { it.second }.coerceAtLeast(1L)
            val first = items[0]
            val rest = items.drop(1)
            val frac = first.second.toFloat() / total
            val out = ArrayList<Pair<RectF, String>>()
            if (w >= h) {
                val split = w * frac
                out += layout(listOf(first), x, y, split, h)
                out += layout(rest, x + split, y, w - split, h)
            } else {
                val split = h * frac
                out += layout(listOf(first), x, y, w, split)
                out += layout(rest, x, y + split, w, h - split)
            }
            return out
        }

        override fun onDraw(canvas: Canvas) {
            if (pending != null && width > 10) recalc()
            super.onDraw(canvas)
            for (b in blocks) {
                fill.color = b.color
                canvas.drawRect(b.rect, fill)
                canvas.drawRect(b.rect.left, b.rect.top, b.rect.right, b.rect.top + 4f, edge)
                if (b.rect.width() > 96f && b.rect.height() > 64f) {
                    textPaint.textSize = if (b.rect.width() > 280f) 30f else 22f
                    subPaint.textSize = if (b.rect.width() > 280f) 24f else 19f
                    canvas.drawText(b.name.take(14), b.rect.left + 12f, b.rect.top + textPaint.textSize + 10f, textPaint)
                    canvas.drawText(ErrorCatalog.formatBytes(b.size), b.rect.left + 12f, b.rect.top + textPaint.textSize + subPaint.textSize + 18f, subPaint)
                }
            }
        }

        override fun performClick(): Boolean = super.performClick()

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val b = blocks.firstOrNull { it.rect.contains(event.x, event.y) }
                if (b != null) {
                    performClick()
                    onBlockClick?.invoke(b)
                }
                return true
            }
            return true
        }
    }

    companion object {
        private const val MAX_ITEM_BYTES = 8L * 1024 * 1024 * 1024
    }
}
