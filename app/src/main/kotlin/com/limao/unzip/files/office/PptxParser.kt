package com.limao.unzip.files.office

import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/**
 * PPTX（PowerPoint）解析：slideN.xml 形状树 → 文本框/图片按原始 EMU 坐标定位，
 * 幻灯片尺寸来自 presentation.xml；每页渲染为一张按比例缩放的卡片。
 */
object PptxParser {

    fun parse(zip: OfficeSource, onProgress: (Int) -> Unit = {}): List<OfficeBlock> {
        // 幻灯片尺寸（EMU）
        var slideW = 12192000L
        var slideH = 6858000L
        if (zip.has("ppt/presentation.xml")) {
            val p = OfficeUtil.newParser(zip.stream("ppt/presentation.xml")!!)
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && OfficeUtil.local(p.name) == "sldSz") {
                    OfficeUtil.attr(p, "cx")?.toLongOrNull()?.let { slideW = it }
                    OfficeUtil.attr(p, "cy")?.toLongOrNull()?.let { slideH = it }
                }
                p.next()
            }
        }

        // 收集 slides（按文件序号排序，保持页面顺序）
        val slideNames = zip.names().asSequence()
            .filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            .sortedBy { it.substringAfterLast("slide").substringBefore('.').toIntOrNull() ?: 0 }
            .toList()
        if (slideNames.isEmpty()) return listOf(OfficeBlock.Notice("演示文稿中没有幻灯片"))

        val blocks = ArrayList<OfficeBlock>()
        val total = slideNames.size
        for ((i, path) in slideNames.withIndex()) {
            val num = path.substringAfterLast("slide").substringBefore('.').toIntOrNull() ?: (i + 1)
            val rels = OfficeUtil.loadRels(zip, "ppt/slides/_rels/slide$num.xml.rels", "ppt/slides")
            blocks.add(parseSlide(zip, path, num, total, slideW, slideH, rels, onProgress))
        }
        return blocks
    }

    private fun parseSlide(
        zip: OfficeSource,
        path: String,
        num: Int,
        total: Int,
        slideW: Long,
        slideH: Long,
        rels: Map<String, String>,
        onProgress: (Int) -> Unit,
    ): OfficeBlock {
        val shapes = ArrayList<OfficeBlock.Slide.Shape>()
        if (!zip.has(path)) return OfficeBlock.Notice("幻灯片 $num 缺失")

        // 逐形状状态
        var x = 0L; var y = 0L; var w = 0L; var h = 0L
        var inXfrm = false
        var inTxBody = false
        var align = 0
        val lines = ArrayList<OfficeBlock.Slide.TextLine>()
        val spans = ArrayList<OfficeBlock.Span>()
        var bold = false; var italic = false
        var sizeHp = 1800 // 默认 18pt（百分之一点单位）
        var color: Int? = null
        var picRid: String? = null

        fun flushLine() {
            if (spans.isNotEmpty()) {
                lines.add(OfficeBlock.Slide.TextLine(ArrayList(spans), align))
                spans.clear()
            }
            align = 0
        }

        fun flushShape() {
            flushLine()
            if (lines.isNotEmpty() || w > 0) {
                shapes.add(
                    OfficeBlock.Slide.Shape(
                        xEmu = x, yEmu = y, wEmu = w, hEmu = h,
                        text = lines.toList(),
                        align = 0,
                    ),
                )
            }
            lines.clear()
        }

        val p = OfficeUtil.newParser(zip.stream(path)!!)
        // 元素栈：TEXT 期间 getName() 不可靠（kxml2 预读），以栈顶为准
        val elementStack = ArrayDeque<String>()
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            val ev = p.eventType
            val n = OfficeUtil.local(p.name)
            when (ev) {
                XmlPullParser.START_TAG -> {
                    elementStack.addLast(n)
                    when (n) {
                        "sp" -> { x = 0; y = 0; w = 0; h = 0; lines.clear(); spans.clear() }
                        "pic" -> { x = 0; y = 0; w = 0; h = 0; picRid = null }
                        "xfrm" -> inXfrm = true
                        "off" -> if (inXfrm) {
                            x = OfficeUtil.attr(p, "x")?.toLongOrNull() ?: 0
                            y = OfficeUtil.attr(p, "y")?.toLongOrNull() ?: 0
                        }
                        "ext" -> if (inXfrm) {
                            w = OfficeUtil.attr(p, "cx")?.toLongOrNull() ?: 0
                            h = OfficeUtil.attr(p, "cy")?.toLongOrNull() ?: 0
                        }
                        "txBody" -> inTxBody = true
                        "pPr" -> {
                            when (OfficeUtil.attr(p, "algn")?.lowercase(Locale.ROOT)) {
                                "ctr" -> align = OfficeBlock.ALIGN_CENTER
                                "r" -> align = OfficeBlock.ALIGN_RIGHT
                            }
                        }
                        "rPr" -> {
                            OfficeUtil.attr(p, "sz")?.toIntOrNull()?.let { if (it > 0) sizeHp = it }
                            bold = OfficeUtil.attr(p, "b") == "1"
                            italic = OfficeUtil.attr(p, "i") == "1"
                            color = null
                        }
                        "srgbClr" -> {
                            color = OfficeUtil.colorOf(OfficeUtil.attr(p, "val"))
                        }
                        "blip" -> {
                            picRid = OfficeUtil.attr(p, "r:embed")?.let { rels[it] }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inTxBody && elementStack.lastOrNull() == "t" && p.text.isNotEmpty()) {
                        spans.add(
                            OfficeBlock.Span(
                                text = p.text,
                                bold = bold,
                                italic = italic,
                                sizeSp = sizeHp / 100f,
                                color = color,
                            ),
                        )
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (elementStack.isNotEmpty()) elementStack.removeLast()
                    when (n) {
                        "xfrm" -> inXfrm = false
                        "p" -> if (inTxBody) flushLine()
                        "txBody" -> inTxBody = false
                        "sp" -> flushShape()
                        "pic" -> {
                            // blip 先于 xfrm 出现，坐标在 END "pic" 时已就绪
                            val img = picRid
                            if (img != null && w > 0) {
                                shapes.add(
                                    OfficeBlock.Slide.Shape(
                                        xEmu = x, yEmu = y, wEmu = w, hEmu = h,
                                        imagePath = img,
                                    ),
                                )
                            }
                            picRid = null
                        }
                    }
                }
            }
            p.next()
        }
        onProgress(num)
        return OfficeBlock.Slide(index = num, total = total, shapes = shapes, slideWEmu = slideW, slideHEmu = slideH)
    }
}
