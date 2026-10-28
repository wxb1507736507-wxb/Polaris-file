package com.limao.unzip.webdav

import android.os.Bundle
import android.view.Gravity
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

/** WebDAV 网盘：连接服务器 → 浏览目录 → 下载文件到 Download/PolarisWebDav。 */
class WebDavActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var pathView: TextView
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
            text = getString(R.string.cat_webdav)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(pathView)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val prefs = getSharedPreferences("webdav", MODE_PRIVATE)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(24, 16, 24, 16)
        }
        val urlInput = EditText(this).apply {
            hint = getString(R.string.webdav_url)
            setText(prefs.getString("url", ""))
            maxLines = 1
        }
        val userInput = EditText(this).apply {
            hint = getString(R.string.webdav_user)
            setText(prefs.getString("user", ""))
            maxLines = 1
        }
        val passInput = EditText(this).apply {
            hint = getString(R.string.webdav_pass)
            setText(prefs.getString("pass", ""))
            maxLines = 1
        }
        val connect = Button(this).apply { text = getString(R.string.webdav_connect); textSize = 14f }
        form.addView(urlInput)
        form.addView(userInput)
        form.addView(passInput)
        form.addView(connect, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(form, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        listView = ListView(this).apply { divider = null }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        connect.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            prefs.edit()
                .putString("url", url)
                .putString("user", userInput.text.toString())
                .putString("pass", passInput.text.toString())
                .apply()
            client = WebDavClient(url, userInput.text.toString().ifEmpty { null }, passInput.text.toString().ifEmpty { null })
            currentPath = "/"
            browse()
        }
    }

    private fun browse() {
        val c = client ?: return
        pathView.text = "WebDAV $currentPath"
        listView.adapter = object : BaseAdapter() {
            private var entries: List<WebDavClient.DavEntry> = emptyList()
            init {
                Thread {
                    val list = runCatching { c.list(currentPath) }
                        .getOrElse { emptyList() }
                    runOnUiThread {
                        if (list.isEmpty()) {
                            Toast.makeText(this@WebDavActivity, getString(R.string.webdav_list_error), Toast.LENGTH_LONG).show()
                        }
                        entries = list.sortedWith(compareByDescending<WebDavClient.DavEntry> { it.isDir }.thenBy { it.name.lowercase() })
                        notifyDataSetChanged()
                    }
                }.start()
            }
            override fun getCount() = entries.size
            override fun getItem(position: Int) = entries[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
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
                    layoutParams = LinearLayout.LayoutParams(dp2(44), dp2(44))
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
            private fun dp2(v: Int): Int = (v * resources.displayMetrics.density).toInt()
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            val adapter = listView.adapter as BaseAdapter
            val e = adapter.getItem(position) as WebDavClient.DavEntry
            if (e.isDir) {
                currentPath = if (currentPath.endsWith("/")) currentPath + e.name else "$currentPath/${e.name}"
                currentPath += "/"
                browse()
            } else {
                confirmDownload(e)
            }
        }
    }

    private fun confirmDownload(e: WebDavClient.DavEntry) {
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setMessage("${ErrorCatalog.formatBytes(e.size)}\n${getString(R.string.webdav_download_started)}${downloadDir.absolutePath}")
            .setPositiveButton("OK") { _, _ ->
                val c = client ?: return@setPositiveButton
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
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
