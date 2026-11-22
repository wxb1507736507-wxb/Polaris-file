package com.limao.unzip.files

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaCodecInfo
import android.net.Uri

/**
 * MediaCodec 缓冲区模式（无 Surface）抽帧器：
 * 硬件解码器输出厂商私有像素格式时（如 MTK 0x7f000001），ImageReader/Surface
 * 路径会因格式不匹配失败；本类走 ByteBuffer 输出 + flexible YUV（getOutputImage），
 * 由软件完成 YUV→RGB，兼容所有解码器（硬件不支持时自动回落到软件解码器）。
 */
class CodecFrameExtractor(
    context: Context,
    uri: Uri,
) : AutoCloseable {

    private val extractor = MediaExtractor()

    init {
        extractor.setDataSource(context, uri, null)
    }

    /** 顺序解码并按 fps 网格采样；返回帧数组（下标=网格序号，失败项为 null） */
    fun sampleGrid(
        outW: Int,
        outH: Int,
        fps: Int,
        durationSec: Int,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Array<Bitmap?> {
        var track = -1
        var trackFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                track = i; trackFormat = f; break
            }
        }
        val fmt = trackFormat ?: throw IllegalStateException("no video track")
        extractor.selectTrack(track)

        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val srcW = fmt.getInteger(MediaFormat.KEY_WIDTH)
        val srcH = fmt.getInteger(MediaFormat.KEY_HEIGHT)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val useUs = minOf(durUs, durationSec * 1_000_000L)
        val count = ((useUs.toDouble() / 1_000_000.0) * fps).toInt().coerceIn(1, 600)
        val grid = arrayOfNulls<Bitmap>(count)
        val stepUs = 1_000_000L / fps

        // 选择支持 flexible YUV（ByteBuffer 输出）的解码器：
        // 软件解码器（c2.android.* / OMX.google.*）优先——硬件解码器多为厂商私有
        // 输出格式，getOutputImage 会返回 null；软件解码保证可读的 YUV_420_888
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val candidates = list.codecInfos.filter { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals(mime, true) } &&
                runCatching { info.getCapabilitiesForType(mime) }.getOrNull()?.colorFormats?.any {
                    it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                } == true
        }.sortedByDescending { info ->
            val n = info.name.lowercase()
            if (n.startsWith("c2.android") || n.startsWith("omx.google") || n.contains(".sw.")) 1 else 0
        }
        var dec: MediaCodec? = null
        for (info in candidates) {
            val m = MediaFormat.createVideoFormat(mime, srcW, srcH)
            if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                m.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
            m.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            try {
                dec = MediaCodec.createByCodecName(info.name).apply { configure(m, null, null, 0) }
                android.util.Log.d("CodecFrames", "decoder=${info.name}")
                break
            } catch (t: Throwable) {
                runCatching { dec?.release() }
                dec = null
            }
        }
        val codec = dec ?: throw IllegalStateException("no decoder supports flexible YUV")
        codec.start()

        val info = MediaCodec.BufferInfo()
        var inDone = false
        var idx = 0
        var nextGridUs = 0L
        var sawEos = false
        var guard = 0
        var inQueued = 0
        var outFrames = 0
        var imagesGot = 0
        try {
            while (idx < count && !sawEos && guard++ < 200_000) {
                if (!inDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(buf, 0)
                        if (sz >= 0) {
                            // 从首个样本顺序解码：extractor 起点即关键帧，无需额外 sync 标记
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                            inQueued++
                        } else {
                            android.util.Log.d("CodecFrames", "input EOS at sample #$inQueued")
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    outFrames++
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawEos = true
                        android.util.Log.d("CodecFrames", "output EOS: in=$inQueued out=$outFrames imgs=$imagesGot idx=$idx lastPts=${info.presentationTimeUs}")
                    }
                    val pts = info.presentationTimeUs
                    if (idx < count && pts >= nextGridUs && info.size > 0) {
                        val img = runCatching { codec.getOutputImage(outIdx) }.getOrNull()
                        if (img != null) {
                            imagesGot++
                            val bmp = runCatching { yuvToBitmap(img, srcW, srcH) }.getOrNull()
                            if (bmp != null) {
                                val scaled = if (bmp.width == outW && bmp.height == outH) bmp
                                else Bitmap.createScaledBitmap(bmp, outW, outH, true).also {
                                    if (it !== bmp) bmp.recycle()
                                }
                                grid[idx] = scaled
                                idx++
                                nextGridUs += stepUs
                                // 低帧率源：pts 大步跨越网格
                                while (idx < count && pts >= nextGridUs) { nextGridUs += stepUs; idx++ }
                                onProgress(idx, count)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    android.util.Log.d("CodecFrames", "out fmt: ${codec.outputFormat}")
                }
            }
            android.util.Log.d("CodecFrames", "loop end: idx=$idx in=$inQueued out=$outFrames imgs=$imagesGot eos=$sawEos guard=$guard")
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        if (idx == 0) throw IllegalStateException("no frames decoded")
        return grid
    }

    /** YUV_420_888 → ARGB（处理行/像素跨距与 crop） */
    private fun yuvToBitmap(img: android.media.Image, w: Int, h: Int): Bitmap? {
        val crop = img.cropRect
        val cw = crop.width().coerceAtMost(w)
        val ch = crop.height().coerceAtMost(h)
        if (cw <= 0 || ch <= 0) return null
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yBuf = yP.buffer; val uBuf = uP.buffer; val vBuf = vP.buffer
        val yRow = yP.rowStride; val uRow = uP.rowStride; val vRow = vP.rowStride
        val uPix = uP.pixelStride; val vPix = vP.pixelStride
        val pixels = IntArray(cw * ch)
        var p = 0
        val yBase = crop.top * yRow + crop.left
        val uBase = (crop.top / 2) * uRow + crop.left / 2
        val vBase = (crop.top / 2) * vRow + crop.left / 2
        for (j in 0 until ch) {
            var yIdx = yBase + j * yRow
            val uLine = uBase + (j / 2) * uRow
            val vLine = vBase + (j / 2) * vRow
            var i = 0
            while (i < cw) {
                val y = (yBuf.get(yIdx + i).toInt() and 0xFF)
                val u = (uBuf.get(uLine + (i / 2) * uPix).toInt() and 0xFF) - 128
                val v = (vBuf.get(vLine + (i / 2) * vPix).toInt() and 0xFF) - 128
                var r = y + 1436 * v / 1024
                var g = y - 352 * u / 1024 - 731 * v / 1024
                var b = y + 1815 * u / 1024
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                pixels[p++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                i++
            }
        }
        val bmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, cw, 0, 0, cw, ch)
        return bmp
    }

    override fun close() {
        runCatching { extractor.release() }
    }
}
