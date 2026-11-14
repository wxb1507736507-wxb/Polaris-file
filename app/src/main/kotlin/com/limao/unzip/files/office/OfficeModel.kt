package com.limao.unzip.files.office

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * 内建 Office 渲染的中间模型：解析产物是一列「块」，由 OfficeViewerActivity 按块渲染。
 * 零第三方依赖：OOXML(zip + XmlPullParser) 直接解析，与项目架构一致。
 */
sealed class OfficeBlock {
    /** 富文本段：spans 带样式；bullet 非空表示列表项前缀 */
    data class Para(
        val spans: List<Span>,
        val align: Int = ALIGN_LEFT, // 0 左 1 中 2 右
        val bullet: String? = null,
        val indentLevel: Int = 0,
        val spaceAfterDp: Int = 6,
    ) : OfficeBlock()

    /** 表格：每行若干单元格（合并单元格简化为等宽栅格） */
    data class Table(val rows: List<Row>) : OfficeBlock() {
        data class Row(val cells: List<List<Span>>)
    }

    /** 内嵌图片（懒解码：zip 内路径 + 采样上限） */
    data class Image(val zipPath: String, val maxDim: Int = 1080) : OfficeBlock()

    /** pptx 幻灯片：形状按原始坐标摆放 */
    data class Slide(
        val index: Int,
        val total: Int,
        val shapes: List<Shape>,
        val slideWEmu: Long,
        val slideHEmu: Long,
    ) : OfficeBlock() {
        data class Shape(
            val xEmu: Long, val yEmu: Long, val wEmu: Long, val hEmu: Long,
            val text: List<TextLine> = emptyList(),
            val imagePath: String? = null,
            val align: Int = ALIGN_LEFT,
        )

        data class TextLine(val spans: List<Span>, val align: Int = ALIGN_LEFT)
    }

    /** 提示块（不支持的内容/截断说明） */
    data class Notice(val text: String) : OfficeBlock()

    /** xlsx 单张工作表的网格数据（含列宽/截断标记），渲染为可滚动表格 */
    data class SheetHeader(
        val name: String,
        val index: Int,
        val rows: List<List<SheetCell?>>,
        val colCount: Int,
        val colWidthDp: List<Int>,
        val truncated: Boolean,
        val rowCount: Int,
    ) : OfficeBlock()

    data class SheetCell(val text: String, val bold: Boolean)

    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val sizeSp: Float? = null, // 字号（sp）
        val color: Int? = null, // ARGB
        val highlight: Int? = null, // 高亮背景 ARGB
        val mono: Boolean = false, // 等宽（代码风格）
    )

    companion object {
        const val ALIGN_LEFT = 0
        const val ALIGN_CENTER = 1
        const val ALIGN_RIGHT = 2
    }
}

/** OOXML 解析公共工具 */
object OfficeUtil {
    /** 解析 zip 内 rels 文件：rId → zip 内目标路径（归一化为 zip entry 名） */
    fun loadRels(zip: OfficeSource, relsPath: String, baseDir: String): HashMap<String, String> {
        val map = HashMap<String, String>()
        if (!zip.has(relsPath)) return map
        val p = newParser(zip.stream(relsPath) ?: return map)
        loop@ while (true) {
            val ev = p.eventType
            if (ev == org.xmlpull.v1.XmlPullParser.START_TAG && local(p.name) == "Relationship") {
                val id = attr(p, "Id")
                val target = attr(p, "Target")
                val mode = attr(p, "TargetMode")
                if (id != null && target != null && mode != "External") {
                    map[id] = resolve(baseDir, target)
                }
            } else if (ev == org.xmlpull.v1.XmlPullParser.END_DOCUMENT) break@loop
            p.next()
        }
        return map
    }

    /** 相对路径 → zip entry 名（处理 ../ 与前导 /） */
    fun resolve(baseDir: String, target: String): String {
        var t = target.removePrefix("/")
        val segs = ArrayList<String>()
        for (s in t.split('/')) {
            when (s) {
                "", "." -> {}
                ".." -> if (segs.isNotEmpty()) segs.removeAt(segs.size - 1)
                else -> segs.add(s)
            }
        }
        val joined = segs.joinToString("/")
        return if (t.startsWith("xl/") || t.startsWith("word/") || t.startsWith("ppt/") ||
            t.startsWith("docProps/") || t.startsWith("[trash]")
        ) joined
        else if (baseDir.isEmpty()) joined
        else "$baseDir/$joined"
    }

    fun newParser(input: java.io.InputStream): org.xmlpull.v1.XmlPullParser {
        val p = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(input, null)
        return p
    }

    /** 去掉命名空间前缀（兼容任意前缀写法） */
    fun local(name: String?): String = name?.substringAfterLast(':') ?: ""

    fun attr(p: org.xmlpull.v1.XmlPullParser, name: String): String? {
        for (i in 0 until p.attributeCount) {
            if (local(p.getAttributeName(i)) == local(name)) return p.getAttributeValue(i)
        }
        return null
    }

