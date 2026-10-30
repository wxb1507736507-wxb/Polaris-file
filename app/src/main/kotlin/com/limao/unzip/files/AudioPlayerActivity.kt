package com.limao.unzip.files

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.limao.unzip.R
import java.io.File
import java.util.Locale

/**
 * 内建音频播放器（M3 深色，与阅读器体系一致）：
 * mp3/wav/flac/ogg/m4a 等，内嵌封面 + 标题/艺术家 + 进度拖拽 + 播放/暂停。
 */
class AudioPlayerActivity : AppCompatActivity() {

    private var player: MediaPlayer? = null
    private var prepared = false
    private var userSeeking = false
    private lateinit var file: File
    private lateinit var seek: SeekBar
    private lateinit var curTime: TextView
    private lateinit var playBtn: TextView
    private val ui = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && prepared && !userSeeking) {
                seek.progress = p.currentPosition
                // progress 为 Int
                curTime.text = fmt(p.currentPosition.toLong())
            }
            ui.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        file = File(path)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1E1E1E.toInt())
            fitsSystemWindows = true
            gravity = Gravity.CENTER_HORIZONTAL
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

        // 封面（内嵌图优先，缺省占位）
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(art, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)).apply { topMargin = dp(28) })

        // 标题 / 艺术家
        val title = TextView(this).apply {
            textSize = 18f
            setTextColor(0xFFEEEEEE.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(dp(24), dp(18), dp(24), 0)
        }
        val artist = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF999999.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(24), dp(6), dp(24), 0)
        }

        // 进度条 + 时间
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
                    player?.takeIf { prepared }?.seekTo(sb?.progress ?: 0)
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

        // 播放/暂停
        playBtn = TextView(this).apply {
            text = "▶"; textSize = 52f
            setTextColor(0xFFDDDDDD.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(40), 0, dp(40), 0)
            setOnClickListener { toggle() }
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            gravity = Gravity.CENTER
        }
        bottom.addView(title)
        bottom.addView(artist)
        bottom.addView(playBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(30) })
        bottom.addView(timeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(34) })
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // 元数据与封面（后台）
        Thread {
            val r = MediaMetadataRetriever()
            var bitmap: android.graphics.Bitmap? = null
            var mTitle: String? = null
            var mArtist: String? = null
            var durationMs = 0L
            runCatching {
                r.setDataSource(path)
                bitmap = r.embeddedPicture?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                mTitle = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                mArtist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
            runCatching { r.release() }
            runOnUiThread {
                if (bitmap != null) art.setImageBitmap(bitmap)
                else {
                    art.setImageResource(R.drawable.ic_audiotrack)
                    art.setColorFilter(0xFF9AA3AC.toInt())
                }
                title.text = (mTitle?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension)
                artist.text = (mArtist?.takeIf { it.isNotBlank() } ?: getString(R.string.player_local_audio))
                if (durationMs > 0) durTime.text = fmt(durationMs)
            }
        }.start()

        preparePlayer { duration ->
            seek.max = duration
            durTime.text = fmt(duration.toLong())
        }
        ui.post(tick)
    }

    private fun preparePlayer(onReady: (Int) -> Unit) {
        Thread {
            val ok = runCatching {
                MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    setOnCompletionListener {
                        runOnUiThread {
                            seek.progress = 0
                            curTime.text = "0:00"
                            playBtn.text = "▶"
                        }
                    }
                    start()
                }.also { player = it }
            }.isSuccess
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this, getString(R.string.toast_cant_open, file.name), Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                prepared = true
                playBtn.text = "⏸"
                onReady(player!!.duration)
            }
        }.start()
    }

    private fun toggle() {
        val p = player?.takeIf { prepared } ?: return
        runCatching {
            if (p.isPlaying) {
                p.pause()
                playBtn.text = "▶"
            } else {
                p.start()
                playBtn.text = "⏸"
            }
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 测试钩子。 */
    internal fun isPlayingNow(): Boolean = runCatching { player?.isPlaying == true }.getOrDefault(false)

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(tick)
        runCatching { player?.release() }
        player = null
        prepared = false
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
