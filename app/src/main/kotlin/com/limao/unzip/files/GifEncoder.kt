package com.limao.unzip.files

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.HashMap

/**
 * 纯 Kotlin GIF89a 编码器（零第三方依赖）：
 *  - 调色板：5-6-5 量化直方图 + 中位切分（计数加权），全局共享调色板，最多 256 色
 *  - 映射：普通模式按精确颜色缓存直查（毫秒级/帧）；高清模式 Floyd-Steinberg 抖动消除色带
 *  - LZW：经典 Weiner/GIFLIB 算法位级实现，码长 12 位自动复位
 *  - 动画：多帧 + Graphic Control Extension 帧延时 + NETSCAPE2.0 循环
 *
 * 两阶段 API 支持流式转换视频（同一时刻仅一帧在内存，不 OOM）：
 *  1. [PaletteBuilder.feed] 喂入各帧采样 → [PaletteBuilder.build] 得全局调色板
 *  2. [write] 以惰性 Sequence 逐帧取图写出
 */
object GifEncoder {

    class Frame(val bitmap: Bitmap, val delayCs: Int)

    /** 阶段一：从若干帧采样构建全局调色板。 */
    class PaletteBuilder {
        private val hist = HashMap<Int, Int>()

        /** 喂入一帧（内部按 5-6-5 量化采样，内存有界）。 */
        fun feed(bitmap: Bitmap) {
            val w = bitmap.width
            val h = bitmap.height
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, 0, 0, w, h)
            val stride = maxOf(1, Math.sqrt(w.toDouble() * h / 24_000.0).toInt())
            var i = 0
            val keys = IntArray(((w + stride - 1) / stride) * ((h + stride - 1) / stride))
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val p = px[y * w + x]
                    keys[i++] = ((p ushr 19) shl 11) or (((p ushr 10) and 0x3F) shl 5) or ((p ushr 3) and 0x1F)
                    x += stride
                }
                y += stride
            }
            for (j in 0 until i) hist[keys[j]] = (hist[keys[j]] ?: 0) + 1
        }

        /** 中位切分构建 ≤256 色全局调色板（ARGB 不透明色）。 */
        fun build(): IntArray {
            if (hist.isEmpty()) return intArrayOf(Color.BLACK, Color.WHITE)
            val colors = IntArray(hist.size)
            val counts = IntArray(hist.size)
            var i = 0
            for ((k, c) in hist) {
                colors[i] = k; counts[i] = c; i++
            }
            val palette = medianCut(colors, counts, 256)
            return if (palette.size < 2) intArrayOf(palette[0], Color.WHITE) else palette
        }
    }

    /** 阶段二：写出 GIF 文件（单帧静态或逐帧动画）。 */
    fun write(out: File, width: Int, height: Int, palette: IntArray, loopForever: Boolean, frames: Sequence<Frame>, dither: Boolean = false) {
        FileOutputStream(out).use { fos ->
            writeStream(fos, width, height, palette, loopForever, frames, dither)
        }
    }

    fun writeStream(out: OutputStream, width: Int, height: Int, palette: IntArray, loopForever: Boolean, frames: Sequence<Frame>, dither: Boolean) {
        out.write("GIF89a".toByteArray(Charsets.ISO_8859_1))
        writeLE16(out, width); writeLE16(out, height)
        out.write(0xF7) // 全局色表标志 + 256 项
        out.write(0); out.write(0)
        for (c in palette) { out.write(Color.red(c)); out.write(Color.green(c)); out.write(Color.blue(c)) }
        repeat((256 - palette.size).coerceAtLeast(0) * 3) { out.write(0) }
        if (loopForever) {
            out.write(0x21); out.write(0xFF); out.write(0x0B)
            out.write("NETSCAPE2.0".toByteArray(Charsets.ISO_8859_1))
            out.write(0x03); out.write(0x01); writeLE16(out, 0); out.write(0)
        }
        val cache = HashMap<Int, Int>()
        for (f in frames) {
            writeFrame(out, f.bitmap, f.delayCs, palette, cache, dither)
        }
        out.write(0x3B) // trailer
    }

    /** 静态单帧 GIF（全分辨率、按该图构建最优调色板）。 */
    fun encodeSingle(out: File, bitmap: Bitmap) {
        val pb = PaletteBuilder(); pb.feed(bitmap)
        val w = bitmap.width; val h = bitmap.height
        write(out, w, h, pb.build(), loopForever = false, frames = sequenceOf(Frame(bitmap, 0)))
    }

    // ---------- 帧写出 ----------

    private fun writeFrame(out: OutputStream, bmp: Bitmap, delayCs: Int, palette: IntArray, cache: HashMap<Int, Int>, dither: Boolean) {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val indices = if (dither) mapDither(pixels, w, h, palette) else mapFast(pixels, palette, cache)

        out.write(0x21); out.write(0xF9); out.write(0x04)
        out.write(0x00) // packed：无处置法、无透明色
        writeLE16(out, delayCs.coerceIn(0, 65535))
        out.write(0x00); out.write(0x00)
        out.write(0x2C) // image descriptor
        writeLE16(out, 0); writeLE16(out, 0)
        writeLE16(out, w); writeLE16(out, h)
        out.write(0x00) // 无局部色表、不交错
        lzwAndWrite(indices, palette.size, out)
    }

    private fun mapFast(pixels: IntArray, palette: IntArray, cache: HashMap<Int, Int>): ByteArray {
        val out = ByteArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            var idx = cache[p]
            if (idx == null) {
                idx = nearestIndex(palette, (p ushr 16) and 0xFF, (p ushr 8) and 0xFF, p and 0xFF)
                cache[p] = idx
            }
            out[i] = idx.toByte()
        }
        return out
    }

    /** Floyd-Steinberg 抖动映射（高清模式，消除渐变色带）。 */
    private fun mapDither(pixels: IntArray, w: Int, h: Int, palette: IntArray): ByteArray {
        val out = ByteArray(w * h)
        val re = FloatArray(w + 2)
        val ge = FloatArray(w + 2)
        val be = FloatArray(w + 2)
        val re2 = FloatArray(w + 2)
        val ge2 = FloatArray(w + 2)
        val be2 = FloatArray(w + 2)
        for (y in 0 until h) {
            java.util.Arrays.fill(re2, 0f); java.util.Arrays.fill(ge2, 0f); java.util.Arrays.fill(be2, 0f)
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                val r = ((p ushr 16) and 0xFF) + re[x + 1]
                val g = ((p ushr 8) and 0xFF) + ge[x + 1]
                val b = (p and 0xFF) + be[x + 1]
                val ri = r.coerceIn(0f, 255f).toInt()
                val gi = g.coerceIn(0f, 255f).toInt()
                val bi = b.coerceIn(0f, 255f).toInt()
                val idx = nearestIndex(palette, ri, gi, bi)
                out[y * w + x] = idx.toByte()
                val pc = palette[idx]
                val er = ri - Color.red(pc)
                val eg = gi - Color.green(pc)
                val eb = bi - Color.blue(pc)
                re[x + 2] += er * 7f / 16f; ge[x + 2] += eg * 7f / 16f; be[x + 2] += eb * 7f / 16f
                if (x > 0) {
                    re2[x] += er * 3f / 16f; ge2[x] += eg * 3f / 16f; be2[x] += eb * 3f / 16f
                }
                re2[x + 1] += er * 5f / 16f; ge2[x + 1] += eg * 5f / 16f; be2[x + 1] += eb * 5f / 16f
                re2[x + 2] += er / 16f; ge2[x + 2] += eg / 16f; be2[x + 2] += eb / 16f
            }
            System.arraycopy(re2, 0, re, 0, w + 2)
            System.arraycopy(ge2, 0, ge, 0, w + 2)
            System.arraycopy(be2, 0, be, 0, w + 2)
        }
        return out
    }

    private fun nearestIndex(palette: IntArray, r: Int, g: Int, b: Int): Int {
        var best = 0
        var bestD = Int.MAX_VALUE
        for (i in palette.indices) {
            val c = palette[i]
            val dr = r - Color.red(c)
            val dg = g - Color.green(c)
            val db = b - Color.blue(c)
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) { bestD = d; best = i; if (d == 0) break }
        }
        return best
    }

    // ---------- 中位切分 ----------

    private class Box(val colors: IntArray, val counts: IntArray, val from: Int, val to: Int) // [from, to)

    private fun medianCut(allColors: IntArray, allCounts: IntArray, maxColors: Int): IntArray {
        // 按亮度预排序，方便首轮切分
        val order = allColors.indices.sortedBy { lum(allColors[it]) }
        val colors = IntArray(order.size); val counts = IntArray(order.size)
        for (i in order.indices) { colors[i] = allColors[order[i]]; counts[i] = allCounts[order[i]] }
        val boxes = ArrayList<Box>()
        boxes.add(Box(colors, counts, 0, colors.size))
        while (boxes.size < maxColors) {
            var bestIdx = -1
            var bestVol = 0
            for (i in boxes.indices) {
                val b = boxes[i]
                if (b.to - b.from < 2) continue
                val vol = weightedVolume(b)
                if (vol > bestVol) { bestVol = vol; bestIdx = i }
            }
            if (bestIdx < 0) break
            val box = boxes.removeAt(bestIdx)
            val (b1, b2) = splitBox(box)
            boxes.add(b1); boxes.add(b2)
        }
        val palette = IntArray(boxes.size)
        for (i in boxes.indices) palette[i] = boxAverage(boxes[i])
        return palette
    }

    // 5-6-5 量化键 → 8bit 分量
    private fun keyR(k: Int) = ((k ushr 11) and 0x1F) * 255 / 31
    private fun keyG(k: Int) = ((k ushr 5) and 0x3F) * 255 / 63
    private fun keyB(k: Int) = (k and 0x1F) * 255 / 31
    private fun lum(k: Int) = keyR(k) * 2 + keyG(k) * 4 + keyB(k)

    private fun weightedVolume(b: Box): Int {
        var rMin = 255; var rMax = 0; var gMin = 255; var gMax = 0; var bMin = 255; var bMax = 0
        for (i in b.from until b.to) {
            val c = b.colors[i]
            val r = keyR(c); val g = keyG(c); val bl = keyB(c)
            if (r < rMin) rMin = r; if (r > rMax) rMax = r
            if (g < gMin) gMin = g; if (g > gMax) gMax = g
            if (bl < bMin) bMin = bl; if (bl > bMax) bMax = bl
        }
        val dr = rMax - rMin; val dg = gMax - gMin; val db = bMax - bMin
        val maxCh = maxOf(dr, dg, db)
        // 权重用盒内像素总数放大"大盒子"
        var total = 0L
        for (i in b.from until b.to) total += b.counts[i]
        return maxCh * maxOf(1L, total shr 12).toInt()
    }

    private fun splitBox(box: Box): Pair<Box, Box> {
        // 找最长通道，按该通道在窗口内原地排序 [from, to)（窗口互不相交，原地安全）
        var rMin = 255; var rMax = 0; var gMin = 255; var gMax = 0; var bMin = 255; var bMax = 0
        for (i in box.from until box.to) {
            val c = box.colors[i]
            val r = keyR(c); val g = keyG(c); val bl = keyB(c)
            if (r < rMin) rMin = r; if (r > rMax) rMax = r
            if (g < gMin) gMin = g; if (g > gMax) gMax = g
            if (bl < bMin) bMin = bl; if (bl > bMax) bMax = bl
        }
        val dr = rMax - rMin; val dg = gMax - gMin; val db = bMax - bMin
        val channel = if (dg >= dr && dg >= db) 1 else if (dr >= db) 0 else 2
        val n = box.to - box.from
        val order = (0 until n).sortedBy { o ->
            val c = box.colors[box.from + o]
            when (channel) {
                0 -> keyR(c); 1 -> keyG(c); else -> keyB(c)
            }
        }
        val tmpC = IntArray(n); val tmpN = IntArray(n)
        for (o in 0 until n) { tmpC[o] = box.colors[box.from + order[o]]; tmpN[o] = box.counts[box.from + order[o]] }
        for (o in 0 until n) { box.colors[box.from + o] = tmpC[o]; box.counts[box.from + o] = tmpN[o] }
        // 计数加权中位切分
        var total = 0L
        for (i in box.from until box.to) total += box.counts[i]
        var acc = 0L
        var mid = box.from
        while (mid < box.to - 1) {
            acc += box.counts[mid]
            if (acc * 2 >= total) break
            mid++
        }
        val left = Box(box.colors, box.counts, box.from, mid + 1)
        val right = Box(box.colors, box.counts, mid + 1, box.to)
        return left to right
    }

    private fun boxAverage(b: Box): Int {
        var rs = 0L; var gs = 0L; var bs = 0L; var total = 0L
        for (i in b.from until b.to) {
            val c = b.colors[i]
            val cnt = b.counts[i].toLong()
            rs += keyR(c) * cnt; gs += keyG(c) * cnt; bs += keyB(c) * cnt
            total += cnt
        }
        if (total == 0L) total = 1
        return Color.rgb((rs / total).toInt(), (gs / total).toInt(), (bs / total).toInt())
    }

    // ---------- LZW ----------

    private fun lzwAndWrite(indices: ByteArray, paletteSize: Int, out: OutputStream) {
        var minCodeSize = 2
        while ((1 shl minCodeSize) < paletteSize && minCodeSize < 8) minCodeSize++
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        var codeSize = minCodeSize + 1
        var maxCode = (1 shl codeSize) - 1
        var freeEnt = clear + 2
        val dict = HashMap<Int, Int>()
        var cur = 0
        var curBits = 0
        val buf = ByteArrayOutputStream(65536)

        fun emit(code: Int) {
            cur = cur or (code shl curBits)
            curBits += codeSize
            while (curBits >= 8) {
                buf.write(cur and 0xFF)
                cur = cur ushr 8
                curBits -= 8
            }
            // 写出后按 GIF 规则尝试提升码长（Weiner/GIFLIB 语义）
            if (freeEnt > maxCode && codeSize < 12) {
                codeSize++
                maxCode = (1 shl codeSize) - 1
            }
        }

        emit(clear)
        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val k = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val found = dict[key]
            if (found != null) {
                prefix = found
            } else {
                emit(prefix)
                if (freeEnt < 4096) { dict[key] = freeEnt; freeEnt++ }
                if (freeEnt >= 4096) {
                    emit(clear)
                    dict.clear()
                    freeEnt = clear + 2
                    codeSize = minCodeSize + 1
                    maxCode = (1 shl codeSize) - 1
                }
                prefix = k
            }
        }
        emit(prefix)
        emit(eoi)
        if (curBits > 0) buf.write(cur and 0xFF)

        // 按子块（≤255 字节）写出
        out.write(minCodeSize)
        val bytes = buf.toByteArray()
        var off = 0
        while (off < bytes.size) {
            val n = minOf(255, bytes.size - off)
            out.write(n)
            out.write(bytes, off, n)
            off += n
        }
        out.write(0x00) // 子块结束
    }

    private fun writeLE16(out: OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }
}
