package com.limao.unzip

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.limao.unzip.engine.api.CreateFormat
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

    companion object {
        /** 当前标签（0=文件 1=工具 2=设置），进程内静态：主题/语言切换导致 Activity 重建后保持。 */
        @Volatile
        var lastTab = 0
    }

    // ---------- 权限引导 ----------
    private fun showPermissionGuide() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(UiTheme.pageBg())
            setPadding(48, 48, 48, 48)
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = "⭐"; textSize = 56f; gravity = Gravity.CENTER
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
            setTextColor(UiTheme.textTertiary())
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
    private var toolsPage: View? = null
    private var tabViews: List<TextView> = emptyList()

    private fun showHome() {
        homeBuilt = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.pageBg())
            fitsSystemWindows = true
        }
        val content = android.widget.FrameLayout(this)
        filesPage = buildFilesPage()
        toolsPage = buildToolsPage()
        minePage = buildMinePage()
        val match = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        content.addView(filesPage, match)
        content.addView(toolsPage, match)
        content.addView(minePage, match)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(this).apply {
            setBackgroundColor(UiTheme.card())
            setPadding(0, 10, 0, 16)
        }
        val tabs = listOf(
            "📁" to getString(R.string.tab_files),
            "🧰" to getString(R.string.tab_tools),
            "🛠️" to getString(R.string.tab_mine),
        )
        tabViews = tabs.mapIndexed { i, (icon, label) ->
            TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(if (i == 0) UiTheme.accent() else UiTheme.textSecondary())
                text = "$icon\n$label"
                setOnClickListener { showTab(i) }
            }
        }
        tabViews.forEach { bottom.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        showTab(lastTab)
        refreshRecent()
    }

    private fun showTab(index: Int) {
        lastTab = index
        filesPage?.visibility = if (index == 0) View.VISIBLE else View.GONE
        toolsPage?.visibility = if (index == 1) View.VISIBLE else View.GONE
        minePage?.visibility = if (index == 2) View.VISIBLE else View.GONE
        tabViews.forEachIndexed { i, tv -> tv.setTextColor(if (i == index) UiTheme.accent() else UiTheme.textSecondary()) }
    }

    /** 文件页：整页滚动（存储卡+宫格+最近文件一起滑，QQ浏览器式丝滑）。 */
    private var filesScroll: androidx.core.widget.NestedScrollView? = null

    private fun buildFilesPage(): View {
        val page = androidx.core.widget.NestedScrollView(this).apply {
            setBackgroundColor(UiTheme.pageBg())
            isFillViewport = true
        }
        filesScroll = page
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val bar = LinearLayout(this).apply {
            setBackgroundColor(UiTheme.card())
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
            setTextColor(UiTheme.accent())
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
            setBackgroundColor(UiTheme.card())
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
                setColorFilter(UiTheme.accent())
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
            textSize = 12f; setTextColor(UiTheme.textSecondary())
        })
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 20 })
        row.setOnClickListener { openRecent(item) }
        row.setOnLongClickListener { showRecentOps(item); true }
        return row
    }

    /** 打开最近文件：与分类页共用统一分派器（FileOpener），行为完全一致。 */
    private fun openRecent(item: RecentItem) {
        com.limao.unzip.files.FileOpener.open(this, item.name, item.path, item.uri, isDirHint = item.isExtractedDir)
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

    /** 工具页：解压与新建压缩入口（M3 卡片）。 */
    private fun buildToolsPage(): View {
        val scroll = androidx.core.widget.NestedScrollView(this).apply {
            setBackgroundColor(UiTheme.pageBg())
            isFillViewport = true
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }
        scroll.addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        page.addView(TextView(this).apply {
            text = getString(R.string.tab_tools)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(8, 8, 8, 4)
        })
        page.addView(TextView(this).apply {
            text = getString(R.string.tools_subtitle)
            textSize = 12f
            setTextColor(UiTheme.textSecondary())
            setPadding(8, 0, 8, 8)
        })

        // ---- 解压 ----
        page.addView(sectionLabel(getString(R.string.tools_section_extract)))
        page.addView(m3Card { card ->
            card.addView(settingsRow(
                "📦", getString(R.string.tools_browse_archives), getString(R.string.tools_browse_archives_desc),
            ) {
                startActivity(
                    Intent(this@MainActivity, com.limao.unzip.files.FileListActivity::class.java)
                        .putExtra(com.limao.unzip.files.FileListActivity.EXTRA_MODE, "archives"),
                )
            })
        })

        // ---- 压缩 ----
        page.addView(sectionLabel(getString(R.string.tools_section_compress)))
        page.addView(m3Card { card ->
            card.addView(settingsRow(
                "📂", getString(R.string.tools_compress_folder), getString(R.string.tools_compress_folder_desc),
            ) { showFolderPicker { dir -> askCompressFormat(dir) } })
            card.addView(divider())
            card.addView(settingsRow(
                "🗂️", getString(R.string.tools_compress_multi), getString(R.string.tools_compress_multi_desc),
            ) {
                startActivity(
                    Intent(this@MainActivity, com.limao.unzip.files.FileListActivity::class.java).apply {
                        putExtra(com.limao.unzip.files.FileListActivity.EXTRA_MODE, "browser")
                        putExtra(com.limao.unzip.files.FileListActivity.EXTRA_PATH, Environment.getExternalStorageDirectory().absolutePath)
                    },
                )
            })
        })
        return scroll
    }

    /** 选择压缩格式后入队后台压缩（产物固定落在 Download/）。 */
    private fun askCompressFormat(dir: File) {
        val formats = listOf(
            CreateFormat.ZIP to "ZIP",
            CreateFormat.SEVEN_Z to "7Z",
            CreateFormat.TAR_GZ to "TAR.GZ",
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.tools_pick_format, dir.name))
            .setItems(formats.map { it.second }.toTypedArray()) { _, which ->
                val out = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "${dir.name}.${formats[which].first.extension}",
                )
                val request = androidx.work.OneTimeWorkRequestBuilder<com.limao.unzip.worker.CompressWorker>()
                    .setInputData(
                        androidx.work.workDataOf(
                            com.limao.unzip.worker.CompressWorker.KEY_SRC_PATH to dir.absolutePath,
                            com.limao.unzip.worker.CompressWorker.KEY_OUT_PATH to out.absolutePath,
                            com.limao.unzip.worker.CompressWorker.KEY_FORMAT to formats[which].first.name,
                        ),
                    )
                    .build()
                androidx.work.WorkManager.getInstance(this).enqueue(request)
                toast(getString(R.string.toast_compressing, out.name))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** 轻量目录选择器：目录列表 + 上一级 + 选中当前目录。 */
    private fun showFolderPicker(onPick: (File) -> Unit) {
        var cur = Environment.getExternalStorageDirectory()
        val titleView = TextView(this).apply {
            textSize = 13f
            setTextColor(UiTheme.textSecondary())
            setPadding(dp(24), dp(18), dp(24), dp(8))
        }
        val list = ListView(this).apply { dividerHeight = 0 }
        fun refresh() {
            titleView.text = getString(R.string.tools_pick_folder) + "\n" + cur.absolutePath
            val dirs = runCatching {
                cur.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedBy { it.name.lowercase() }
            }.getOrNull() ?: emptyList()
            list.adapter = object : android.widget.BaseAdapter() {
                override fun getCount(): Int = dirs.size + 1
                override fun getItem(position: Int): Any? = if (position == 0) null else dirs[position - 1]
                override fun getItemId(position: Int): Long = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                        textSize = 15f
                        setPadding(dp(24), dp(14), dp(24), dp(14))
                    }
                    tv.text = if (position == 0) "↩︎  .." else "📁 " + dirs[position - 1].name
                    return tv
                }
            }
            list.setOnItemClickListener { _, _, position, _ ->
                cur = if (position == 0) (cur.parentFile ?: cur) else dirs[position - 1]
                refresh()
            }
        }
        refresh()
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(titleView)
        container.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)))
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(container)
            .setPositiveButton(getString(R.string.tools_pick_this)) { _, _ -> onPick(cur) }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** 设置页（M3 仪表盘）：存储概览 + 通用偏好（外观/语言）+ 关于。 */
    private fun buildMinePage(): View {
        val scroll = androidx.core.widget.NestedScrollView(this).apply {
            setBackgroundColor(UiTheme.pageBg())
            isFillViewport = true
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }
        scroll.addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        page.addView(TextView(this).apply {
            text = getString(R.string.tab_mine)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(8, 8, 8, 4)
        })
        page.addView(TextView(this).apply {
            text = getString(R.string.mine_subtitle)
            textSize = 12f
            setTextColor(UiTheme.textSecondary())
            setPadding(8, 0, 8, 8)
        })

        // ---- 存储概览 ----
        page.addView(sectionLabel(getString(R.string.settings_section_storage)))
        page.addView(m3Card { card ->
            val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            val total = stat.totalBytes
            val avail = stat.availableBytes
            val used = total - avail
            val pct = if (total > 0) (used * 100 / total).toInt() else 0
            card.addView(TextView(this@MainActivity).apply {
                text = getString(R.string.storage_title)
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(LinearProgressIndicator(this@MainActivity).apply {
                max = 100
                setProgress(pct, true)
                trackCornerRadius = dp(4)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)).apply { topMargin = dp(14) })
            card.addView(TextView(this@MainActivity).apply {
                text = "$pct% · ${getString(R.string.used_of, ErrorCatalog.formatBytes(used), ErrorCatalog.formatBytes(total))}"
                textSize = 12f
                setTextColor(UiTheme.textSecondary())
                setPadding(0, dp(10), 0, 0)
            })
            card.addView(divider())
            card.addView(settingsRow("💾", getString(R.string.mine_free, ErrorCatalog.formatBytes(stat.availableBytes)), "") { })
        })

        // ---- 通用 ----
        page.addView(sectionLabel(getString(R.string.settings_section_general)))
        page.addView(m3Card { card ->
            card.addView(TextView(this@MainActivity).apply {
                text = getString(R.string.mine_theme)
                textSize = 15f
                setPadding(0, dp(4), 0, dp(10))
            })
            val savedMode = ThemeStore.saved(this@MainActivity)
            val themeModes = listOf(
                getString(R.string.theme_light) to ThemeStore.MODE_LIGHT,
                getString(R.string.theme_dark) to ThemeStore.MODE_DARK,
                getString(R.string.theme_system) to ThemeStore.MODE_SYSTEM,
            )
            card.addView(segmentedGroup(themeModes, savedMode) { ThemeStore.set(applicationContext, it) })
            card.addView(TextView(this@MainActivity).apply {
                text = getString(R.string.mine_lang)
                textSize = 15f
                setPadding(0, dp(18), 0, dp(10))
            })
            val langTags = listOf("中文" to "zh-CN", "English" to "en")
            val currentTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            val checked = langTags.firstOrNull { currentTag.startsWith(it.second) }?.second ?: "zh-CN"
            card.addView(segmentedGroup(langTags, checked) { tag ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            })
        })

        // ---- 关于 ----
        page.addView(sectionLabel(getString(R.string.settings_section_about)))
        page.addView(m3Card { card ->
            val ver = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: "?"
            card.addView(settingsRow("ℹ️", getString(R.string.settings_version), ver) { })
            card.addView(divider())
            val granted = Environment.isExternalStorageManager()
            card.addView(settingsRow(
                if (granted) "🔓" else "🔒",
                getString(R.string.settings_permission),
                getString(if (granted) R.string.mine_perm_granted else R.string.mine_perm_denied),
            ) {
                if (!Environment.isExternalStorageManager()) {
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
            card.addView(divider())
            card.addView(settingsRow("🛡️", getString(R.string.settings_privacy), getString(R.string.settings_privacy_desc)) { })
        })
        return scroll
    }

    /** M3 卡片：圆角 20dp、无阴影、主题表面色。 */
    private fun m3Card(content: (LinearLayout) -> Unit): com.google.android.material.card.MaterialCardView {
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(UiTheme.card())
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        content(inner)
        card.addView(inner)
        return card
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(UiTheme.textSecondary())
        setPadding(8, dp(22), 8, dp(8))
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(if (UiTheme.isNight()) 0xFF2A2F36.toInt() else 0xFFE9EBEE.toInt())
    }.also { v ->
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(14)
            bottomMargin = dp(2)
        }
    }

    /** 设置行：图标 + 标题 + 右对齐值（onClick 为空则静态展示）。 */
    private fun settingsRow(icon: String, title: String, value: String, onClick: (() -> Unit)? = null): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            if (onClick != null) {
                val out = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                setBackgroundResource(out.resourceId)
                setOnClickListener { onClick() }
            }
            addView(TextView(this@MainActivity).apply {
                text = icon; textSize = 18f; setPadding(0, 0, dp(14), 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = title; textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (value.isNotEmpty()) addView(TextView(this@MainActivity).apply {
                text = value
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(UiTheme.textSecondary())
            })
        }

    /** M3 分段按钮组（单选）。 */
    private fun segmentedGroup(options: List<Pair<String, String>>, checkedValue: String, onSelect: (String) -> Unit): com.google.android.material.button.MaterialButtonToggleGroup {
        val group = com.google.android.material.button.MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            setSelectionRequired(true)
        }
        options.forEach { (label, value) ->
            val btn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                textSize = 12f
                isCheckable = true
                isAllCaps = false
                id = View.generateViewId()
                insetTop = 0; insetBottom = 0
                minWidth = 0
                setPadding(0, 0, 0, 0)
            }
            group.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (value == checkedValue) group.check(btn.id)
            btn.setOnClickListener { if (group.checkedButtonId == btn.id) onSelect(value) }
        }
        return group
    }

    /** 存储卡 */
    private fun buildStorageCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.card())
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
            setTextColor(UiTheme.textSecondary())
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
            setTextColor(UiTheme.accent())
            setPadding(0, 8, 0, 0)
        })
        return card
    }

    /** 分类宫格 */
    private val countViews = HashMap<String, TextView>()

    private fun buildCategoryGrid(): View {
        val grid = GridLayout(this).apply {
            columnCount = 4
            setBackgroundColor(UiTheme.card())
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
                setColorFilter(UiTheme.accent())
                setBackgroundResource(R.drawable.archive_icon_bg)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { gravity = Gravity.CENTER }
            })
            cell.addView(TextView(this).apply {
                text = name; textSize = 13f; setPadding(0, 8, 0, 0); gravity = Gravity.CENTER
            })
            val cnt = TextView(this).apply {
                text = ""; textSize = 11f; setTextColor(UiTheme.textSecondary()); gravity = Gravity.CENTER
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

    /** 最近文件：首页与「最近文件」分类共用同一数据源（FileItems.recentMixed），保证两处显示一致。 */
    private fun refreshRecent() {
        Thread {
            val top = com.limao.unzip.files.FileItems.recentMixed(this)
            runOnUiThread {
                recentItems = top.map { e ->
                    RecentItem(e.name, e.path, e.uri, e.size, e.time, e.isDir)
                }
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
                textSize = 13f; setTextColor(UiTheme.textSecondary())
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
