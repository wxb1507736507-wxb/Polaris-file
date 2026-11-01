package com.limao.unzip.files

import android.content.res.Configuration
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.limao.unzip.R
import com.limao.unzip.files.player.AudioInfo
import com.limao.unzip.files.player.AudioInfoLiveData
import com.limao.unzip.files.player.Stateful
import com.limao.unzip.files.player.use
import java.io.File
import java.util.Locale

/**
 * 统一播放器（对标设备自带播放器的样式与功能）：
 *  - 主引擎 Media3 ExoPlayer + PlayerView 标准控件：
 *      视频 mp4/mov/m4v/mkv/webm/3gp/ts/flv —— 画面比例自动适配（内容完整）、解码渲染跟随媒体原生帧率（无帧率上限）；
 *      音频 mp3/wav/flac/ogg/m4a/aac/opus —— 解码直出 PCM（不重编码、无 DSP，即文件数据无损播放），
 *      时间/进度条/播放暂停由 PlayerView 内置（M3E 风格图标），横屏全屏且不中断播放。
 *  - 回退引擎平台 MediaPlayer：avi/wma/wmv/midi 按设备编解码器尽力播放。
 */
class PlayerActivity : AppCompatActivity() {

    private var exo: ExoPlayer? = null
    private var mp: MediaPlayer? = null
    private var mpPrepared = false
    private var playbackStarted = false
    private var fallbackTried = false
    private var usingFallback = false
    private var isVideo = false
    private lateinit var file: File

    // 回退引擎的简易控件
    private var mpSeek: SeekBar? = null
    private var mpCur: TextView? = null
    private var mpDur: TextView? = null
    private var mpBtn: TextView? = null
    private var mpSurface: SurfaceView? = null
    private val ui = Handler(Looper.getMainLooper())
    private var mpTick: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        file = File(path)
        isVideo = isVideoExt(file.name)
        usingFallback = MEDIAPLAYER_ONLY.contains(ext())

        if (usingFallback) {
            buildFallbackUi()
            startMediaPlayer()
        } else {
            setContentView(R.layout.activity_player)
            bindHeader()
            startExo()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转不重建（manifest configChanges），仅切换全屏/分栏布局并重新挂接播放器
        if (!usingFallback && exo != null) rebindExoUi()
    }

    // ---------- Exo 主路径 ----------