    /** 十六进制颜色（RRGGBB 或 AARRGGBB / 自动色省略）→ ARGB，失败返回 null */
    fun colorOf(hex: String?): Int? {
        if (hex.isNullOrBlank()) return null
        return runCatching {
            when (hex.length) {
                6 -> (0xFF000000L.toInt()) or hex.toLong(16).toInt()
                8 -> hex.toLong(16).toInt()
                3 -> (0xFF000000L.toInt()) or (
                    ((hex[0].digitToIntOrNull(16) ?: return null) * 17 shl 16) or
                        ((hex[1].digitToIntOrNull(16) ?: return null) * 17 shl 8) or
                        ((hex[2].digitToIntOrNull(16) ?: return null) * 17)
                    )
                else -> null
            }
        }.getOrNull()
    }

    /** 从 zip 懒解码图片：按 maxDim 采样，失败返回 null */
    fun decodeImage(zip: OfficeSource, entryPath: String, maxDim: Int): Bitmap? {
        if (!zip.has(entryPath)) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zip.stream(entryPath)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            zip.stream(entryPath)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }

    /** EMU → dp（1dp = 1/160 inch；1inch = 914400 EMU） */
    fun emuToDp(emu: Long): Int = (emu / 5715L).toInt()

    /** 半点（half-point）→ sp */
    fun halfPointToSp(hp: Int): Float = hp / 2f

    /** 魔数嗅探结果 */
    enum class Kind { OOXML, OLE2_LEGACY, UNKNOWN }

    /** 读文件头判断真实类型：扩展名不可信（大量"docx"实为 OLE2 老格式） */
    fun sniff(file: File): Kind {
        val head = ByteArray(8)
        runCatching {
            java.io.FileInputStream(file).use { it.read(head) }
        }
        val hex = head.joinToString("") { String.format("%02X", it) }
        return when {
            hex.startsWith("504B0304") || hex.startsWith("504B0506") || hex.startsWith("504B0708") -> Kind.OOXML
            hex.startsWith("D0CF11E0A1B11AE1") -> Kind.OLE2_LEGACY
            else -> Kind.UNKNOWN
        }
    }
}

/**
 * OOXML 包访问抽象：优先 java.util.zip.ZipFile（随机访问）；
 * 构造失败（WPS 流式写入 data descriptor、非标准布局等）时回退
 * ZipInputStream 全量扫描到内存（有总量上限），保证非标准 zip 也能解析。
 */
class OfficeSource private constructor(
    private val zip: ZipFile?,
    private val data: Map<String, ByteArray>?,
) : java.io.Closeable {

    fun has(name: String): Boolean =
        if (zip != null) zip.getEntry(name) != null else data?.containsKey(name) == true

    fun names(): List<String> =
        if (zip != null) zip.entries().asSequence().map { it.name }.toList()
        else data?.keys?.toList() ?: emptyList()

    fun stream(name: String): java.io.InputStream? =
        if (zip != null) {
            val e = zip.getEntry(name) ?: return null
            zip.getInputStream(e)
        } else {
            data?.get(name)?.let { java.io.ByteArrayInputStream(it) }
        }

    override fun close() {
        runCatching { zip?.close() }
    }

    companion object {
        private const val MAX_TOTAL = 96L * 1024 * 1024 // 回退扫描总量上限
        private const val MAX_ENTRY = 32L * 1024 * 1024 // 单条目上限

        fun open(file: File): OfficeSource {
            // 非真实 zip（OLE2/损坏）直接抛出，由调用方按嗅探结果给提示
            val zf = ZipFile(file)
            return try {
                // 探测一次可读性
                zf.entries().hasMoreElements()
                OfficeSource(zf, null)
            } catch (t: Throwable) {
                runCatching { zf.close() }
                scanFallback(file)
            }
        }

        /** ZipInputStream 流式扫描回退（容错 data descriptor / 非标准头） */
        private fun scanFallback(file: File): OfficeSource {
            val map = HashMap<String, ByteArray>()
            var total = 0L
            java.util.zip.ZipInputStream(java.io.BufferedInputStream(java.io.FileInputStream(file), 256 * 1024)).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val name = e.name
                    if (name.isEmpty() || e.isDirectory) { zis.closeEntry(); continue }
                    if (e.size > MAX_ENTRY || total + e.size > MAX_TOTAL) { zis.closeEntry(); continue }
                    val buf = ByteArrayOutputStream(maxOf(4096, e.size.toInt().coerceAtMost(1 shl 20)))
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = zis.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                    }
                    map[name] = buf.toByteArray()
                    total += buf.size()
                    zis.closeEntry()
                }
            }
            if (map.isEmpty()) throw java.io.IOException("zip 回退扫描失败")
            return OfficeSource(null, map)
        }
    }
}
