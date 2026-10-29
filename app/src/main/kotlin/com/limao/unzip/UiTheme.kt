package com.limao.unzip

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/**
 * 主题偏好：日间 / 夜间 / 跟随系统。
 * 跟随系统由 AppCompatDelegate 监听 uiMode 配置变化，系统切换时前台 Activity 自动重建（即时响应）。
 */
object ThemeStore {
    const val PREF = "theme"
    const val KEY_MODE = "mode"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"
    const val MODE_SYSTEM = "system"

    fun saved(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_MODE, MODE_SYSTEM)
            ?: MODE_SYSTEM

    fun apply(ctx: Context) {
        val mode = saved(ctx)
        UiTheme.forcedDark = when (mode) {
            MODE_LIGHT -> false
            MODE_DARK -> true
            else -> null // 跟随系统：按当前配置取色
        }
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }

    /** 切换并立即生效（所有前台 AppCompat Activity 自动重建）。 */
    fun set(ctx: Context, mode: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply()
        apply(ctx)
    }
}

/** 进程级初始化：冷启动即应用已保存的主题。 */
class PolarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UiTheme.init(this)
        ThemeStore.apply(this)
    }
}

/**
 * 浅色页面调色板（主页/文件列表/WebDAV/进度框）。取值依据 forcedDark 优先，
 * 否则读 application 配置的夜间标志；Activity 重建后视图自动重取色。
 * 深色工具型页面（编辑器/图片/PDF/存储分析）固定深色，不走本表。
 */
object UiTheme {

    /** null=跟随系统配置；true/false=用户强制夜间/日间。由 ThemeStore 维护。 */
    @Volatile
    var forcedDark: Boolean? = null

    @Volatile
    private var appCtx: Context? = null

    /** PolarApp.onCreate 时注入（先于一切 Activity）。 */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun isNight(): Boolean {
        forcedDark?.let { return it }
        val cfg = appCtx?.resources?.configuration ?: return false
        return (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** 页面背景 */
    fun pageBg() = if (isNight()) 0xFF121417.toInt() else 0xFFF5F6F8.toInt()

    /** 卡片/工具栏/列表行背景 */
    fun card() = if (isNight()) 0xFF1E2126.toInt() else 0xFFFFFFFF.toInt()

    /** 次要文字 */
    fun textSecondary() = if (isNight()) 0xFF9AA3AC.toInt() else 0xFF999999.toInt()

    /** 强调说明文字 */
    fun textTertiary() = if (isNight()) 0xFF7E8790.toInt() else 0xFF666666.toInt()

    /** 主题蓝 */
    fun accent() = if (isNight()) 0xFF5FB2EF.toInt() else 0xFF1296DB.toInt()

    /** 选中底色（浅蓝 / 深蓝） */
    fun chipBg() = if (isNight()) 0xFF17324A.toInt() else 0xFFE3F0FC.toInt()
}