    private fun rebindExoUi() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_player)
        bindHeader()
        // 横屏：标题栏悬浮、音频文字隐藏（全屏画面，与设备播放器一致）
        findViewById<View>(R.id.header)?.let {
            it.setBackgroundColor(if (land) 0x66000000.toInt() else 0xFF2B2B2B.toInt())
        }
        audioViews().forEach { it.visibility = if (!isVideo && !land) View.VISIBLE else View.GONE }
        findViewById<PlayerView>(R.id.player_view)?.player = exo
    }

    private fun bindHeader() {
        findViewById<TextView>(R.id.title)?.text = file.name
        findViewById<View>(R.id.back)?.setOnClickListener { finish() }
        findViewById<View>(R.id.more)?.setOnClickListener { showPlayerActions() }
    }

    /** 音频模式的三行文字视图（横屏布局中不存在，返回空列表）。 */
    private fun audioViews(): List<View> = listOfNotNull(
        findViewById(R.id.audio_title),
        findViewById(R.id.audio_artist),
        findViewById(R.id.audio_info),
    )

    private fun startExo() {
        val player = ExoPlayer.Builder(this).build()
        exo = player
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!playbackStarted && !fallbackTried) {
                    fallbackTried = true
                    runCatching { exo?.release() }
                    exo = null
                    usingFallback = true
                    buildFallbackUi()
                    startMediaPlayer()
                } else {
                    fail()
                }
            }
        })

        if (isVideo) {
            player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            player.prepare()
            player.playWhenReady = true
        } else {
            // 音频：先取内嵌封面与元数据 → 带作品图与标题的 MediaItem → prepare
            Thread {
                var bytes: ByteArray? = null
                runCatching {
                    MediaMetadataRetriever().use { r ->
                        r.setDataSource(file.absolutePath)
                        bytes = r.embeddedPicture
                    }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val artBmp = bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                    val meta = MediaMetadata.Builder()
                        .setTitle(file.nameWithoutExtension)
                        .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build()
                    player.setMediaItem(
                        MediaItem.Builder()
                            .setUri(android.net.Uri.fromFile(file))
                            .setMediaMetadata(meta)
                            .build()
                    )
                    player.prepare()
                    player.playWhenReady = true
                }
            }.start()
            // 文字信息（MaterialFiles 元数据栈）
            AudioInfoLiveData(file.absolutePath).observe(this) { state ->
                val info = (state as? Stateful.Success<AudioInfo>)?.value ?: return@observe
                if (isFinishing || isDestroyed) return@observe
                findViewById<TextView>(R.id.audio_title)?.text =
                    (info.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension)
                findViewById<TextView>(R.id.audio_artist)?.text =
                    (info.artist?.takeIf { it.isNotBlank() }
                        ?: info.album?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.player_local_audio))
                findViewById<TextView>(R.id.audio_info)?.text = listOfNotNull(
                    info.bitRate?.let { "${it / 1000} kbps" },
                    info.sampleRate?.let { "${it / 1000} kHz" },
                    info.genre?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifEmpty { "无损解码直出" }
            }
            // 元数据未带出前的占位
            findViewById<TextView>(R.id.audio_title)?.text = file.nameWithoutExtension
            findViewById<TextView>(R.id.audio_artist)?.text = getString(R.string.player_local_audio)
        }
        rebindExoUi()
    }

    // ---------- 回退引擎（avi/wma/wmv/midi） ----------

    private fun buildFallbackUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1E1E1E.toInt())
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF2B2B2B.toInt())
            setPadding(24, 32, 24, 32)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(this).apply {
            text = "‹"; textSize = 26f; setTextColor(0xFFDDDDDD.toInt()); setPadding(0, 0, 28, 0)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = file.name; textSize = 16f; setTextColor(0xFFEEEEEE.toInt())
            setTypeface(android.graphics.Typeface.MONOSPACE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (isVideo) {
            val holder = android.widget.FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
            mpSurface = SurfaceView(this)
            holder.addView(mpSurface, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER))
            root.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        mpCur = TextView(this).apply {
            text = "0:00"; textSize = 12f; setTextColor(0xFFDDDDDD.toInt())
            setTypeface(android.graphics.Typeface.MONOSPACE)
        }
        mpSeek = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(10); rightMargin = dp(10)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) mpCur?.text = fmt(progress.toLong())
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    runCatching { mp?.seekTo(sb?.progress ?: 0) }
                }
            })
        }
        mpDur = TextView(this).apply {
            text = "0:00"; textSize = 12f; setTextColor(0xFFDDDDDD.toInt())
            setTypeface(android.graphics.Typeface.MONOSPACE)
        }
        timeRow.addView(mpCur)
        timeRow.addView(mpSeek)
        timeRow.addView(mpDur)

        mpBtn = TextView(this).apply {
            text = "▶"; textSize = 52f
            setTextColor(0xFFDDDDDD.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(dp(40), 0, dp(40), 0)
            setOnClickListener { runCatching { if (mp?.isPlaying == true) { mp?.pause(); mpBtn?.text = "▶" } else { mp?.start(); mpBtn?.text = "⏸" } } }
        }

        if (isVideo) {
            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, dp(12))
            }
            controls.addView(timeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            controls.addView(mpBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL; topMargin = dp(10); bottomMargin = dp(10)
            })
            root.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            val bottom = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            bottom.addView(mpBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            bottom.addView(timeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(30) })
            root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    private fun startMediaPlayer() {
        Thread {
            var ok = false
            var duration = 0
            val p = MediaPlayer()
            runCatching {
                if (isVideo) mpSurface?.holder?.let { p.setDisplay(it) }
                p.setDataSource(file.absolutePath)
                p.prepare()
                p.setOnCompletionListener {
                    runOnUiThread {
                        mpSeek?.progress = 0
                        mpCur?.text = "0:00"
                        mpBtn?.text = "▶"
                    }
                }
                p.start()
                ok = true
                duration = p.duration
            }.onFailure {
                runCatching { p.release() }
            }
            runOnUiThread {
                if (!ok) {
                    fail()
                    return@runOnUiThread
                }
                mp = p
                mpPrepared = true
                playbackStarted = true
                mpBtn?.text = "⏸"
                if (duration > 0) mpSeek?.max = duration
                mpDur?.text = fmt(duration.toLong())
            }
        }.start()
    }

    private fun fail() {
        Toast.makeText(this, getString(R.string.toast_cant_open, file.name), Toast.LENGTH_LONG).show()
        finish()
    }

    private fun ext(): String = file.name.substringAfterLast('.', "").lowercase()

    // ---------- 播放器操作单（MaterialFiles 风格底部列表） ----------

    private fun showPlayerActions() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(16), dp(8), dp(24))
        }
        container.addView(TextView(this).apply {
            text = "播放器选项"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (com.limao.unzip.UiTheme.isNight()) 0xFFEEEEEE.toInt() else 0xFF1D1B20.toInt())
            setPadding(dp(16), dp(4), dp(16), dp(10))
        })

        fun actionRow(label: String, onClick: () -> Unit) {
            container.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(if (com.limao.unzip.UiTheme.isNight()) 0xFFDDDDDD.toInt() else 0xFF1D1B20.toInt())
                setPadding(dp(16), dp(14), dp(16), dp(14))
                bgResource(android.R.attr.selectableItemBackground)
                setOnClickListener { onClick() }
            })
        }

        if (exo != null) {
            val repeatLabel = when (exo?.repeatMode) {
                Player.REPEAT_MODE_ONE -> "单曲循环"
                Player.REPEAT_MODE_ALL -> "列表循环"
                else -> "关闭"
            }
            actionRow("循环播放：$repeatLabel") {
                exo?.repeatMode = when (exo?.repeatMode) {
                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_ALL
                }
                sheet.dismiss()
                showPlayerActions()
            }
            actionRow("播放速度：$speedLabel") {
                sheet.dismiss()
                showSpeedSheet()
            }
        } else {
            actionRow("当前引擎（平台回退）不支持速度与循环") { }
        }
        sheet.setContentView(container)
        sheet.show()
    }

    private var speedLabel = "1x"

    private fun showSpeedSheet() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(16), dp(8), dp(24))
        }
        container.addView(TextView(this).apply {
            text = "播放速度"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (com.limao.unzip.UiTheme.isNight()) 0xFFEEEEEE.toInt() else 0xFF1D1B20.toInt())
            setPadding(dp(16), dp(4), dp(16), dp(10))
        })
        listOf("0.5x" to 0.5f, "0.75x" to 0.75f, "1x" to 1f, "1.25x" to 1.25f, "1.5x" to 1.5f, "2x" to 2f)
            .forEach { (label, speed) ->
                container.addView(TextView(this).apply {
                    text = if (label == speedLabel) "✓ $label" else label
                    textSize = 15f
                    setTextColor(if (com.limao.unzip.UiTheme.isNight()) 0xFFDDDDDD.toInt() else 0xFF1D1B20.toInt())
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    bgResource(android.R.attr.selectableItemBackground)
                    setOnClickListener {
                        exo?.setPlaybackSpeed(speed)
                        speedLabel = label
                        sheet.dismiss()
                    }
                })
            }
        sheet.setContentView(container)
        sheet.show()
    }

    private fun TextView.bgResource(attr: Int) {
        val out = android.util.TypedValue()
        context.theme.resolveAttribute(attr, out, true)
        setBackgroundResource(out.resourceId)
    }

    // ---------- 工具 ----------

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun releaseEngines() {
        runCatching { exo?.release() }
        exo = null
        runCatching { mp?.release() }
        mp = null
        mpPrepared = false
    }

    override fun onDestroy() {
        super.onDestroy()
        mpTick?.let { ui.removeCallbacks(it) }
        releaseEngines()
    }

    /** 测试钩子。 */
    internal fun isPlayingNow(): Boolean = runCatching {
        exo?.isPlaying == true || mp?.isPlaying == true
    }.getOrDefault(false)

    companion object {
        const val EXTRA_PATH = "path"

        /** ExoPlayer 原生不支持的类型：直接走平台 MediaPlayer 回退。 */
        private val MEDIAPLAYER_ONLY = setOf("avi", "wma", "wmv", "midi", "mid")

        val VIDEO_EXTS = listOf(".mp4", ".m4v", ".mov", ".avi", ".flv", ".mkv", ".webm", ".3gp", ".ts", ".wmv")

        val AUDIO_EXTS = listOf(".mp3", ".wav", ".midi", ".mid", ".wma", ".flac", ".aac", ".ogg", ".m4a", ".opus")

        fun isVideoExt(name: String): Boolean = VIDEO_EXTS.any { name.lowercase().endsWith(it) }
    }
}
