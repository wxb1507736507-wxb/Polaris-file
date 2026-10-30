package com.limao.unzip.files

import android.app.AlertDialog
import com.limao.unzip.UiTheme
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.AbsListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.limao.unzip.ObjectGraph
import com.limao.unzip.R
import com.limao.unzip.engine.api.CreateFormat
import com.limao.unzip.engine.error.ErrorCatalog
import com.limao.unzip.worker.CompressWorker
import com.limao.unzip.worker.ExtractionWorker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一列表页（QQ浏览器样式）：archives/download/images/video/audio/docs/apk/browser 七类。
 * 文档分类带子类筛选；长按文件 → 重命名/删除/详情/复制路径/编辑（MT 管理器式）；
 * 浏览器可新建文件夹/文件、搜索、压缩当前文件夹。
 */
class FileListActivity : AppCompatActivity() {

    private var mode: String = "archives"
    private var browserPath: String? = null
    private var titleText: String = ""
    private var docFilter = 0
    private var searchQuery: String = ""

    private var items: List<Entry> = emptyList()
    private var shown: List<Entry> = emptyList()
    private lateinit var adapter: ItemAdapter
    private lateinit var titleView: TextView
    private lateinit var countView: TextView
    private lateinit var emptyView: TextView
    private lateinit var listView: AbsListView
    private lateinit var loading: ProgressBar
    private var chipsRow: LinearLayout? = null
    private val dfmt = SimpleDateFormat("yyyy/M/d", Locale.getDefault())
    private var dualMode = false // onCreate 读取（字段初始化早于 base context attach 会 NPE）

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dualMode = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("dual", false)
        mode = intent.getStringExtra(EXTRA_MODE) ?: "archives"
        browserPath = intent.getStringExtra(EXTRA_PATH)
        titleText = when (mode) {
            "archives" -> getString(R.string.cat_archives)
            "download" -> getString(R.string.cat_download)
            "images" -> getString(R.string.cat_images)
            "video" -> getString(R.string.cat_video)
            "audio" -> getString(R.string.cat_audio)
            "docs" -> getString(R.string.cat_docs)
            "apk" -> getString(R.string.cat_apk)
            "recent" -> getString(R.string.cat_recent)
            "browser" -> File(browserPath ?: Environment.getExternalStorageDirectory().absolutePath).name.ifEmpty { getString(R.string.cat_all) }
            else -> getString(R.string.cat_all)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.pageBg())
            fitsSystemWindows = true
        }
        // 顶栏：‹ 标题 计数 🔍 ＋
        val bar = LinearLayout(this).apply {
            setBackgroundColor(UiTheme.card())
            setPadding(24, 40, 24, 40)
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(this).apply {
            text = "‹"; textSize = 26f; setPadding(0, 0, 32, 0)
            setOnClickListener { finish() }
        })
        titleView = TextView(this).apply {
            text = titleText; textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
        }
        countView = TextView(this).apply {
            textSize = 13f; setTextColor(UiTheme.textSecondary())
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(countView)
        bar.addView(TextView(this).apply {
            text = if (dualMode) "☰" else "▦"; textSize = 18f; setPadding(24, 0, 12, 0)
            setOnClickListener { toggleLayout() }
        })
        bar.addView(TextView(this).apply {
            text = "🔍"; textSize = 18f; setPadding(24, 0, 24, 0)
            setOnClickListener { showSearchDialog() }
        })
        if (mode == "browser") {
            bar.addView(TextView(this).apply {
                text = "＋"; textSize = 22f
                setOnClickListener { showNewDialog() }
            })
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (mode == "docs") buildDocChips(root)

        loading = ProgressBar(this)
        root.addView(loading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        emptyView = TextView(this).apply {
            text = getString(R.string.list_none); setTextColor(UiTheme.textSecondary()); gravity = Gravity.CENTER
            setPadding(0, 200, 0, 0); visibility = View.GONE
        }
        listView = if (dualMode) {
            GridView(this).apply {
                numColumns = 2
                horizontalSpacing = dp(6)
                verticalSpacing = dp(6)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isHapticFeedbackEnabled = false
            }
        } else {
            ListView(this).apply { divider = null; isHapticFeedbackEnabled = false }
        }
        adapter = ItemAdapter()
        listView.adapter = adapter
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(emptyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 多选操作条（选择模式显示，替代压缩行）
        opsBar = buildOpsBar()
        root.addView(opsBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        opsBar?.visibility = View.GONE

        if (mode == "browser") {
            compressRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            listOf(getString(R.string.compress_zip) to CreateFormat.ZIP, getString(R.string.compress_7z) to CreateFormat.SEVEN_Z, getString(R.string.compress_tgz) to CreateFormat.TAR_GZ).forEach { (label, fmt) ->
                compressRow!!.addView(android.widget.Button(this).apply {
                    text = label; textSize = 12f; maxLines = 1
                    setOnClickListener { startCompress(File(browserPath!!), fmt) }
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(4) })
            }
            root.addView(compressRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        reload()
    }

    override fun onResume() {
        super.onResume()
        // 返回页面时重扫（MediaStore/文件系统变化即时可见）
        if (this::adapter.isInitialized) reload()
    }

    private var opsBar: LinearLayout? = null
    private var compressRow: LinearLayout? = null
    private val selection = LinkedHashMap<String, Entry>()
    private val inSelect: Boolean get() = selection.isNotEmpty()

    // ---------- 多选模式（电脑式文件操作） ----------

    private fun buildOpsBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.card())
            setPadding(12, 10, 12, 12)
            visibility = View.GONE
        }
        val selTitle = TextView(this).apply {
            textSize = 13f; setTextColor(UiTheme.accent())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
            tag = "title"
        }
        bar.addView(selTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fun row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        val r1 = row()
        val r2 = row()
        fun addBtn(parent: LinearLayout, label: String, enabled: () -> Boolean, action: () -> Unit) {
            parent.addView(android.widget.Button(this).apply {
                text = label; textSize = 12f; maxLines = 1
                isEnabled = true
                setOnClickListener { if (enabled()) action() }
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(3) })
        }
        addBtn(r1, getString(R.string.sel_all), { true }) { selectAll() }
        addBtn(r1, getString(R.string.sel_copy), { selection.isNotEmpty() }) { opCopyMove(move = false) }
        addBtn(r1, getString(R.string.sel_move), { selection.isNotEmpty() }) { opCopyMove(move = true) }
        addBtn(r1, getString(R.string.sel_delete), { selection.isNotEmpty() }) { opDeleteSelected() }
        addBtn(r2, getString(R.string.sel_rename), { selection.size == 1 }) { opRenameSelected() }
        addBtn(r2, getString(R.string.sel_compress), { selection.isNotEmpty() }) { opCompressSelected() }
        addBtn(r2, getString(R.string.sel_share), { selection.isNotEmpty() }) { opShareSelected() }
        addBtn(r2, getString(R.string.sel_exit), { true }) { exitSelection() }
        bar.addView(r1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(r2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return bar
    }

    private fun updateChrome() {
        val n = selection.size
        opsBar?.visibility = if (inSelect) View.VISIBLE else View.GONE
        compressRow?.visibility = if (mode == "browser" && !inSelect) View.VISIBLE else View.GONE
        if (inSelect) {
            opsBar?.findViewWithTag<TextView>("title")?.text = getString(R.string.sel_title, n)
            countView.text = getString(R.string.sel_title, n)
        } else {
            applyFilter()
        }
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelect(e: Entry) {
        val p = e.path ?: return
        if (selection.containsKey(p)) selection.remove(p) else selection[p] = e
        updateChrome()
    }

    private fun selectAll() {
        shown.forEach { e -> e.path?.let { selection[it] = e } }
        updateChrome()
    }

    private fun exitSelection() {
        selection.clear()
        updateChrome()
    }

    private fun opDeleteSelected() {
        val paths = selection.keys.toList()
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_delete_multi, paths.size))
            .setPositiveButton(getString(R.string.sel_delete)) { _, _ ->
                Thread {
                    val ok = paths.all { p ->
                        val f = File(p)
                        (if (f.isDirectory) f.deleteRecursively() else f.delete())
                    }
                    runOnUiThread {
                        toast(getString(if (ok) R.string.op_done else R.string.op_failed))
                        reload()
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun opRenameSelected() {
        val e = selection.values.firstOrNull() ?: return
        askName(getString(R.string.ops_rename), e.name) { newName ->
            if (newName.isEmpty() || newName == e.name) return@askName
            val target = File(e.path!!).parentFile?.let { File(it, newName) } ?: return@askName
            if (target.exists()) { toast(getString(R.string.name_exists)); return@askName }
            if (File(e.path!!).renameTo(target)) { toast(getString(R.string.renamed_ok)); reload() } else toast(getString(R.string.op_failed))
        }
    }

    private fun opCopyMove(move: Boolean) {
        val paths = selection.keys.toList()
        val verb = if (move) getString(R.string.sel_move) else getString(R.string.sel_copy)
        pickFolder { dst ->
            Thread {
                var ok = true
                for (p in paths) {
                    val src = File(p)
                    ok = ok && runCatching {
                        if (move) {
                            src.renameTo(File(dst, src.name)) || (copyRec(src, dst) && src.deleteRecursively())
                        } else {
                            copyRec(src, dst)
                        }
                    }.getOrDefault(false)
                }
                runOnUiThread {
                    toast(getString(if (ok) R.string.op_done else R.string.op_failed))
                    reload()
                }
            }.start()
        }
    }

    private fun copyRec(src: File, dstDir: File): Boolean = try {
        val target = File(dstDir, src.name)
        if (src.isDirectory) {
            target.mkdirs()
            src.listFiles()?.all { copyRec(it, target) } ?: true
        } else {
            target.parentFile?.mkdirs()
            if (target.exists()) target.delete()
            src.copyTo(target, overwrite = true).length() >= 0
        }
    } catch (t: Throwable) {
        false
    }

    private fun opCompressSelected() {
        val paths = selection.keys.map { File(it) }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val out = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "selected_$stamp.zip")
        toast(getString(R.string.compress_started, out.name))
        Thread {
            val job = com.limao.unzip.ObjectGraph.compressor.submit(
                com.limao.unzip.engine.api.CompressRequest(
                    source = PathsCompressSource(paths),
                    output = com.limao.unzip.io.AndroidFileArchiveOutput(out),
                    format = com.limao.unzip.engine.api.CreateFormat.ZIP,
                ),
            )
            val r = kotlinx.coroutines.runBlocking { job.await() }
            runOnUiThread {
                toast(getString(if (r is com.limao.unzip.engine.api.ExtractResult.Completed) R.string.compress_done else R.string.op_failed, out.name))
                if (r is com.limao.unzip.engine.api.ExtractResult.Completed) reload()
            }
        }.start()
    }

    private fun opShareSelected() {
        val files = selection.keys.filter { File(it).isFile }
        if (files.isEmpty()) { toast(getString(R.string.share_none)); return }
        val uris = ArrayList<android.net.Uri>(files.size)
        for (p in files) {
            runCatching { uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", File(p))) }
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.sel_share))) }
            .onFailure { toast(getString(R.string.share_none)) }
    }

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
                    TextView(this@FileListActivity).apply {
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

    private fun buildDocChips(root: LinearLayout) {
        val scroll = HorizontalScrollView(this).apply {
            setBackgroundColor(UiTheme.card())
            isHorizontalScrollBarEnabled = false
        }
        chipsRow = LinearLayout(this).apply { setPadding(24, 8, 24, 20) }
        FileItems.DOC_GROUPS.forEachIndexed { i, label ->
            val chip = TextView(this).apply {
                text = label; textSize = 13f
                setPadding(28, 10, 28, 10)
                setTextColor(if (i == 0) UiTheme.accent() else UiTheme.textTertiary())
                setBackgroundResource(if (i == 0) R.drawable.chip_selected else R.drawable.chip_normal)
                setOnClickListener { selectDocGroup(i) }
                tag = i
            }
            chipsRow!!.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = 20 })
        }
        scroll.addView(chipsRow)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun reload() {
        loading.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        listView.visibility = View.GONE
        Thread {
            val list: List<Entry> = when (mode) {
                "archives" -> ArchiveScanner.scanRoots(this).map {
                    val extracted = ExtractHistory.lookup(this, it.file.absolutePath)
                    Entry(
                        it.name, it.file.absolutePath, null, it.size, it.lastModified, false, "📦",
                        if (extracted != null) getString(R.string.extracted_badge) else getString(R.string.cat_archives),
                    )
                }
                "download" -> FileItems.netdiskDownloads(500)
                "images" -> FileItems.images(this)
                "video" -> FileItems.videos(this)
                "audio" -> FileItems.audio(this)
                "docs" -> FileItems.docs(this)
                "apk" -> FileItems.apks(this)
                "recent" -> FileItems.recentMixed(this)
                "browser" -> FileItems.listDir(File(browserPath!!), archiveOnly = false)
                else -> emptyList()
            }
            runOnUiThread {
                items = list
                selection.clear() // 刷新后路径可能变化，清空选择
                applyFilter()
                opsBar?.visibility = View.GONE
                compressRow?.visibility = if (mode == "browser") View.VISIBLE else View.GONE
                loading.visibility = View.GONE
            }
        }.start()
    }

    private fun applyFilter() {
        shown = items.filter { e ->
            (mode != "docs" || docFilter == 0 || FileItems.docGroup(e.name) == docFilter - 1) &&
                (searchQuery.isEmpty() || e.name.contains(searchQuery, ignoreCase = true))
        }
        countView.text = if (searchQuery.isEmpty()) {
            getString(R.string.count_items, shown.size)
        } else {
            getString(R.string.search_badge, shown.size, searchQuery)
        }
        emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        listView.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
    }

    private fun selectDocGroup(i: Int) {
        docFilter = i
        chipsRow?.let { row ->
            for (c in 0 until row.childCount) {
                val chip = row.getChildAt(c) as TextView
                val sel = chip.tag == i
                chip.setTextColor(if (sel) UiTheme.accent() else UiTheme.textTertiary())
                chip.setBackgroundResource(if (sel) R.drawable.chip_selected else R.drawable.chip_normal)
            }
        }
        applyFilter()
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply { hint = getString(R.string.search_hint); setText(searchQuery) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.search_hint))
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                searchQuery = input.text.toString().trim()
                applyFilter()
            }
            .setNeutralButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showNewDialog() {
        val dir = File(browserPath ?: return)
        AlertDialog.Builder(this)
            .setItems(arrayOf(getString(R.string.new_folder), getString(R.string.new_file))) { _, which ->
                askName(getString(if (which == 0) R.string.new_folder else R.string.new_file), "new_${System.currentTimeMillis() / 1000}") { name ->
                    val target = File(dir, name)
                    if (target.exists()) {
                        toast(getString(R.string.name_exists))
                    } else {
                        val ok = if (which == 0) target.mkdirs() else target.createNewFile()
                        toast(getString(if (ok) R.string.created_ok else R.string.toast_cant_open, name))
                        if (ok) reload()
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
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

    // ---------- 长按操作（MT 管理器式） ----------

    private fun showOps(e: Entry) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (e.path != null) {
            if (isTextName(e.name)) actions.add(getString(R.string.ops_edit) to { openEditor(File(e.path!!)) })
            actions.add(getString(R.string.ops_rename) to { askName(getString(R.string.ops_rename), e.name) { newName ->
                if (newName.isEmpty() || newName == e.name) return@askName
                val target = File(e.path!!).parentFile?.let { File(it, newName) } ?: return@askName
                if (target.exists()) { toast(getString(R.string.name_exists)); return@askName }
                if (File(e.path!!).renameTo(target)) { toast(getString(R.string.renamed_ok)); reload() } else toast(getString(R.string.toast_cant_open, e.name))
            } })
            actions.add(getString(R.string.ops_delete) to {
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.delete_confirm, e.name))
                    .setPositiveButton(getString(R.string.ops_delete)) { _, _ ->
                        val f = File(e.path!!)
                        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                        toast(getString(if (ok) R.string.deleted_ok else R.string.toast_cant_open, e.name))
                        if (ok) reload()
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            })
            actions.add(getString(R.string.ops_copy_path) to {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("path", e.path))
                toast(getString(R.string.copied))
            })
        }
        actions.add(getString(R.string.ops_details) to {
            val f = e.path?.let { File(it) }
            val msg = buildString {
                append("≡ ${e.name}\n")
                append(getString(R.string.kind_folder).let { if (e.isDir) it else e.kindLabel.ifEmpty { "file" } }).append('\n')
                if (f != null) {
                    append(f.absolutePath).append('\n')
                    if (!e.isDir) append(ErrorCatalog.formatBytes(e.size)).append('\n')
                    append(dfmt.format(Date(if (e.time > 0) e.time else f.lastModified())))
                } else {
                    append(ErrorCatalog.formatBytes(e.size)).append('\n').append(dfmt.format(Date(e.time)))
                }
            }
            AlertDialog.Builder(this).setTitle(getString(R.string.details_title)).setMessage(msg)
                .setPositiveButton("OK", null).show()
        })
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun isImageName(name: String): Boolean = com.limao.unzip.files.FileItems.iconResFor(name) == com.limao.unzip.R.drawable.ic_image

    private fun isTextName(name: String): Boolean {
        val lower = name.lowercase()
        return listOf(".txt", ".log", ".md", ".xml", ".json", ".csv", ".html", ".htm", ".py", ".java", ".kt", ".c", ".cpp", ".h", ".sh", ".properties", ".ini", ".conf", ".yaml", ".yml", ".js", ".ts", ".css")
            .any { lower.endsWith(it) }
    }

    private fun openEditor(file: File) {
        startActivity(Intent(this, TextEditorActivity::class.java).putExtra(TextEditorActivity.EXTRA_PATH, file.absolutePath))
    }

    private fun toggleLayout() {
        dualMode = !dualMode
        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("dual", dualMode).apply()
        recreate()
    }

    private fun subOf(e: Entry): String = when {
        e.isDir -> getString(R.string.kind_folder)
        e.kindLabel.startsWith(getString(R.string.extracted_badge).substringBefore(" ·")) ->
            "${e.kindLabel} · ${ErrorCatalog.formatBytes(e.size)}"
        else -> buildString {
            append(if (e.kindLabel.isNotEmpty()) "${e.kindLabel} · " else "")
            append(ErrorCatalog.formatBytes(e.size))
            if (e.time > 0) append(" · ").append(dfmt.format(Date(e.time)))
        }
    }

    private inner class ItemAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val e = shown[position]
            return if (dualMode) buildDualCell(e) else buildRow(e)
        }
    }

    /** 单列行（QQ浏览器样式）。 */
    private fun buildRow(e: Entry): View {
        val sel = e.path != null && selection.containsKey(e.path)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (sel) UiTheme.chipBg() else UiTheme.card())
            setPadding(28, 22, 28, 22)
            isHapticFeedbackEnabled = false
            fitsSystemWindows = false
        }
        row.addView(buildIcon(e, dp(50), 24f, sel, inSelect))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(TextView(this).apply {
            text = e.name; textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        mid.addView(TextView(this).apply {
            text = subOf(e); textSize = 12f; setTextColor(UiTheme.textSecondary())
        })
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 22 })
        row.setOnClickListener { if (inSelect && e.path != null) toggleSelect(e) else onEntry(e) }
        row.setOnLongClickListener { if (e.path != null) toggleSelect(e) else showOps(e); true }
        return row
    }

    /** 图标区：有缩略图键时异步加载封面，否则显示 emoji。 */
    /** 图标区：封面常驻（多选选中也不消失），多选模式叠加右上角勾选角标。 */
    private fun buildIcon(e: Entry, sizePx: Int, emojiSize: Float, sel: Boolean = false, selecting: Boolean = false): View {
        val wrap = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            setBackgroundResource(R.drawable.archive_icon_bg)
        }
        val emoji = TextView(this).apply {
            text = e.icon
            textSize = emojiSize; gravity = Gravity.CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        wrap.addView(emoji)
        val img = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        wrap.addView(img)
        // 封面加载：优先 thumbKey（MediaStore），否则用 path（文件系统扫描的图片/视频）
        val thumbPath = e.thumbKey?.let { k ->
            // MediaStore 键 → 从 Entry.path 获取实际文件路径
            e.path
        } ?: e.path?.takeIf { Thumbs.hasCover(e.name) }
        if (thumbPath != null || e.thumbKey != null) {
            val key = e.thumbKey ?: e.path ?: e.name
            Thumbs.loadInto(this, key, thumbPath, img)
        }
        if (selecting && e.path != null) {
            val badge = TextView(this).apply {
                text = if (sel) "✓" else ""
                textSize = 12f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(if (sel) R.drawable.badge_selected else R.drawable.badge_normal)
                visibility = View.VISIBLE
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(18), dp(18), Gravity.BOTTOM or Gravity.END)
            }
            wrap.addView(badge)
        }
        return wrap
    }

    /** 双列格（MT 管理器样式）。 */
    private fun buildDualCell(e: Entry): View {
        val sel = e.path != null && selection.containsKey(e.path)
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.dual_cell_bg)
            if (sel) setBackgroundColor(UiTheme.chipBg())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isHapticFeedbackEnabled = false
        }
        cell.addView(buildIcon(e, dp(36), 20f, sel, inSelect))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(TextView(this).apply {
            text = e.name; textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        mid.addView(TextView(this).apply {
            text = subOf(e); textSize = 11f; setTextColor(UiTheme.textSecondary()); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        cell.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        cell.setOnClickListener { if (inSelect && e.path != null) toggleSelect(e) else onEntry(e) }
        cell.setOnLongClickListener { if (e.path != null) toggleSelect(e) else showOps(e); true }
        return cell
    }

    /** 打开条目：与首页最近文件共用统一分派器（FileOpener），行为完全一致。 */
    private fun onEntry(e: Entry) {
        FileOpener.open(this, e.name, e.path, e.uri, isDirHint = e.isDir)
    }

    private fun startExtract(src: File, dst: File, password: String? = null) {
        // 点击即入队+打开进度弹层，零预检零卡顿；加密在 Worker 内检测，失败后经 NEED_PASSWORD 重试
        val request = OneTimeWorkRequestBuilder<ExtractionWorker>()
            .setInputData(
                workDataOf(
                    ExtractionWorker.KEY_SRC_PATH to src.absolutePath,
                    ExtractionWorker.KEY_DST_PATH to dst.absolutePath,
                    ExtractionWorker.KEY_PASSWORD to password,
                ),
            )
            .build()
        WorkManager.getInstance(this).enqueue(request)
        startActivity(
            Intent(this, ProgressActivity::class.java)
                .putExtra(ProgressActivity.EXTRA_NAME, src.name)
                .putExtra(ProgressActivity.EXTRA_SRC, src.absolutePath)
                .putExtra(ProgressActivity.EXTRA_DST, dst.absolutePath),
        )
    }

    private fun startCompress(dir: File, format: CreateFormat) {
        val out = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "${dir.name}.${format.extension}",
        )
        val request = OneTimeWorkRequestBuilder<CompressWorker>()
            .setInputData(
                workDataOf(
                    CompressWorker.KEY_SRC_PATH to dir.absolutePath,
                    CompressWorker.KEY_OUT_PATH to out.absolutePath,
                    CompressWorker.KEY_FORMAT to format.name,
                ),
            )
            .build()
        WorkManager.getInstance(this).enqueue(request)
        ObjectGraph.lastAction = "compress"
        toast(getString(R.string.toast_compressing, out.name))
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_PATH = "path"
    }
}
