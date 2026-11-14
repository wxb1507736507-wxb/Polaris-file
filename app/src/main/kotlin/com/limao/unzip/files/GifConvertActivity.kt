package com.limao.unzip.files

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import com.limao.unzip.R
import java.io.File
import java.io.FileOutputStream

/**
 * GIF 表情包转换工具：
 *  - 视频 → GIF：MediaMetadataRetriever 流式抽帧（可调帧率/时长/宽度）+ GifEncoder 两阶段编码
 *  - 动图 .gif → GIF：字节级直拷，真无损、瞬时完成
 *  - 静态图片 → GIF：全分辨率单帧，最优 256 色调色板
 *  - 产物落在 Download/PolarisGif/ 并触发媒体扫描
 */
class GifConvertActivity : AppCompatActivity() {

    private var source: Uri? = null
    private var sourceName: String = ""
    private var converting = false

    private lateinit var nameLabel: TextView
    private lateinit var optionsBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var convertBtn: TextView

    private var fps = 10
    private var durationSec = 10
    private var targetWidth = 480
    private var dither = false

    private val fpsValues = listOf(5, 10, 15)
    private val durValues = listOf(5, 10, 15)
    private val widthValues = listOf(480, 720)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = com.limao.unzip.UiTheme.isNight()
        val pageBg = com.limao.unzip.UiTheme.pageBg()
        val cardBg = com.limao.unzip.UiTheme.card()
        val fgPrimary = if (night) 0xFFEEEEEE.toInt() else 0xFF1D1B20.toInt()
        val fgSecondary = if (night) 0xFFDDDDDD.toInt() else 0xFF1D1B20.toInt()
        val fgMuted = if (night) 0xFF999999.toInt() else 0xFF666666.toInt()
        val accent = if (night) 0xFF82AAFF.toInt() else 0xFF1296DB.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBg)
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            setBackgroundColor(com.limao.unzip.UiTheme.navBar())
            setPadding(dp(6), dp(8), dp(6), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(this).apply {
            text = "‹"; textSize = 26f; setTextColor(fgSecondary); setPadding(dp(6), 0, dp(14), 0)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = getString(R.string.gif_tool_title); textSize = 16f; setTextColor(fgPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cardBg)
        }
        fun ViewGroup.marginCard(v: View) = addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        // ---- 选择文件卡片 ----
        val pickCard = card()
        pickCard.addView(TextView(this).apply {
            text = getString(R.string.gif_pick_source); textSize = 13f; setTextColor(fgMuted)
            setPadding(dp(16), dp(12), dp(16), 0)
        })
        pickCard.addView(TextView(this).apply {
            text = "📁 " + getString(R.string.btn_pick_file); textSize = 15f; setTextColor(accent)
            setPadding(dp(16), dp(10), dp(16), dp(6))
            setOnClickListener { pickFile.launch(arrayOf("video/*", "image/*")) }
        })
        nameLabel = TextView(this).apply {
            textSize = 14f; setTextColor(fgPrimary)
            setPadding(dp(16), 0, dp(16), dp(12))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            text = getString(R.string.gif_none_selected)
        }
        pickCard.addView(nameLabel)
        body.marginCard(pickCard)

        // ---- 选项卡片（仅视频显示）----
        optionsBox = card()
        fun label(labelStr: String) = TextView(this).apply {
            text = labelStr; textSize = 13f; setTextColor(fgMuted); setPadding(dp(16), dp(12), dp(16), dp(2))
        }
        fun chipRow(values: List<String>, def: Int = 0): HorizontalScrollView {
            val row = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(6), dp(12), dp(2)) }
            values.forEachIndexed { idx, item ->
                inner.addView(TextView(this).apply {
                    text = item; textSize = 14f
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    if (idx == def) {
                        isSelected = true
                        setBackgroundResource(R.drawable.chip_selected)
                        setTextColor(accent)
                    } else {
                        setBackgroundResource(R.drawable.chip_normal)
                        setTextColor(fgSecondary)
                    }
                    setOnClickListener { v ->
                        // 同组互斥选中
                        inner.children.forEach { c ->
                            (c as TextView).isSelected = false
                            c.setBackgroundResource(R.drawable.chip_normal)
                            c.setTextColor(fgSecondary)
                        }
                        (v as TextView).isSelected = true
                        v.setBackgroundResource(R.drawable.chip_selected)
                        v.setTextColor(accent)
                    }
                })
            }
            row.addView(inner)
            return row
        }
        optionsBox.addView(label(getString(R.string.gif_opt_fps)))
        optionsBox.addView(chipRow(fpsValues.map { it.toString() }, def = 1))
        optionsBox.addView(label(getString(R.string.gif_opt_duration)))
        optionsBox.addView(chipRow(durValues.map { "$it s" }, def = 1))
        optionsBox.addView(label(getString(R.string.gif_opt_width)))
        optionsBox.addView(chipRow(widthValues.map { it.toString() }, def = 0))
        optionsBox.addView(label(getString(R.string.gif_opt_quality)))
        optionsBox.addView(chipRow(listOf(getString(R.string.gif_quality_fast), getString(R.string.gif_quality_hd)), def = 0))
        optionsBox.visibility = View.GONE
        body.marginCard(optionsBox)

        // ---- 转换按钮 ----
        convertBtn = TextView(this).apply {
            text = "🎞️ " + getString(R.string.gif_convert_start); textSize = 16f; setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(accent)
            setOnClickListener { startConvert() }
        }
        body.addView(convertBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        body.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        statusText = TextView(this).apply {
            textSize = 13f; setTextColor(fgMuted); setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        body.addView(statusText)
        setContentView(root)
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        source = uri
        sourceName = queryDisplayName(uri) ?: "media"
        nameLabel.text = sourceName
        val isVideo = isVideoSource(uri)
        optionsBox.visibility = if (isVideo) View.VISIBLE else View.GONE
        statusText.text = ""
        if (isVideo) readChips()
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun isVideoSource(uri: Uri): Boolean {
        // 扩展名优先（部分设备 provider 对 SAF uri 的 MIME 判定不可靠）
        val name = (sourceName.ifEmpty { queryDisplayName(uri) ?: "" }).lowercase()
        if (name.endsWith(".gif")) return false
        if (name.matches(Regex(".*\\.(png|jpe?g|webp|bmp|heic|heif)$"))) return false
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull() ?: ""
        if (mime.startsWith("image/")) return false
        if (mime.startsWith("video/")) return true
        return listOf(".mp4", ".mov", ".mkv", ".webm", ".avi", ".3gp", ".flv", ".ts", ".m4v", ".wmv", ".mpg", ".mpeg").any { name.endsWith(it) }
    }

    /** 从选项组读取选择（未选默认第一项）。 */
    private fun readChips() {
        val groups = optionsBox.children.filterIsInstance<HorizontalScrollView>().toList()
        fun sel(group: HorizontalScrollView): Int {
            val inner = group.getChildAt(0) as ViewGroup
            var picked = 0
            var seen = 0
            inner.children.forEach { c ->
                if (c.isSelected) picked = seen
                seen++
            }
            return picked
        }
        if (groups.size >= 4) {
            fps = fpsValues[sel(groups[0]).coerceIn(0, fpsValues.lastIndex)]
            durationSec = durValues[sel(groups[1]).coerceIn(0, durValues.lastIndex)]
            targetWidth = widthValues[sel(groups[2]).coerceIn(0, widthValues.lastIndex)]
            dither = sel(groups[3]) == 1
        }
    }

    private fun startConvert() {
        val uri = source ?: run { Toast.makeText(this, R.string.gif_pick_source, Toast.LENGTH_SHORT).show(); return }
        if (converting) return
        converting = true
        convertBtn.isEnabled = false
        convertBtn.alpha = 0.5f
        progress.visibility = View.VISIBLE
        progress.progress = 0

        val isVideo = isVideoSource(uri)
        val name = sourceName.substringBeforeLast('.').ifEmpty { "gif" }
        val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PolarisGif").apply { mkdirs() }
        val out = File(outDir, "${name}_${System.currentTimeMillis() % 100000}.gif")

        Thread {
            val result = runCatching {
                if (isVideo) convertVideo(uri, out) else convertImage(uri, out)
            }
            runOnUiThread {
                converting = false
                convertBtn.isEnabled = true
                convertBtn.alpha = 1f
                result.fold(
                    onSuccess = {
                        progress.progress = 100
                        statusText.text = getString(R.string.gif_done, out.absolutePath)
                        Toast.makeText(this, getString(R.string.gif_done, out.name), Toast.LENGTH_LONG).show()
                        android.media.MediaScannerConnection.scanFile(this, arrayOf(out.absolutePath), arrayOf("image/gif"), null)
                    },
                    onFailure = {
                        statusText.text = getString(R.string.gif_failed, it.message ?: "unknown")
                        progress.visibility = View.GONE
                    },
                )
            }
        }.start()
    }

    // ---------- 视频 → GIF ----------

    private fun convertVideo(uri: Uri, out: File) {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(this, uri)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            if (durMs <= 0) throw IllegalStateException("duration=0")
            val durUs = durMs * 1000
            val useUs = minOf(durUs, durationSec * 1_000_000L)
            val count = ((useUs.toDouble() / 1_000_000.0) * fps).toInt().coerceAtLeast(1)
            val delayCs = 100 / fps

            // 显示尺寸（考虑旋转）
            var vw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: targetWidth
            var vh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: targetWidth
            val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) { val t = vw; vw = vh; vh = t }
            val scale = targetWidth.toDouble() / maxOf(vw, vh)
            var dw = (vw * scale).toInt().coerceAtLeast(16)
            var dh = (vh * scale).toInt().coerceAtLeast(16)
            if (dw % 2 != 0) dw -= 1
            if (dh % 2 != 0) dh -= 1

            fun frameAt(i: Int): Bitmap? {
                val tsUs = i.toLong() * 1_000_000L / fps
                var bmp: Bitmap? = null
                if (Build.VERSION.SDK_INT >= 27) {
                    // 部分设备 getScaledFrameAtTime 在非零时间戳可能返回 null，失败则回退全分辨率解码
                    bmp = runCatching { mmr.getScaledFrameAtTime(tsUs, MediaMetadataRetriever.OPTION_CLOSEST, dw, dh) }.getOrNull()
                }
                if (bmp == null) {
                    val raw = runCatching { mmr.getFrameAtTime(tsUs, MediaMetadataRetriever.OPTION_CLOSEST) }.getOrNull()
                        ?: runCatching { mmr.getFrameAtTime(tsUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
                        ?: return null
                    val s = Bitmap.createScaledBitmap(raw, dw, dh, true)
                    if (s !== raw) raw.recycle()
                    bmp = s
                }
                if (rotation == 0) return bmp
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated !== bmp) bmp.recycle()
                return rotated
            }

            val total = count * 2
            fun report(step: Int, phase: String) {
                runOnUiThread {
                    val pct = step * 100 / total
                    progress.progress = pct
                    statusText.text = "$phase $pct%"
                }
            }

            // 阶段一：采样构建全局调色板
            val pb = GifEncoder.PaletteBuilder()
            for (i in 0 until count) {
                val f = frameAt(i)
                android.util.Log.d("GifConv", "sample i=$i ts=${i * 1_000_000L / fps} ok=${f != null} count=$count dur=${durMs}ms")
                f?.let { pb.feed(it); it.recycle() }
                report(i + 1, getString(R.string.gif_phase_palette))
            }
            val palette = pb.build()

            // 阶段二：惰性逐帧编码（同一时刻仅一帧在内存）
            val frames = sequence {
                for (i in 0 until count) {
                    val bmp = frameAt(i)
                    if (bmp != null) {
                        report(count + i + 1, getString(R.string.gif_phase_encode))
                        yield(GifEncoder.Frame(bmp, delayCs))
                    }
                }
            }
            GifEncoder.write(out, dw, dh, palette, loopForever = true, frames, dither)
        } finally {
            runCatching { mmr.release() }
        }
    }

    // ---------- 图片 → GIF ----------

    private fun convertImage(uri: Uri, out: File) {
        val name = (queryDisplayName(uri) ?: "").lowercase()
        if (name.endsWith(".gif")) {
            // 已是 GIF：字节级直拷，零质量损失、瞬时完成
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output, 256 * 1024) }
            } ?: throw IllegalStateException("open stream failed")
            return
        }
        // 其他图片：全分辨率单帧 GIF（超大图按 1600px 上限缩采样，避免 OOM）
        val bmp = if (Build.VERSION.SDK_INT >= 28) {
            val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { dec, info, _ ->
                if (info.size.width > 1600) {
                    val scale = 1600f / info.size.width
                    dec.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
            }
        } else {
            val raw = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalStateException("decode failed")
            if (raw.width > 1600) {
                val scale = 1600.0 / raw.width
                val s = Bitmap.createScaledBitmap(raw, 1600, (raw.height * scale).toInt().coerceAtLeast(1), true)
                if (s !== raw) raw.recycle()
                s
            } else raw
        }
        GifEncoder.encodeSingle(out, bmp)
        bmp.recycle()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** 供工具页直接跳转。 */
        fun launch(activity: Activity) {
            activity.startActivity(android.content.Intent(activity, GifConvertActivity::class.java))
        }
    }
}
