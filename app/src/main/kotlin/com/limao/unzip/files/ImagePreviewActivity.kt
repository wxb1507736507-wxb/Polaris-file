package com.limao.unzip.files

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.limao.unzip.R
import java.io.File

/**
 * 内建图片查看器：全屏显示，按屏宽采样解码（大图不 OOM），双击返回。
 * 底部操作栏：删除 / 重命名 / 移动（与文件列表同语义：删除需确认、改名改后缀需二次确认、移动走文件夹选择器）。
 */
class ImagePreviewActivity : AppCompatActivity() {

    private lateinit var file: File
    private lateinit var titleView: TextView
    private lateinit var sizeView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        file = File(path)
        // 背景跟随应用主题：日间白底深字，夜间黑底浅字
        val night = com.limao.unzip.UiTheme.isNight()
        val bgRoot = if (night) 0xFF101010.toInt() else 0xFFF5F6F8.toInt()
        val bgBar = if (night) 0xE6222222.toInt() else 0xE6FFFFFF.toInt()
        val fgPrimary = if (night) 0xFFEEEEEE.toInt() else 0xFF1D1B20.toInt()
        val fgSecondary = if (night) 0xFFDDDDDD.toInt() else 0xFF49454F.toInt()
        val fgMuted = if (night) 0xFF999999.toInt() else 0xFF666666.toInt()
        val bgImageArea = if (night) 0xFF000000.toInt() else 0xFFE8EAED.toInt()
        val bgMeta = if (night) 0xFF222222.toInt() else 0xFFF0F0F0.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgRoot)
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
        titleView = TextView(this).apply {
            text = file.name; textSize = 14f; setTextColor(fgPrimary)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(titleView)
        sizeView = TextView(this).apply {
            text = "${file.length() / 1024} KB"
            textSize = 12f; setTextColor(fgMuted)
        }
        bar.addView(sizeView)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(bgImageArea)
        }
        root.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val meta = TextView(this).apply {
            textSize = 12f; setTextColor(fgMuted)
            setBackgroundColor(bgMeta)
            setPadding(20, 10, 20, 14)
            gravity = Gravity.CENTER
        }
        root.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 底部操作栏：删除 / 重命名 / 移动
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bgBar)
            setPadding(12, 6, 12, 6)
        }
        fun action(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 14f; setTextColor(fgPrimary); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 22, 0, 22)
            setOnClickListener { onClick() }
        }
        actions.addView(action(getString(R.string.sel_delete)) { opDelete() })
        actions.addView(action(getString(R.string.ops_rename)) { opRename() })
        actions.addView(action(getString(R.string.sel_move)) { opMove() })
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        val targetW = resources.displayMetrics.widthPixels
        Thread {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
            runOnUiThread {
                if (bmp != null) {
                    img.setImageBitmap(bmp)
                    meta.text = "${bounds.outWidth}×${bounds.outHeight} · 采样 $sample"
                } else {
                    meta.text = getString(R.string.toast_cant_open, file.name)
                }
            }
        }.start()
    }

    // ---------- 删除 ----------

    private fun opDelete() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_delete_multi, 1))
            .setPositiveButton(getString(R.string.sel_delete)) { _, _ ->
                Thread {
                    val ok = runCatching { file.delete() }.getOrDefault(false)
                    runOnUiThread {
                        toast(getString(if (ok) R.string.op_done else R.string.op_failed))
                        if (ok) finish()
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ---------- 重命名 ----------

    private fun opRename() {
        askName(getString(R.string.ops_rename), file.name) { newName ->
            if (newName.isEmpty() || newName == file.name) return@askName
            val target = file.parentFile?.let { File(it, newName) } ?: return@askName
            if (target.exists()) { toast(getString(R.string.name_exists)); return@askName }
            val oldExt = file.name.substringAfterLast('.', "")
            val newExt = newName.substringAfterLast('.', "")
            val extChanged = oldExt != newExt && (oldExt.isNotEmpty() || newExt.isNotEmpty())
            fun doRename() {
                Thread {
                    val ok = runCatching { file.renameTo(target) }.getOrDefault(false)
                    runOnUiThread {
                        if (ok) {
                            file = target
                            titleView.text = file.name
                            sizeView.text = "${file.length() / 1024} KB"
                            toast(getString(R.string.renamed_ok))
                        } else toast(getString(R.string.op_failed))
                    }
                }.start()
            }
            if (extChanged) {
                // 更改后缀=更改格式：先确认一次
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ops_rename))
                    .setMessage(getString(R.string.rename_ext_confirm, oldExt.ifEmpty { "—" }, newExt.ifEmpty { "—" }))
                    .setPositiveButton("OK") { _, _ -> doRename() }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            } else doRename()
        }
    }

    private fun askName(title: String, prefill: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply { hint = getString(R.string.input_name); setText(prefill); setSelectAllOnFocus(true) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ---------- 移动 ----------

    private fun opMove() {
        pickFolder { dst ->
            Thread {
                val ok = runCatching {
                    val target = File(dst, file.name)
                    file.renameTo(target) ||
                        (target.outputStream().use { out -> file.inputStream().use { it.copyTo(out); true } } && file.delete())
                }.getOrDefault(false)
                runOnUiThread {
                    toast(getString(if (ok) R.string.op_done else R.string.op_failed))
                    if (ok) finish()
                }
            }.start()
        }
    }

    /** 文件夹选择器（与文件列表一致：进入/上级/选择此文件夹） */
    private fun pickFolder(onOk: (File) -> Unit) {
        var cur = File(Environment.getExternalStorageDirectory().absolutePath)
        val listView = ListView(this).apply { divider = null }
        val dialog = AlertDialog.Builder(this)
            .setTitle(cur.absolutePath)
            .setView(listView)
            .setPositiveButton(getString(R.string.pick_here), null)
            .setNeutralButton(getString(R.string.pick_up), null)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()
        fun refresh() {
            dialog.setTitle(cur.absolutePath)
            val dirs = (cur.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList())
                .sortedBy { it.name.lowercase() }
            listView.adapter = object : BaseAdapter() {
                override fun getCount() = dirs.size
                override fun getItem(position: Int) = dirs[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    TextView(this@ImagePreviewActivity).apply {
                        text = "📁 ${dirs[position].name}"
                        textSize = 15f
                        setPadding(28, 24, 28, 24)
                    }
            }
            listView.setOnItemClickListener { _, _, i, _ -> cur = dirs[i]; refresh() }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onOk(cur); dialog.dismiss() }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { cur = cur.parentFile ?: cur; refresh() }
        }
        refresh()
        dialog.show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_PATH = "path"
    }
}
