package com.limao.unzip.files

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageInfo
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.limao.unzip.R
import java.io.File

/**
 * 应用内 APK 安装器：
 *  - 解析安装包信息（图标/应用名/版本/包名/体积）全程应用内完成
 *  - 交由系统 PackageInstaller 执行（Android 强制的安全确认与进度 UI，非第三方应用）
 *  - 应用内轮询真实安装结果：成功显示"安装完成"+ 直接打开；失败给出原因
 *  - 缺"安装未知应用"授权时引导到系统设置页（一次性授权，之后不再出现）
 *  - ColorOS 对第三方 PackageInstaller 会话会被"安全守护"中止后重装且耗时数分钟，
 *    因此采用系统标准安装意图（ACTION_VIEW），安全守护同样拦截但流程由系统全程接管
 */
class ApkInstallActivity : AppCompatActivity() {

    private lateinit var file: File
    private var installing = false
    private var pkgName: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var watchdogRunning = false
    private var done = false
    private var launchTime = 0L

    private lateinit var iconView: ImageView
    private lateinit var nameView: TextView
    private lateinit var infoView: TextView
    private lateinit var stateView: TextView
    private lateinit var bar: ProgressBar
    private lateinit var installBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        file = File(path)
        initUi()
    }

    private fun initUi() {
        val path = file.absolutePath
        val night = com.limao.unzip.UiTheme.isNight()
        val bgRoot = if (night) 0xFF101010.toInt() else 0xFFF5F6F8.toInt()
        val bgBar = if (night) 0xE6222222.toInt() else 0xE6FFFFFF.toInt()
        val fgPrimary = if (night) 0xFFEEEEEE.toInt() else 0xFF1D1B20.toInt()
        val fgSecondary = if (night) 0xFFDDDDDD.toInt() else 0xFF49454F.toInt()
        val fgMuted = if (night) 0xFF999999.toInt() else 0xFF666666.toInt()
        val accent = if (night) 0xFF82AAFF.toInt() else 0xFF1296DB.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgRoot)
            fitsSystemWindows = true
        }
        val top = LinearLayout(this).apply {
            setBackgroundColor(bgBar); setPadding(24, 32, 24, 32); gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "‹"; textSize = 26f; setTextColor(fgSecondary); setPadding(0, 0, 28, 0)
            setOnClickListener { finish() }
        })
        top.addView(TextView(this).apply {
            text = getString(R.string.apk_install_title); textSize = 16f; setTextColor(fgPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(40), dp(28), dp(28))
        }
        iconView = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)) }
        body.addView(iconView)
        nameView = TextView(this).apply {
            textSize = 17f; setTextColor(fgPrimary); gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
        }
        body.addView(nameView)
        infoView = TextView(this).apply {
            textSize = 13f; setTextColor(fgMuted); gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }
        body.addView(infoView)
        stateView = TextView(this).apply {
            textSize = 13f; setTextColor(fgSecondary); gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }
        body.addView(stateView)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            max = 100
            visibility = View.GONE
        }
        body.addView(bar)
        installBtn = TextView(this).apply {
            text = getString(R.string.apk_install_btn); textSize = 15f
            setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
            setBackgroundColor(accent)
            setOnClickListener { startInstall() }
        }
        body.addView(installBtn)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        stateView.text = getString(R.string.apk_scanning, file.length() / 1024)
        Thread {
            val pm = packageManager
            val pi: PackageInfo? = runCatching { pm.getPackageArchiveInfo(path, 0) }.getOrNull()
            var label: String? = null
            var ver: String? = null
            var icon: Drawable? = null
            if (pi != null) {
                pi.applicationInfo?.sourceDir = path
                pi.applicationInfo?.publicSourceDir = path
                label = runCatching { pi.applicationInfo?.loadLabel(pm)?.toString() }.getOrNull()
                icon = runCatching { pi.applicationInfo?.loadIcon(pm) }.getOrNull()
                ver = pi.versionName ?: ""
                pkgName = pi.packageName
            }
            runOnUiThread {
                if (pi == null) {
                    nameView.text = file.name
                    stateView.text = getString(R.string.apk_unknown)
                    installBtn.visibility = View.GONE
                    return@runOnUiThread
                }
                icon?.let { iconView.setImageDrawable(it) }
                    ?: run {
                        val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                        bmp?.let { iconView.setImageBitmap(it) }
                    }
                nameView.text = label ?: file.name
                infoView.text = buildString {
                    append(getString(R.string.apk_ver, ver ?: "—"))
                    append(" · ")
                    append(fmtSize(file.length()))
                    pkgName?.let { append("\n$it") }
                }
                stateView.text = getString(R.string.apk_ready)
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // 从"安装未知应用"授权页返回：若正卡在授权提示则自动续装
        if (installing && stateView.text == getString(R.string.apk_need_perm)) {
            installing = false
            startInstall()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        done = true // 停轮询
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ---------- 安装 ----------

    private fun startInstall() {
        if (installing) return
        // 缺"安装未知应用"授权：引导去系统设置（一次性授权，之后不再弹出）
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            stateView.text = getString(R.string.apk_need_perm)
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.apk_install_title))
                .setMessage(getString(R.string.apk_need_perm))
                .setPositiveButton(getString(R.string.apk_grant)) { _, _ ->
                    runCatching {
                        startActivity(
                            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                                .setData(Uri.parse("package:$packageName")),
                        )
                    }.onFailure {
                        runCatching {
                            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                        }
                    }
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
            return
        }
        installing = true
        installBtn.visibility = View.GONE
        bar.visibility = View.VISIBLE
        bar.progress = 0
        stateView.text = getString(R.string.apk_installing)

        // 系统标准安装意图：系统 PackageInstaller 接管确认与安装（Android 强制安全 UI）
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val started = runCatching { startActivity(intent) }.isSuccess
        if (started) {
            launchTime = System.currentTimeMillis()
            startWatchdog()
        } else {
            showFailure(getString(R.string.apk_unknown))
        }
    }

    /** 结果看门狗：每 2s 查包是否已装上；10 分钟兜底超时 */
    private fun startWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        val pkg = pkgName
        val check = object : Runnable {
            override fun run() {
                if (done) return
                val installed = pkg != null && runCatching {
                    packageManager.getPackageInfo(pkg, 0); true
                }.getOrDefault(false)
                if (installed) {
                    onInstallDone(); return
                }
                if (System.currentTimeMillis() - launchTime > 600_000) {
                    watchdogRunning = false
                    showFailure("")
                    return
                }
                mainHandler.postDelayed(this, 2000)
            }
        }
        check.run()
    }

    private fun showFailure(msg: String) {
        installing = false
        bar.visibility = View.GONE
        installBtn.visibility = View.VISIBLE
        installBtn.text = getString(R.string.apk_install_btn)
        installBtn.setOnClickListener { startInstall() }
        stateView.text = getString(R.string.apk_failed) + if (msg.isNotEmpty()) "\n$msg" else ""
    }

    private fun onInstallDone() {
        if (done) return
        done = true
        installing = false
        bar.visibility = View.GONE
        stateView.text = getString(R.string.apk_done)
        Toast.makeText(this, R.string.apk_done, Toast.LENGTH_SHORT).show()
        // 提供"打开"：直接进入刚安装的应用
        val launch = pkgName?.let { packageManager.getLaunchIntentForPackage(it) }
        installBtn.visibility = View.VISIBLE
        installBtn.text = getString(R.string.apk_open_btn)
        installBtn.setOnClickListener {
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(launch) }
                    .onFailure { Toast.makeText(this, R.string.op_failed, Toast.LENGTH_SHORT).show() }
            } else Toast.makeText(this, R.string.op_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576f)
        bytes >= 1 shl 10 -> "%d KB".format(bytes / 1024)
        else -> "$bytes B"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PATH = "path"
    }
}
