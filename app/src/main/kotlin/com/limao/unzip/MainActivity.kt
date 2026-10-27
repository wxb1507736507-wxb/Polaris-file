package com.limao.unzip

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
import com.limao.unzip.files.StorageAnalysisActivity
import kotlinx.coroutines.launch
import java.io.File

/** 主页：QQ浏览器式文件管理器（底部 文件/我的 两标签）+ 语言切换。 */
class MainActivity : AppCompatActivity() {

    private var recentItems: List<RecentItem> = emptyList()
    private var recentAdapter: RecentAdapter? = null

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

    /** 文件页 */
    private fun buildFilesPage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F6F8.toInt())
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
        page.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        page.addView(buildStorageCard())
        page.addView(buildCategoryGrid())
        page.addView(TextView(this).apply {
            text = getString(R.string.recent_files)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(28, 24, 28, 8)
        })
        val list = ListView(this).apply { divider = null }
        val ra = RecentAdapter()
        list.adapter = ra
        recentAdapter = ra
        page.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return page
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
            "📦" to getString(R.string.cat_archives) to "archives",
            "📥" to getString(R.string.cat_download) to "download",
            "🖼" to getString(R.string.cat_images) to "images",
            "🎬" to getString(R.string.cat_video) to "video",
            "🎵" to getString(R.string.cat_audio) to "audio",
            "📄" to getString(R.string.cat_docs) to "docs",
            "🤖" to getString(R.string.cat_apk) to "apk",
            "🗂" to getString(R.string.cat_all) to "browser",
            "💾" to getString(R.string.cat_analysis) to "storage",
            "🔍" to getString(R.string.cat_recent) to "recent",
        )
        for ((pair, key) in cats) {
            val (emoji, name) = pair
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, 20, 0, 12)
            }
            cell.addView(TextView(this).apply {
                text = emoji; textSize = 26f; gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.archive_icon_bg)
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
            runCatching { counts["archives"] = ArchiveScanner.scanRoots().size }
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
                ArchiveScanner.scanRoots().take(3).forEach { a ->
                    merged.add(RecentItem(a.name, a.file.absolutePath, null, a.size, a.lastModified, false))
                }
            }
            runCatching {
                com.limao.unzip.files.FileItems.recentAll(this, 10).forEach { e ->
                    if (merged.size < 12) merged.add(RecentItem(e.name, e.path, e.uri, e.size, e.time, false))
                }
            }
            val seen = HashSet<String>()
            val top = merged.sortedByDescending { it.time }
                .filter { seen.add(it.name + ":" + (it.path ?: it.uri.toString())) }
                .take(4)
            runOnUiThread {
                recentItems = top
                recentAdapter?.notifyDataSetChanged()
            }
        }.start()
    }

    private inner class RecentAdapter : BaseAdapter() {
        override fun getCount() = recentItems.size
        override fun getItem(position: Int) = recentItems[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = recentItems[position]
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0xFFFFFFFF.toInt())
                setPadding(28, 20, 28, 20)
            }
            row.addView(TextView(this@MainActivity).apply {
                text = if (item.isExtractedDir) "📂" else "📦"
                textSize = 22f; gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.archive_icon_bg)
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            })
            val mid = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            mid.addView(TextView(this@MainActivity).apply {
                text = item.name; textSize = 14f; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            mid.addView(TextView(this@MainActivity).apply {
                text = when {
                    item.isExtractedDir -> getString(R.string.extracted_badge).substringBefore(" ·")
                    item.size > 0 -> ErrorCatalog.formatBytes(item.size)
                    else -> ""
                }
                textSize = 12f; setTextColor(0xFF999999.toInt())
            })
            row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 20 })
            row.setOnClickListener {
                when {
                    item.isExtractedDir -> startActivity(
                        Intent(this@MainActivity, FileListActivity::class.java).apply {
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
                    else -> startActivity(
                        Intent(this@MainActivity, FileListActivity::class.java).apply {
                            putExtra(FileListActivity.EXTRA_MODE, "archives")
                        },
                    )
                }
            }
            return row
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
