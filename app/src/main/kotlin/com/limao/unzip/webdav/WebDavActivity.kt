package com.limao.unzip.webdav

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.limao.unzip.R
import com.limao.unzip.engine.error.ErrorCatalog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WebDAV 网盘：连接信息持久化后**自动连接**（打开即列出目录，无需手动点连接），
 * 条目操作（下载/删除/上传/新建文件夹）均先经用户确认再执行。
 */
class WebDavActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var pathView: TextView
    private lateinit var formCard: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var uploadBtn: Button
    private var client: WebDavClient? = null
    private var currentPath = "/"
    private val dfmt = SimpleDateFormat("yyyy/M/d", Locale.getDefault())
    private val downloadDir by lazy {
        File(getExternalFilesDir(null), "webdav-inbox").apply { mkdirs() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F6F8.toInt())
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(24, 36, 24, 36)
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(this).apply {
            text = "‹"; textSize = 26f; setPadding(0, 0, 28, 0)
            setOnClickListener { finish() }
        })
        pathView = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(pathView)
        bar.addView(TextView(this).apply {
            text = "⚙"; textSize = 20f; setPadding(24, 0, 0, 0)
            setOnClickListener { showForm() }
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        statusView = TextView(this).apply {
            textSize = 12f; setTextColor(0xFF999999.toInt())
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(24, 8, 24, 12)
            visibility = View.GONE
        }
        root.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        formCard = buildForm()
        root.addView(formCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        uploadBtn = Button(this).apply {
            text = getString(R.string.webdav_upload_btn)
            textSize = 13f
            visibility = View.GONE
            setOnClickListener { showUploadDialog() }
        }
        root.addView(uploadBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        listView = ListView(this).apply { divider = null }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // 自动连接：已保存凭证直接使用（后台网盘打开即读，无需手动连接）
        val savedUrl = getSharedPreferences("webdav", MODE_PRIVATE).getString("url", null)
        if (savedUrl.isNullOrEmpty()) showForm() else autoConnect()
    }

    private fun buildForm(): LinearLayout {
        val prefs = getSharedPreferences("webdav", MODE_PRIVATE)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(24, 16, 24, 16)
            tag = "form"
        }
        val urlInput = EditText(this).apply {
            hint = getString(R.string.webdav_url)
            setText(prefs.getString("url", ""))
            maxLines = 1
            tag = "url"
        }
        val userInput = EditText(this).apply {
            hint = getString(R.string.webdav_user)
            setText(prefs.getString("user", ""))
            maxLines = 1
            tag = "user"
        }
        val passInput = EditText(this).apply {
            hint = getString(R.string.webdav_pass)
            setText(prefs.getString("pass", ""))
            maxLines = 1
            tag = "pass"
        }
        val connect = Button(this).apply {
            text = getString(R.string.webdav_connect); textSize = 14f
            setOnClickListener {
                val url = urlInput.text.toString().trim()
                if (url.isEmpty()) return@setOnClickListener
                getSharedPreferences("webdav", MODE_PRIVATE).edit()
                    .putString("url", url)
                    .putString("user", userInput.text.toString())
                    .putString("pass", passInput.text.toString())
                    .apply()
                autoConnect()
            }
        }
        form.addView(urlInput)
        form.addView(userInput)
        form.addView(passInput)
        form.addView(connect, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return form
    }

    private fun showForm() {
        formCard.visibility = View.VISIBLE
        val prefs = getSharedPreferences("webdav", MODE_PRIVATE)
        formCard.findViewWithTag<EditText>("url")?.setText(prefs.getString("url", ""))
        formCard.findViewWithTag<EditText>("user")?.setText(prefs.getString("user", ""))
        formCard.findViewWithTag<EditText>("pass")?.setText(prefs.getString("pass", ""))
    }

    /** 保存的凭证直接连接（无感读取后台网盘）。 */
    private fun autoConnect() {
        val url = getSharedPreferences("webdav", MODE_PRIVATE).getString("url", null) ?: return
        val user = getSharedPreferences("webdav", MODE_PRIVATE).getString("user", null)
        val pass = getSharedPreferences("webdav", MODE_PRIVATE).getString("pass", null)
        client = WebDavClient(url, user?.ifEmpty { null }, pass?.ifEmpty { null })
        currentPath = "/"
        formCard.visibility = View.GONE
        uploadBtn.visibility = View.VISIBLE
        browse()
    }

    private fun browse() {
        val c = client ?: return
        pathView.text = "WebDAV $currentPath"
        statusView.visibility = View.VISIBLE
        statusView.text = getString(R.string.preparing)
        listView.adapter = emptyAdapter()
        Thread {
            val list = runCatching { c.list(currentPath) }
                .getOrElse { t ->
                    android.util.Log.e("WebDavBrowse", "EXC path=$currentPath", t)
                    emptyList()
                }
            val failed = list.isEmpty()
            android.util.Log.e("WebDavBrowse", "RESULT failed=$failed size=${list.size} path=$currentPath")
            runOnUiThread {
                statusView.visibility = if (failed) View.VISIBLE else View.GONE
                if (!failed) {
                    statusView.text = ""
                    listView.adapter = buildListAdapter(c, list)
                }
            }
        }.start()
    }

    private fun emptyAdapter(): BaseAdapter = object : BaseAdapter() {
        override fun getCount() = 0
        override fun getItem(position: Int): Any? = null
        override fun getItemId(position: Int) = 0L
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = View(this@WebDavActivity)
    }

    private fun buildListAdapter(
        c: WebDavClient,
        list: List<WebDavClient.DavEntry>,
    ): BaseAdapter {
        val entries = list.sortedWith(
            compareByDescending<WebDavClient.DavEntry> { it.isDir }.thenBy { it.name.lowercase() },
        )
        val adapter = object : BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(position: Int): WebDavClient.DavEntry = entries[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val e = entries[position]
                val row = LinearLayout(this@WebDavActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(0xFFFFFFFF.toInt())
                    setPadding(28, 20, 28, 20)
                }
                row.addView(TextView(this@WebDavActivity).apply {
                    text = if (e.isDir) "📁" else "📄"
                    textSize = 20f; gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.archive_icon_bg)
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                })
                val mid = LinearLayout(this@WebDavActivity).apply { orientation = LinearLayout.VERTICAL }
                mid.addView(TextView(this@WebDavActivity).apply {
                    text = e.name; textSize = 14f; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                })
                mid.addView(TextView(this@WebDavActivity).apply {
                    text = if (e.isDir) "—" else "${ErrorCatalog.formatBytes(e.size)} · ${dfmt.format(Date(e.mtime))}"
                    textSize = 12f; setTextColor(0xFF999999.toInt())
                })
                row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 20 })
                return row
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            val e = adapter.getItem(position)
            if (e.isDir) {
                currentPath = if (currentPath.endsWith("/")) currentPath + e.name else "$currentPath/${e.name}"
                currentPath += "/"
                browse()
            } else {
                confirmDownload(e)
            }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val e = adapter.getItem(position)
            AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(arrayOf(getString(R.string.ops_delete), getString(R.string.webdav_upload_btn))) { _, which ->
                    when (which) {
                        0 -> AlertDialog.Builder(this)
                            .setMessage(getString(R.string.webdav_delete_confirm, e.name))
                            .setPositiveButton(getString(R.string.ops_delete)) { _, _ -> deleteEntry(e) }
                            .setNegativeButton(getString(R.string.btn_cancel), null)
                            .show()
                        else -> uploadTo(e.name)
                    }
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
            true
        }
        return adapter
    }

    private fun confirmDownload(e: WebDavClient.DavEntry) {
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setMessage("${ErrorCatalog.formatBytes(e.size)}\n${getString(R.string.webdav_download_started)}${downloadDir.absolutePath}")
            .setPositiveButton("OK") { _, _ -> downloadEntry(e) }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun downloadEntry(e: WebDavClient.DavEntry) {
        val c = client ?: return
        val out = File(downloadDir, e.name)
        Thread {
            val r = runCatching { c.download(currentPath + e.name, out) }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (r.isSuccess) getString(R.string.webdav_download_done) + out.name
                    else getString(R.string.op_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }.start()
    }

    private fun deleteEntry(e: WebDavClient.DavEntry) {
        val c = client ?: return
        Thread {
            val r = runCatching { c.delete(currentPath + e.name) }
            runOnUiThread {
                Toast.makeText(this, getString(if (r.isSuccess) R.string.deleted_ok else R.string.op_failed), Toast.LENGTH_SHORT).show()
                if (r.isSuccess) browse()
            }
        }.start()
    }

    private fun uploadTo(remoteName: String) {
        val files = downloadDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            toast(getString(R.string.webdav_no_local))
            return
        }
        val names = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.webdav_pick_local))
            .setItems(names) { _, which ->
                uploadFile(files[which], remoteName)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showUploadDialog() {
        val files = downloadDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            toast(getString(R.string.webdav_no_local))
            return
        }
        val names = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.webdav_pick_local))
            .setItems(names) { _, which ->
                uploadFile(files[which], files[which].name)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun uploadFile(local: File, remoteName: String) {
        val c = client ?: return
        Thread {
            val r = runCatching { c.upload(local, currentPath + remoteName) }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (r.isSuccess) getString(R.string.webdav_upload_done) + remoteName
                    else getString(R.string.op_failed),
                    Toast.LENGTH_LONG,
                ).show()
                if (r.isSuccess) browse()
            }
        }.start()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
