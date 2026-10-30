package com.limao.unzip.files

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.limao.unzip.R
import java.io.File
import java.util.Locale

/**
 * 统一播放器（M3 深色）：
 *  - 主引擎 Media3 ExoPlayer：mp4/mov/m4v/webm/mkv/flv/ts/3gp 视频（解码渲染跟随媒体原生帧率，无帧率上限），
 *    mp3/wav/flac/ogg/m4a/aac/opus 音频（仅解码为 PCM 输出，不做任何重编码/DSP，即文件数据的无损播放）。
 *  - 回退引擎平台 MediaPlayer：ExoPlayer 不支持的容器/编码（avi、wma、midi）按设备编解码器尽力播放
 *    （midi 走系统合成器，avi/wma 取决于设备硬件解码器）。
 *  - 视频渲染 SurfaceView，画面比例跟随视频实际尺寸。
 */
class PlayerActivity : AppCompatActivity() {

    private var exo: ExoPlayer? = null
    private var mp: MediaPlayer? = null
    private var prepared = false
    private var userSeeking = false
    private var isVideo = false
    private var playbackStarted = false
    private var fallbackTried = false
    private lateinit var file: File
    private lateinit var seek: SeekBar
    private lateinit var curTime: TextView
    private lateinit var playBtn: TextView
    private lateinit var surfaceView: SurfaceView
    private val ui = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            if (!userSeeking) {
                val pos = currentPos()
                val dur = totalDur()
                if (dur > 0) {
                    seek.max = dur.toInt()
                    if (seek.max != seek.secondaryProgress) seek.secondaryProgress = seek.max
                }
                if (pos in 0..seek.max) seek.progress = pos
                curTime.text = fmt(pos.toLong())
            }
            ui.postDelayed(this, 500L)
        }
    }

    // ---------- 引擎抽象 ----------

    private fun currentPos(): Int = when {
        exo != null -> exo?.currentPosition?.toInt() ?: 0
        mp != null -> mp?.currentPosition ?: 0
        else -> 0
    }

    private fun totalDur(): Long = when {
        exo != null -> exo?.duration?.takeIf { it > 0 } ?: 0L
        mp != null -> mp?.duration?.toLong() ?: 0L
        else -> 0L
    }

    private fun playingNow(): Boolean = runCatching {
        exo?.isPlaying == true || mp?.isPlaying == true
    }.getOrDefault(false)

    private fun pauseOrResume() {
        runCatching {
            if (playingNow()) {
                exo?.pause(); mp?.pause(); playBtn.text = "▶"
            } else {
                exo?.play(); mp?.start(); playBtn.text = "⏸"
            }
        }
    }

    private fun seekTo(ms: Int) {
        runCatching {
            exo?.seekTo(ms.toLong())
            mp?.seekTo(ms)
        }
    }

    // ---------- 生命周期 ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        file = File(path)
        isVideo = isVideoExt(file.name)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (isVideo) 0xFF000000.toInt() else 0xFF1E1E1E.toInt())
            fitsSystemWindows = true
        }
        // 顶栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF2B2B2B.toInt())
            setPadding(24, 32, 24, 32)
            gravity = Gravity.CENTER_VERTICAL
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

        var art: ImageView? = null
        var title: TextView? = null
        var artist: TextView? = null

        if (isVideo) {
            // 视频画面（SurfaceView，比例随视频尺寸更新，黑底 letterbox）
            val holder = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
            surfaceView = SurfaceView(this)
            holder.addView(surfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            root.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            val artView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            root.addView(artView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)).apply { topMargin = dp(28) })
            art = artView
            title = TextView(this).apply {
                textSize = 18f; setTextColor(0xFFEEEEEE.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(dp(24), dp(18), dp(24), 0)
            }
            artist = TextView(this).apply {
                textSize = 13f; setTextColor(0xFF999999.toInt())
                gravity = Gravity.CENTER; maxLines = 1
                setPadding(dp(24), dp(6), dp(24), 0)
            }
        }

        // 控制区
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        curTime = TextView(this).apply {
            text = "0:00"; textSize = 12f; setTextColor(0xFFDDDDDD.toInt())
            setTypeface(android.graphics.Typeface.MONOSPACE)
        }
        seek = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(10); rightMargin = dp(10)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) curTime.text = fmt(progress.toLong())
                }

                override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    userSeeking = false
                    seekTo(sb?.progress ?: 0)
                }
            })
        }
        val durTime = TextView(this).apply {
            text = "0:00"; textSize = 12f; setTextColor(0xFFDDDDDD.toInt())
            setTypeface(android.graphics.Typeface.MONOSPACE)
        }
        timeRow.addView(curTime)
        timeRow.addView(seek)
        timeRow.addView(durTime)

        playBtn = TextView(this).apply {
            text = "▶"; textSize = 52f
            setTextColor(0xFFDDDDDD.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(40), 0, dp(40), 0)
            setOnClickListener { pauseOrResume() }
        }

        if (isVideo) {
            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF1E1E1E.toInt())
                setPadding(0, dp(12), 0, dp(12))
            }
            controls.addView(timeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            controls.addView(playBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10); bottomMargin = dp(10)
            })
            root.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            val bottom = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            bottom.addView(title)
            bottom.addView(artist)
            bottom.addView(playBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(30) })
            bottom.addView(timeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(34) })
            root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            // 音频元数据/封面
            loadAudioMeta(art, title!!, artist!!)
        }

        setContentView(root)
        prepareEngine()
        ui.post(tick)
    }

    // ---------- 准备 ----------

    private fun prepareEngine() {
        if (isVideo || !MEDIAPLAYER_ONLY.contains(ext())) {
            startExo()
        } else {
            startMediaPlayer()
        }
    }

    private fun startExo() {
        val player = ExoPlayer.Builder(this).build()
        exo = player
        if (isVideo) {
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {}
                override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {}
            })
            player.setVideoSurfaceView(surfaceView)
        }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    prepared = true
                    playbackStarted = playbackStarted || player.playWhenReady
                    playBtn.text = if (playingNow()) "⏸" else "▶"
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                if (playing) {
                    playbackStarted = true
                    playBtn.text = "⏸"
                } else if (prepared) {
                    playBtn.text = "▶"
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (!isVideo || videoSize.width == 0 || videoSize.height == 0) return
                val ratio = videoSize.width.toFloat() / videoSize.height.toFloat()
                surfaceView.post {
                    val parent = surfaceView.parent as? FrameLayout ?: return@post
                    val w = parent.width.takeIf { it > 0 } ?: return@post
                    var h = (w / ratio).toInt().coerceAtLeast(1)
                    parent.post {
                        val ph = parent.height.takeIf { it > 0 } ?: return@post
                        if (h > ph) h = ph
                        surfaceView.layoutParams = FrameLayout.LayoutParams((h * ratio).toInt(), h, Gravity.CENTER)
                        surfaceView.requestLayout()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!playbackStarted && !fallbackTried) {
                    fallbackTried = true
                    releaseEngines()
                    startMediaPlayer()
                } else {
                    fail()
                }
            }
        })
        player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
        player.prepare()
        player.playWhenReady = true
    }

    private fun startMediaPlayer() {
        // 平台回退引擎：avi/wma 依赖设备解码器；midi 走系统合成器
        Thread {
            var ok = false
            var duration = 0
            val p = MediaPlayer()
            runCatching {
                if (isVideo) {
                    p.setDisplay(surfaceView.holder)
                }
                p.setDataSource(file.absolutePath)
                p.prepare()
                p.setOnCompletionListener {
                    runOnUiThread {
                        seek.progress = 0
                        curTime.text = "0:00"
                        playBtn.text = "▶"
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
                prepared = true
                playbackStarted = true
                playBtn.text = "⏸"
                if (duration > 0) seek.max = duration
            }
        }.start()
    }


    private fun fail() {
        Toast.makeText(this, getString(R.string.toast_cant_open, file.name), Toast.LENGTH_LONG).show()
        finish()
    }

    private fun ext(): String = file.name.substringAfterLast('.', "").lowercase()

    // ---------- 音频元数据 ----------

    private fun loadAudioMeta(art: ImageView?, title: TextView, artist: TextView) {
        Thread {
            val r = MediaMetadataRetriever()
            var bitmap: android.graphics.Bitmap? = null
            var mTitle: String? = null
            var mArtist: String? = null
            runCatching {
                r.setDataSource(file.absolutePath)
                bitmap = r.embeddedPicture?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                mTitle = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                mArtist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            }
            runCatching { r.release() }
            runOnUiThread {
                if (bitmap != null) {
                    art?.setImageBitmap(bitmap)
                } else {
                    art?.setImageResource(R.drawable.ic_audiotrack)
                    art?.setColorFilter(0xFF9AA3AC.toInt())
                }
                title.text = (mTitle?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension)
                artist.text = (mArtist?.takeIf { it.isNotBlank() } ?: getString(R.string.player_local_audio))
            }
        }.start()
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
        prepared = false
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(tick)
        releaseEngines()
    }

    /** 测试钩子。 */
    internal fun isPlayingNow(): Boolean = playingNow()

    companion object {
        const val EXTRA_PATH = "path"

        /** ExoPlayer 原生不支持的类型：直接走平台 MediaPlayer 回退。 */
        private val MEDIAPLAYER_ONLY = setOf("avi", "wma", "wmv", "midi", "mid")

        val VIDEO_EXTS = listOf(".mp4", ".m4v", ".mov", ".avi", ".flv", ".mkv", ".webm", ".3gp", ".ts", ".wmv")

        val AUDIO_EXTS = listOf(".mp3", ".wav", ".midi", ".mid", ".wma", ".flac", ".aac", ".ogg", ".m4a", ".opus")

        fun isVideoExt(name: String): Boolean = VIDEO_EXTS.any { name.lowercase().endsWith(it) }
    }
}
