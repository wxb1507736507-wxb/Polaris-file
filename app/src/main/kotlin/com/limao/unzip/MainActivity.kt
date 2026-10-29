package com.limao.unzip

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.limao.unzip.engine.error.ErrorCatalog
import com.limao.unzip.files.ArchiveScanner
import com.limao.unzip.files.ExtractHistory
import com.limao.unzip.files.FileListActivity

import com.limao.unzip.files.TextEditorActivity
import com.limao.unzip.files.StorageAnalysisActivity
import com.limao.unzip.webdav.WebDavActivity
import kotlinx.coroutines.launch
import java.io.File

/** 主页：QQ浏览器式文件管理器（底部 文件/我的 两标签）+ 语言切换。 */
class MainActivity : AppCompatActivity() {

    private var recentItems: List<RecentItem> = emptyList()

    private data class RecentItem(
        val name: String, val path: String?, val uri: android.net.Uri?,
        val size: Long, val time: Long, val isExtractedDir: Boolean,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ObjectGraph.init(this)
        if (!Environment.isExternalStorageManager()) {
            showPermissionGuide()
        } else {
            showHome()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Environment.isExternalStorageManager()) {
            if (!homeBuilt) showHome() else refreshRecent()
        }
    }

    private var homeBuilt = false

    // ---------- 权限引导 ----------
    private fun showPermissionGuide() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFF5F6F8.toInt())
            setPadding(48, 48, 48, 48)
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = "🎩"; textSize = 56f; gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 16)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.guide_body)
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        })
        root.addView(Button(this).apply {
            text = getString(R.string.guide_grant)
            setOnClickListener {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }
            }
        })
        setContentView(root)
    }

    // ---------- 主页骨架 ----------
    private var filesPage: View? = null
    private var minePage: View? = null
    private var tabViews: List<TextView> = emptyList()

    private fun showHome() {
        homeBuilt = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F6F8.toInt())
            fitsSystemWindows = true
        }
        val content = android.widget.FrameLayout(this)
        filesPage = buildFilesPage()
        minePage = buildMinePage()
        val match = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        content.addView(filesPage, match)
        content.addView(minePage, match)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(0, 10, 0, 16)
        }
        val tabs = listOf("📁" to getString(R.string.tab_files), "👤" to getString(R.string.tab_mine))
        tabViews = tabs.mapIndexed { i, (icon, label) ->
            TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(if (i == 0) 0xFF1296DB.toInt() else 0xFF999999.toInt())
                text = "$icon\n$label"
                setOnClickListener { showTab(i) }
            }
        }
        tabViews.forEach { bottom.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        showTab(0)
        refreshRecent()
    }

    private fun showTab(index: Int) {
        filesPage?.visibility = if (index == 0) View.VISIBLE else View.GONE
        minePage?.visibility = if (index == 1) View.VISIBLE else View.GONE
        tabViews.forEachIndexed { i, tv -> tv.setTextColor(if (i == index) 0xFF1296DB.toInt() else 0xFF999999.toInt()) }
    }

    /** 文件页：整页滚动（存储卡+宫格+最近文件一起滑，QQ浏览器式丝滑）。 */
    private var filesScroll: androidx.core.widget.NestedScrollView? = null

    private fun buildFilesPage(): View {
        val page = androidx.core.widget.NestedScrollView(this).apply {
            setBackgroundColor(0xFFF5F6F8.toInt())
            isFillViewport = true
        }
        filesScroll = page
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val bar = LinearLayout(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(24, 40, 24, 32)
        }
        bar.addView(TextView(this).apply {
            text = getString(R.string.tab_files)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        bar.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 12f
            setTextColor(0xFF1296DB.toInt())
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        content.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(buildStorageCard())
        content.addView(buildCategoryGrid())
        content.addView(TextView(this).apply {
            text = getString(R.string.recent_files)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(28, 24, 28, 8)
        })
        recentContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(recentContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        page.addView(content, android.view.ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return page
    }

    private var recentContainer: LinearLayout? = null

    /** 最近文件行（点击打开/进入，长按操作菜单；图片/视频显示封面缩略图）。 */
    private fun buildRecentRow(item: RecentItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(28, 20, 28, 20)
            isHapticFeedbackEnabled = false
        }
        // 图片/视频且有路径 → 加载封面缩略图；否则 Material 矢量图标
        val isMediaFile = !item.isExtractedDir && item.path != null &&
            com.limao.unzip.files.Thumbs.hasCover(item.name)
        if (isMediaFile) {
            val img = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            }
            row.addView(img)
            com.limao.unzip.files.Thumbs.loadInto(this, item.path!!, item.path, img)
        } else {
            row.addView(android.widget.ImageView(this).apply {
                setImageResource(
                    when {
                        item.isExtractedDir -> R.drawable.ic_folder
                        com.limao.unzip.files.ArchiveScanner.isArchive(item.name) -> R.drawable.ic_archive
                        else -> com.limao.unzip.files.FileItems.iconResFor(item.name)
                    },
                )
                setColorFilter(0xFF1296DB.toInt())
                setBackgroundResource(R.drawable.archive_icon_bg)
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            })
        }
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(TextView(this).apply {
            text = item.name; textSize = 14f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        mid.addView(TextView(this).apply {
            text = when {
                item.isExtractedDir -> getString(R.string.extracted_badge).substringBefore(" ·")
                item.size > 0 -> ErrorCatalog.formatBytes(item.size)
                else -> ""
            }
            textSize = 12f; setTextColor(0xFF999999.toInt())
        })
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 20 })
        row.setOnClickListener { openRecent(item) }
        row.setOnLongClickListener { showRecentOps(item); true }
        return row
    }

    private fun openRecent(item: RecentItem) {
        when {
            item.isExtractedDir -> startActivity(
                Intent(this, FileListActivity::class.java).apply {
                    putExtra(FileListActivity.EXTRA_MODE, "browser")
                    putExtra(FileListActivity.EXTRA_PATH, item.path)
                },
            )
            item.uri != null -> runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(item.uri, contentResolver.getType(item.uri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                )
            }
            item.path != null && com.limao.unzip.files.ArchiveScanner.isArchive(item.name) -> startActivity(
                Intent(this, FileListActivity::class.java).apply {
                    putExtra(FileListActivity.EXTRA_MODE, "archives")
                },
            )
            item.path != null && java.io.File(item.path).isFile -> {
                if (com.limao.unzip.files.FileItems.isTextName(item.name)) {
                    startActivity(Intent(this, TextEditorActivity::class.java).putExtra(TextEditorActivity.EXTRA_PATH, item.path))
                } else runCatching {
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", java.io.File(item.path))
                    startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                    )
                }
            }
            else -> startActivity(
                Intent(this, FileListActivity::class.java).apply {
                    putExtra(FileListActivity.EXTRA_MODE, "archives")
                },
            )
        }
    }

    /** 最近文件长按菜单：打开/重命名/删除/详情/复制路径。 */
    private fun showRecentOps(item: RecentItem) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add(getString(R.string.ops_open) to { openRecent(item) })
        if (item.path != null) {
            actions.add(getString(R.string.ops_rename) to {
                val src = File(item.path)
                val input = EditText(this).apply { setText(src.name); setSelectAllOnFocus(true) }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ops_rename))
                    .setView(input)
                    .setPositiveButton("OK") { _, _ ->
                        val newName = input.text.toString().trim()
                        val target = src.parentFile?.let { File(it, newName) }
                        if (newName.isNotEmpty() && target != null && !target.exists() && src.renameTo(target)) {
                            if (item.isExtractedDir) ExtractHistory.updateDst(this, src.absolutePath, target.absolutePath)
                            toast(getString(R.string.renamed_ok))
                            refreshRecent()
                        } else toast(getString(R.string.name_exists))
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            })
            actions.add(getString(R.string.ops_delete) to {
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.delete_confirm, item.name))
                    .setPositiveButton(getString(R.string.ops_delete)) { _, _ ->
                        Thread {
                            val f = File(item.path)
                            val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                            runOnUiThread {
                                toast(getString(if (ok) R.string.deleted_ok else R.string.op_failed))
                                if (ok) refreshRecent()
                            }
                        }.start()
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            })
            actions.add(getString(R.string.ops_copy_path) to {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("path", item.path))
                toast(getString(R.string.copied))
            })
        }
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** 我的页 */
    private fun buildMinePage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFFF5F6F8.toInt())
            setPadding(24, 60, 24, 24)
        }
        page.addView(TextView(this).apply {
            text = "🧭"; textSize = 52f; gravity = Gravity.CENTER; setPadding(0, 24, 0, 8)
        })
        page.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        page.addView(TextView(this).apply {
            text = getString(R.string.mine_subtitle)
            textSize = 13f
            setTextColor(0xFF999999.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 24)
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(28, 24, 28, 24)
        }
        val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        card.addView(TextView(this).apply { text = getString(R.string.mine_version); textSize = 14f; setPadding(0, 8, 0, 8) })
        card.addView(TextView(this).apply {
            text = getString(
                R.string.mine_perm,
                getString(if (Environment.isExternalStorageManager()) R.string.mine_perm_granted else R.string.mine_perm_denied),
            )
            textSize = 14f; setPadding(0, 8, 0, 8)
        })
        card.addView(TextView(this).apply {
            text = getString(R.string.mine_free, ErrorCatalog.formatBytes(stat.availableBytes))
            textSize = 14f; setPadding(0, 8, 0, 8)
        })
        card.addView(TextView(this).apply {
            text = getString(R.string.mine_privacy)
            textSize = 12f; setTextColor(0xFF999999.toInt()); setPadding(0, 12, 0, 0)
        })
        page.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val langCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(28, 24, 28, 24)
        }
        langCard.addView(TextView(this).apply {
            text = getString(R.string.mine_lang)
            textSize = 14f
            setPadding(0, 0, 0, 12)
        })
        val langRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("中文" to "zh-CN", "English" to "en").forEach { (label, tag) ->
            langRow.addView(Button(this).apply {
                text = label; textSize = 13f
                setOnClickListener {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        langCard.addView(langRow)
        page.addView(langCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        return page
    }

    /** 存储卡 */
    private fun buildStorageCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(28, 28, 28, 20)
        }
        val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        val total = stat.totalBytes
        val avail = stat.availableBytes
        val used = total - avail
        val pct = if (total > 0) (used * 100 / total).toInt() else 0
        card.addView(TextView(this).apply {
            text = getString(R.string.storage_title)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = getString(R.string.used_of, ErrorCatalog.formatBytes(used), ErrorCatalog.formatBytes(total))
            textSize = 12f
            setTextColor(0xFF999999.toInt())
            setPadding(0, 4, 0, 8)
        })
        val prog = LinearProgressIndicator(this).apply {
            max = 100
            setProgress(pct, true)
            trackCornerRadius = 6
        }
        card.addView(prog, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)).apply { topMargin = dp(4) })
        card.addView(TextView(this).apply {
            text = "$pct%"
            textSize = 12f
            setTextColor(0xFF1296DB.toInt())
            setPadding(0, 8, 0, 0)
        })
        return card
    }

    /** 分类宫格 */
    private val countViews = HashMap<String, TextView>()

    private fun buildCategoryGrid(): View {
        val grid = GridLayout(this).apply {
            columnCount = 4
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(12, 24, 12, 24)
        }
        val cats = listOf(
            R.drawable.ic_archive to getString(R.string.cat_archives) to "archives",
            R.drawable.ic_get_app to getString(R.string.cat_download) to "download",
            R.drawable.ic_image to getString(R.string.cat_images) to "images",
            R.drawable.ic_videocam to getString(R.string.cat_video) to "video",
            R.drawable.ic_audiotrack to getString(R.string.cat_audio) to "audio",
            R.drawable.ic_description to getString(R.string.cat_docs) to "docs",
            R.drawable.ic_android_apk to getString(R.string.cat_apk) to "apk",
            R.drawable.ic_folder_open to getString(R.string.cat_all) to "browser",
            R.drawable.ic_memory to getString(R.string.cat_analysis) to "storage",
            R.drawable.ic_history to getString(R.string.cat_recent) to "recent",
            R.drawable.ic_cloud to getString(R.string.cat_webdav) to "webdav",
        )
        for ((pair, key) in cats) {
            val (iconRes, name) = pair
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, 20, 0, 12)
            }
            cell.addView(android.widget.ImageView(this).apply {
                setImageResource(iconRes)
                setColorFilter(0xFF1296DB.toInt())
                setBackgroundResource(R.drawable.archive_icon_bg)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { gravity = Gravity.CENTER }
            })
            cell.addView(TextView(this).apply {
                text = name; textSize = 13f; setPadding(0, 8, 0, 0); gravity = Gravity.CENTER
            })
            val cnt = TextView(this).apply {
                text = ""; textSize = 11f; setTextColor(0xFF999999.toInt()); gravity = Gravity.CENTER
            }
            countViews[key] = cnt
            cell.addView(cnt)
            cell.setOnClickListener { onCategory(key) }
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
            )
            lp.width = 0
            grid.addView(cell, lp)
        }
        loadCategoryCounts()
        return grid
    }

    private fun loadCategoryCounts() {
        Thread {
            val counts = HashMap<String, Int>()
            runCatching { counts["archives"] = ArchiveScanner.scanRoots(this).size }
            runCatching {
                counts["download"] = Environment
                    .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    .listFiles()?.count { it.isFile } ?: 0
            }
            runCatching { counts.putAll(com.limao.unzip.files.FileItems.counts(this)) }
            runOnUiThread {
                for ((k, v) in counts) countViews[k]?.text = v.toString()
            }
        }.start()
    }

    private fun onCategory(key: String) {
        when (key) {
            "storage" -> startActivity(Intent(this, StorageAnalysisActivity::class.java))
            "webdav" -> startActivity(Intent(this, WebDavActivity::class.java))
            "recent" -> startActivity(Intent(this, FileListActivity::class.java).apply {
                putExtra(FileListActivity.EXTRA_MODE, "recent")
            })
            else -> startActivity(
                Intent(this, FileListActivity::class.java).apply {
                    putExtra(FileListActivity.EXTRA_MODE, key)
                    if (key == "browser") {
                        putExtra(FileListActivity.EXTRA_PATH, Environment.getExternalStorageDirectory().absolutePath)
                    }
                },
            )
        }
    }

    /** 最近文件 = 设备全部类型的最近文件（MediaStore）+ 最近解压文件夹 + 最近压缩包（时间倒序）。 */
    private fun refreshRecent() {
        Thread {
            val merged = ArrayList<RecentItem>(12)
            runCatching {
                ExtractHistory.all(this).take(2).forEach { (_, dst, time) ->
                    merged.add(RecentItem(File(dst).name, dst, null, 0L, time, true))
                }
            }
            runCatching {
                com.limao.unzip.files.FileItems.recentFs(6).forEach { e ->
                    if (merged.size < 12) merged.add(RecentItem(e.name, e.path, null, e.size, e.time, false))
                }
            }
            val seen = HashSet<String>()
            val top = merged.sortedByDescending { it.time }
                .filter { seen.add(it.name + ":" + (it.path ?: it.uri.toString())) }
                .take(4)
            runOnUiThread {
                recentItems = top
                renderRecent()
            }
        }.start()
    }

    private fun renderRecent() {
        val container = recentContainer ?: return
        container.removeAllViews()
        if (recentItems.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.list_none)
                textSize = 13f; setTextColor(0xFF999999.toInt())
                setPadding(28, 16, 28, 16)
            })
            return
        }
        recentItems.forEach { container.addView(buildRecentRow(it)) }
        // 动态添加后强制 NestedScrollView 重算可滚动区域
        filesScroll?.requestLayout()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
