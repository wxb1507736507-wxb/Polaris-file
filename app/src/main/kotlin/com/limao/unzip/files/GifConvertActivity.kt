package com.limao.unzip.files

import android.app.Activity
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
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
 *    · 高清模式：逐帧局部调色板 + Floyd-Steinberg 抖动（色彩保真）
 *    · 高速模式：全局调色板（更快）
 *    · 帧去重：相同帧跳过（避免死帧）
 *  - 动图输入（动图 WebP / GIF）：GIF 字节级直拷（真无损）；动图 WebP 逐帧抓取转编码
 *  - 静态图片 → GIF：全分辨率单帧
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
    private var targetWidth = 720
    private var dither = true

    private val fpsValues = listOf(5, 10, 15)
    private val durValues = listOf(5, 10, 15)
    private val widthValues = listOf(480, 720, 1080)
    private val mainHandler = Handler(Looper.getMainLooper())
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
        fun marginCard(v: View) = body.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

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
        marginCard(pickCard)

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
        optionsBox.addView(chipRow(widthValues.map { it.toString() }, def = 1))
        optionsBox.addView(label(getString(R.string.gif_opt_quality)))
        optionsBox.addView(chipRow(listOf(getString(R.string.gif_quality_fast), getString(R.string.gif_quality_hd)), def = 1))
        optionsBox.visibility = View.GONE
        marginCard(optionsBox)

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
            var async = false
            val result = runCatching {
                if (isVideo) convertVideo(uri, out)
                else async = convertImage(uri, out)
            }
            if (!async) runOnUiThread { onConvertDone(result, out) }
        }.start()
    }

    private fun onConvertDone(result: Result<Unit>, out: File) {
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

    /** 位图简单哈希（帧去重用） */
    private fun bitmapHash(b: Bitmap): Int {
        var h = 17
        val w = b.width; val hh = b.height
        for (i in 0 until 64) {
            h = h * 31 + b.getPixel((i * 37) % w, (i * 91) % hh)
        }
        return h
    }

    // ---------- 视频 → GIF ----------

    private class VideoMeta(val durationMs: Long, val width: Int, val height: Int, val rotation: Int)

    private fun readVideoMeta(uri: Uri): VideoMeta {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(this, uri)
            VideoMeta(
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
            )
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** 采样亮度均值判断黑帧（黑场/黑屏帧） */
    private fun isMostlyBlack(b: Bitmap): Boolean {
        var sum = 0L
        val n = 64
        for (k in 0 until n) {
            val p = b.getPixel((k * 37) % b.width, (k * 91) % b.height)
            sum += (android.graphics.Color.red(p) + android.graphics.Color.green(p) + android.graphics.Color.blue(p)).toLong()
        }
        return sum / n < 90 // 平均每通道 < 30 视为黑帧
    }

    private fun convertVideo(uri: Uri, out: File) {
        val meta = readVideoMeta(uri)
        if (meta.durationMs <= 0) throw IllegalStateException("duration=0")

        // 显示尺寸（考虑旋转）
        var vw = meta.width.coerceAtLeast(16)
        var vh = meta.height.coerceAtLeast(16)
        if (meta.rotation == 90 || meta.rotation == 270) { val t = vw; vw = vh; vh = t }
        val scale = targetWidth.toDouble() / maxOf(vw, vh)
        var dw = (vw * scale).toInt().coerceAtLeast(16)
        var dh = (vh * scale).toInt().coerceAtLeast(16)
        if (dw % 2 != 0) dw -= 1
        if (dh % 2 != 0) dh -= 1

        // 主路径：ExoPlayer + ImageReader（部分厂商解码器输出私有像素格式会失败）
        try {
            convertVideoExo(uri, out, meta, dw, dh)
            return
        } catch (t: Throwable) {
            android.util.Log.w("GifConvert", "ExoPlayer extract failed: ${t.message}")
        }
        // 次路径：MediaCodec 缓冲区模式（flexible YUV，兼容 MTK 等厂商私有格式）
        try {
            convertVideoCodec(uri, out, meta, dw, dh)
            return
        } catch (t: Throwable) {
            android.util.Log.w("GifConvert", "Codec extract failed: ${t.message}")
        }
        // 兜底：MediaMetadataRetriever（全尺寸帧 + 黑帧检测/重建重试）
        convertVideoMmr(uri, out, meta, dw, dh)
    }

    /** 双路径共用的采集+编码管线（fetch 返回 null 视为无效帧跳过） */
    private fun encodeFrames(
        out: File, dw: Int, dh: Int, fps: Int, delayCs: Int, dither: Boolean,
        fetch: (Long) -> Bitmap?, report: (Int, String) -> Unit,
    ) {
        val count = ((minOf(durUsClamped(), durationSec * 1_000_000L).toDouble() / 1_000_000.0) * fps).toInt().coerceIn(1, 600)
        val grid = collectGrid(count, fps, fetch, report)
        encodeGrid(out, dw, dh, delayCs, dither, grid, report)
    }

    /** 按网格抽帧 + 黑帧过滤 */
    private fun collectGrid(count: Int, fps: Int, fetch: (Long) -> Bitmap?, report: (Int, String) -> Unit): Array<Bitmap?> {
        val grid = arrayOfNulls<Bitmap>(count)
        for (i in 0 until count) {
            var f = fetch(i.toLong() * 1_000_000L / fps)
            if (f != null && isMostlyBlack(f)) { f.recycle(); f = null }
            grid[i] = f
            report(i + 1, getString(R.string.gif_phase_palette))
        }
        return grid
    }

    /** 哈希去重 → 调色板 → 逐帧编码 */
    private fun encodeGrid(
        out: File, dw: Int, dh: Int, delayCs: Int, dither: Boolean,
        grid: Array<Bitmap?>, report: (Int, String) -> Unit,
    ) {
        val count = grid.size
        val hashes = IntArray(count)
        var validCount = 0
        for (i in 0 until count) {
            hashes[i] = grid[i]?.let { bitmapHash(it) } ?: -1
            if (hashes[i] >= 0) validCount++
        }
        if (validCount < 2) throw IllegalStateException(getString(R.string.gif_no_valid_frames))

        // 去重：保留首个与所有变化帧
        val kept = ArrayList<Int>()
        var lastHash = Int.MIN_VALUE
        for (i in 0 until count) {
            if (hashes[i] < 0) continue
            if (hashes[i] != lastHash) { kept.add(i); lastHash = hashes[i] }
        }

        val palette = if (dither) intArrayOf() else GifEncoder.PaletteBuilder().also { pb ->
            for (i in kept) { grid[i]?.let { pb.feed(it) } }
        }.build()

        val frames = sequence {
            for ((idx, i) in kept.withIndex()) {
                val bmp = grid[i]
                if (bmp != null) {
                    report(kept.size + idx + 1, getString(R.string.gif_phase_encode))
                    yield(GifEncoder.Frame(bmp, delayCs))
                }
            }
        }
        GifEncoder.write(out, dw, dh, palette, loopForever = true, frames, dither, perFramePalette = dither)
        for (b in grid) b?.recycle() // 写出完成后统一回收帧位图
    }

    private var videoDurUs = 0L
    private fun durUsClamped(): Long = videoDurUs

    private fun convertVideoExo(uri: Uri, out: File, meta: VideoMeta, dw: Int, dh: Int) {
        videoDurUs = meta.durationMs * 1000
        val extractor = VideoFrameExtractor(this, uri, dw, dh)
        try {
            val total = ((minOf(videoDurUs, durationSec * 1_000_000L).toDouble() / 1_000_000.0) * fps).toInt().coerceIn(1, 600)
            fun report(step: Int, phase: String) {
                runOnUiThread {
                    progress.progress = step * 100 / total
                    statusText.text = "$phase ${step * 100 / total}%"
                }
            }
            var leadFailures = 0
            encodeFrames(out, dw, dh, fps, 100 / fps, dither, { ts ->
                val f = runCatching { extractor.frameAt(ts) }.getOrNull()
                if (f == null) {
                    // 开头连续失败=解码器/格式不可用：快速放弃本路径（避免整段慢慢磨）
                    if (++leadFailures > 2) throw IllegalStateException("decoder output unavailable")
                } else leadFailures = 0
                f
            }, ::report)
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** MediaCodec 缓冲区模式路径（MTK 等厂商解码器的通用兜底） */
    private fun convertVideoCodec(uri: Uri, out: File, meta: VideoMeta, dw: Int, dh: Int) {
        val total = ((minOf(meta.durationMs * 1000, durationSec * 1_000_000L).toDouble() / 1_000_000.0) * fps).toInt().coerceIn(1, 600)
        CodecFrameExtractor(this, uri).use { ex ->
            val grid = ex.sampleGrid(dw, dh, fps, durationSec) { done, tot ->
                runOnUiThread {
                    progress.progress = done * 100 / tot
                    statusText.text = "${getString(R.string.gif_phase_palette)} ${done * 100 / tot}%"
                }
            }
            // 黑帧过滤
            for (i in grid.indices) {
                val f = grid[i]
                if (f != null && isMostlyBlack(f)) { f.recycle(); grid[i] = null }
            }
            encodeGrid(out, dw, dh, 100 / fps, dither, grid) { step, phase ->
                runOnUiThread {
                    progress.progress = step * 100 / total
                    statusText.text = "$phase ${step * 100 / total}%"
                }
            }
        }
    }

    /** MediaMetadataRetriever 兜底：全尺寸抽帧 + 缩放 + 黑帧检测（重建重试） */
    private fun convertVideoMmr(uri: Uri, out: File, meta: VideoMeta, dw: Int, dh: Int) {
        videoDurUs = meta.durationMs * 1000
        val total = ((minOf(videoDurUs, durationSec * 1_000_000L).toDouble() / 1_000_000.0) * fps).toInt().coerceIn(1, 600)
        fun report(step: Int, phase: String) {
            runOnUiThread {
                progress.progress = step * 100 / total
                statusText.text = "$phase ${step * 100 / total}%"
            }
        }
        val mmr = MediaMetadataRetriever()
        var mmrAlive = try {
            mmr.setDataSource(this@GifConvertActivity, uri)
            true
        } catch (t: Throwable) {
            false
        }
        fun grab(tsUs: Long): Bitmap? {
            if (!mmrAlive) return null
            for (attempt in 0 until 3) {
                val full = runCatching {
                    mmr.getFrameAtTime(tsUs, MediaMetadataRetriever.OPTION_CLOSEST)
                }.getOrNull()
                if (full != null && !isMostlyBlack(full)) {
                    val scaled = Bitmap.createScaledBitmap(full, dw, dh, true)
                    if (scaled !== full) full.recycle()
                    return scaled
                }
                // 黑帧/NULL：重建 retriever 后重试
                runCatching { mmr.release() }
                mmrAlive = false
                if (attempt < 2) {
                    runCatching {
                        mmr.setDataSource(this@GifConvertActivity, uri)
                        mmrAlive = true
                    }
                }
            }
            return null
        }
        try {
            encodeFrames(out, dw, dh, fps, 100 / fps, dither, ::grab, ::report)
        } finally {
            runCatching { if (mmrAlive) mmr.release() }
        }
    }

    // ---------- 图片 / 动图 → GIF ----------

    /** 返回 true 表示已转入异步抓帧流程（动图）；false 表示同步完成或已抛异常 */
    private fun convertImage(uri: Uri, out: File): Boolean {
        val name = (queryDisplayName(uri) ?: "").lowercase()
        if (name.endsWith(".gif")) {
            // 已是 GIF：字节级直拷，零质量损失、瞬时完成
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output, 256 * 1024) }
            } ?: throw IllegalStateException("open stream failed")
            return false
        }
        if (Build.VERSION.SDK_INT >= 28) {
            // 动图检测：decodeDrawable 返回 AnimatedImageDrawable 即为动图（动图 WebP 等）
            val drawable = runCatching {
                val src = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                android.graphics.ImageDecoder.decodeDrawable(src)
            }.getOrNull()
            if (drawable is AnimatedImageDrawable) {
                captureAnimated(drawable, out)
                return true // 异步流程：抓帧完成后自行回调 onConvertDone
            }
        }
        // 静态图片：全分辨率单帧 GIF（超大图按 1600px 上限缩采样，避免 OOM）
        val bmp = if (Build.VERSION.SDK_INT >= 28) {
            val src = android.graphics.ImageDecoder.createSource(contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(src) { dec, info, _ ->
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
        return false
    }

    /** 动图（AnimatedImageDrawable）按 10fps 逐帧抓取 → 编码为循环 GIF（主线程抓帧，后台编码） */
    private fun captureAnimated(drawable: AnimatedImageDrawable, out: File) {
        val maxFrames = 100
        val intervalMs = 100L
        var w = drawable.intrinsicWidth.coerceAtMost(720).coerceAtLeast(1)
        var h = drawable.intrinsicHeight.coerceAtMost(720).coerceAtLeast(1)
        val scale = 720f / maxOf(w, h).coerceAtLeast(1)
        if (scale < 1f) { w = (w * scale).toInt().coerceAtLeast(1); h = (h * scale).toInt().coerceAtLeast(1) }
        drawable.setBounds(0, 0, w, h)
        drawable.start()
        val frames = ArrayList<Bitmap>()
        fun step() {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawable.draw(Canvas(bmp))
            frames.add(bmp)
            if (frames.size < maxFrames) {
                mainHandler.postDelayed({ step() }, intervalMs)
            } else {
                drawable.stop()
                Thread {
                    val result = runCatching { encodeCaptured(frames, out) }
                    runOnUiThread { onConvertDone(result, out) }
                }.start()
            }
        }
        mainHandler.post { step() }
    }

    private fun encodeCaptured(frames: List<Bitmap>, out: File) {
        // 去重（动图循环抓取会产生重复帧）
        val kept = ArrayList<Bitmap>()
        var lastHash = Int.MIN_VALUE
        for (b in frames) {
            val h = bitmapHash(b)
            if (h != lastHash) { kept.add(b); lastHash = h }
        }
        if (kept.isEmpty()) throw IllegalStateException("no frames")
        if (kept.size == 1) {
            GifEncoder.encodeSingle(out, kept[0])
        } else {
            GifEncoder.write(
                out, kept[0].width, kept[0].height, intArrayOf(),
                loopForever = true,
                frames = kept.asSequence().map { GifEncoder.Frame(it, 10) },
                dither = true, perFramePalette = true,
            )
        }
    }

    companion object {
        /** 供工具页直接跳转。 */
        fun launch(activity: Activity) {
            activity.startActivity(android.content.Intent(activity, GifConvertActivity::class.java))
        }
    }
}
