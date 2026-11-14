package com.limao.unzip.files.office

import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipFile

/**
 * DOCX（Word）解析：document.xml + styles.xml + rels + media。
 * 样式还原：标题层级、字号/加粗/斜体/下划线/删除线/颜色/高亮、对齐、
 * 项目符号列表（bullet/十进制计数）、表格、内嵌图片（DrawingML 与旧式 VML 均支持）。
 */
object DocxParser {

    private val DEFAULT_SIZE_SP = 11f

    private class StyleDef(val sizeSp: Float?, val bold: Boolean)

    /** 运行期 run 属性栈帧 */
    private class RunState {
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var sizeSp: Float? = null
        var color: Int? = null
        var highlight: Int? = null
    }

    fun parse(zip: OfficeSource, onProgress: (Int) -> Unit = {}): List<OfficeBlock> {
        val styles = loadStyles(zip)
        val rels = OfficeUtil.loadRels(zip, "word/_rels/document.xml.rels", "word")
        val listPrefix = loadNumberingPrefixes(zip)

        val blocks = ArrayList<OfficeBlock>(256)
        if (!zip.has("word/document.xml")) return listOf(
            OfficeBlock.Notice("文档缺少 document.xml，可能已损坏"),
        )
        val p = OfficeUtil.newParser(zip.stream("word/document.xml")!!)

        // 段落累积状态
        val spans = ArrayList<OfficeBlock.Span>()
        var align = 0
        var bullet: String? = null
        var indentLevel = 0
        val counters = DecimalCounters()
        var curNumId = 0
        // 样式状态
        val runStack = ArrayDeque<RunState>()
        val paraStyleSizes = ArrayDeque<Float>()
        var paraSize = DEFAULT_SIZE_SP
        var styleId: String? = null
        // 表格状态
        var tableDepth = 0
        var tableRows = ArrayList<OfficeBlock.Table.Row>()
        var rowCells = ArrayList<List<OfficeBlock.Span>>()
        var cellSpans = ArrayList<OfficeBlock.Span>()
        // 段落属性捕获
        var inPPr = false
        var inRunProps = false
        // 元素上下文栈：TEXT 事件期间 getName() 可能返回下一个标签名（kxml2 预读），
        // 必须以栈顶判断文本归属，否则 w:t 的正文永远匹配不上
        val elementStack = ArrayDeque<String>()

        fun curRun(): RunState = runStack.lastOrNull() ?: RunState().also { runStack.addLast(it) }

        fun flushPara(intoCell: Boolean) {
            val merged = ArrayList<OfficeBlock.Span>(spans)
            spans.clear()
            if (intoCell) {
                cellSpans.addAll(merged)
            } else {
                val prefix = when (bullet) {
                    "#" -> "${counters.next(curNumId)}."
                    null -> null
                    else -> bullet
                }
                blocks.add(OfficeBlock.Para(merged, align, prefix, indentLevel))
                onProgress(blocks.size)
            }
            align = 0; bullet = null; indentLevel = 0; paraSize = DEFAULT_SIZE_SP
            styleId = null; curNumId = 0
        }

        fun startTag() {
            val n = OfficeUtil.local(p.name)
            when (n) {
                "document", "body" -> {}
                "p" -> {
                    if (tableDepth == 0) { align = 0; bullet = null; indentLevel = 0; paraSize = DEFAULT_SIZE_SP }
                }
                "pPr" -> inPPr = true
                "pStyle" -> {
                    styleId = OfficeUtil.attr(p, "w:val")
                    styles[styleId]?.let { st ->
                        st.sizeSp?.let { paraSize = it }
                    }
                }
                "jc" -> {
                    if (inPPr) {
                        when (OfficeUtil.attr(p, "w:val")?.lowercase()) {
                            "center" -> align = OfficeBlock.ALIGN_CENTER
                            "right" -> align = OfficeBlock.ALIGN_RIGHT
                        }
                    }
                }
                "numId" -> if (inPPr) curNumId = OfficeUtil.attr(p, "w:val")?.toIntOrNull() ?: 0
                "ilvl" -> if (inPPr) indentLevel = OfficeUtil.attr(p, "w:val")?.toIntOrNull() ?: 0
                "r" -> {
                    val rs = RunState()
                    rs.sizeSp = paraSize
                    runStack.addLast(rs)
                    inRunProps = false
                }
                "rPr" -> {
                    val rs = runStack.lastOrNull()
                    if (rs != null) inRunProps = true
                }
                "b" -> {
                    val rs = runStack.lastOrNull() ?: return
                    if (OfficeUtil.attr(p, "w:val") != "0") rs.bold = true
                }
                "i" -> {
                    val rs = runStack.lastOrNull() ?: return
                    if (OfficeUtil.attr(p, "w:val") != "0") rs.italic = true
                }
                "u" -> runStack.lastOrNull()?.let { it.underline = OfficeUtil.attr(p, "w:val") != "none" }
                "strike" -> runStack.lastOrNull()?.let { it.strike = OfficeUtil.attr(p, "w:val") != "0" }
                "color" -> {
                    if (inRunProps) runStack.lastOrNull()?.color =
                        OfficeUtil.colorOf(OfficeUtil.attr(p, "w:val"))
                }
                "highlight" -> {
                    if (inRunProps) {
                        val v = OfficeUtil.attr(p, "w:val")
                        if (v != null && v != "none") {
                            runStack.lastOrNull()?.highlight = OfficeUtil.colorOf(
                                when (v) {
                                    "yellow" -> "FFFF00"; "green" -> "00FF00"; "cyan" -> "00FFFF"
                                    "magenta" -> "FF00FF"; "blue" -> "66CCFF"; "red" -> "FF6666"
                                    "darkBlue" -> "3366CC"; "darkYellow" -> "CCCC33"; "darkGreen" -> "339933"
                                    "darkRed" -> "993333"; "lightGray" -> "DDDDDD"; "darkGray" -> "555555"
                                    "black" -> "333333"
                                    else -> "FFFF00"
                                },
                            )
                        }
                    }
                }
                "sz" -> if (inRunProps) {
                    OfficeUtil.attr(p, "w:val")?.toIntOrNull()?.let { hp ->
                        if (hp > 0) runStack.lastOrNull()?.sizeSp = OfficeUtil.halfPointToSp(hp)
                    }
                }
                "szCs" -> { /* 忽略 */ }
                "t" -> { /* TEXT 事件处理 */ }
                "br" -> if (!inRunProps) spans.add(OfficeBlock.Span("\n", sizeSp = runStack.lastOrNull()?.sizeSp ?: paraSize))
                "tab" -> if (!inRunProps) spans.add(OfficeBlock.Span("    ", sizeSp = runStack.lastOrNull()?.sizeSp ?: paraSize))
                "blip" -> {
                    if (tableDepth == 0) {
                        val rid = OfficeUtil.attr(p, "r:embed") ?: OfficeUtil.attr(p, "r:link")
                        val path = rid?.let { rels[it] }
                        if (path != null) {
                            flushPara(false)
                            blocks.add(OfficeBlock.Image(path))
                            onProgress(blocks.size)
                        }
                    }
                }
                "imagedata" -> {
                    if (tableDepth == 0) {
                        val rid = OfficeUtil.attr(p, "r:id")
                        val path = rid?.let { rels[it] }
                        if (path != null) {
                            flushPara(false)
                            blocks.add(OfficeBlock.Image(path))
                            onProgress(blocks.size)
                        }
                    }
                }
                "tbl" -> { tableDepth++; if (tableDepth == 1) tableRows.clear() }
                "tr" -> if (tableDepth == 1) rowCells.clear()
                "tc" -> if (tableDepth == 1) cellSpans.clear()
            }
        }

        fun endTag() {
            val n = OfficeUtil.local(p.name)
            when (n) {
                "pPr" -> {
                    inPPr = false
                    // 段落属性收尾：确定列表前缀
                    if (curNumId != 0) {
                        bullet = listPrefix[curNumId] ?: "•"
                    }
                }
                "rPr" -> inRunProps = false
                "r" -> runStack.removeLastOrNull()
                "p" -> {
                    // 表格单元格内段落并入 cellSpans；独立段落正常 flush
                    if (tableDepth == 0) flushPara(false) else flushPara(true)
                }
                "tc" -> if (tableDepth == 1) {
                    rowCells.add(ArrayList(cellSpans))
                    cellSpans = ArrayList()
                }
                "tr" -> if (tableDepth == 1) {
                    tableRows.add(OfficeBlock.Table.Row(ArrayList(rowCells)))
                    rowCells.clear()
                }
                "tbl" -> if (tableDepth == 1) {
                    if (tableRows.isNotEmpty()) {
                        blocks.add(OfficeBlock.Table(tableRows))
                        blocks.add(OfficeBlock.Para(emptyList(), spaceAfterDp = 10))
                        onProgress(blocks.size)
                    }
                    tableRows.clear()
                    tableDepth = 0
                } else if (tableDepth > 0) tableDepth--
            }
        }

        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    elementStack.addLast(OfficeUtil.local(p.name))
                    startTag()
                }
                XmlPullParser.END_TAG -> {
                    if (elementStack.isNotEmpty()) elementStack.removeLast()
                    endTag()
                }
                XmlPullParser.TEXT -> {
                    // 文本归属：以元素栈顶为准（kxml2 在 TEXT 期间 getName() 不可靠）
                    if (elementStack.lastOrNull() == "t") {
                        val rs = runStack.lastOrNull()
                        val text = p.text ?: ""
                        if (text.isNotEmpty()) {
                            spans.add(
                                OfficeBlock.Span(
                                    text = text,
                                    bold = rs?.bold ?: false,
                                    italic = rs?.italic ?: false,
                                    underline = rs?.underline ?: false,
                                    strike = rs?.strike ?: false,
                                    sizeSp = rs?.sizeSp ?: paraSize,
                                    color = rs?.color,
                                    highlight = rs?.highlight,
                                ),
                            )
                        }
                    }
                }
            }
            p.next()
        }
        if (spans.isNotEmpty()) flushPara(false)
        return blocks
    }

    private fun loadStyles(zip: OfficeSource): Map<String, StyleDef> {
        if (!zip.has("word/styles.xml")) return emptyMap()
        val map = HashMap<String, StyleDef>()
        val p = OfficeUtil.newParser(zip.stream("word/styles.xml")!!)
        var id: String? = null
        var nameVal: String? = null
        var sz: Int? = null
        var bold = false
        var inRpr = false
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            val ev = p.eventType
            val n = OfficeUtil.local(p.name)
            when (ev) {
                XmlPullParser.START_TAG -> when (n) {
                    "style" -> { id = OfficeUtil.attr(p, "w:styleId"); nameVal = null; sz = null; bold = false; inRpr = false }
                    "name" -> nameVal = OfficeUtil.attr(p, "w:val")?.lowercase()
                    "rPr" -> inRpr = true
                    "sz" -> if (inRpr) sz = OfficeUtil.attr(p, "w:val")?.toIntOrNull()
                    "b" -> if (inRpr && OfficeUtil.attr(p, "w:val") != "0") bold = true
                }
                XmlPullParser.END_TAG -> when (n) {
                    "rPr" -> inRpr = false
                    "style" -> {
                        val sid = id
                        if (sid != null) {
                            map[sid] = StyleDef(sz?.takeIf { it > 0 }?.let { OfficeUtil.halfPointToSp(it) }, bold)
                        }
                    }
                }
            }
            p.next()
        }
        return map
    }

    /** numId → 列表前缀（bullet/decimal）；十进制计数器在解析段落时递增 */
    private fun loadNumberingPrefixes(zip: OfficeSource): Map<Int, String> {
        if (!zip.has("word/numbering.xml")) return emptyMap()
        val result = HashMap<Int, String>()
        val p = OfficeUtil.newParser(zip.stream("word/styles.xml")!!)
        var numId = -1
        var absRef = -1
        var absId = -1
        var fmt: String? = null
        val absFmt = HashMap<Int, String>()
        var inNum = false
        var inAbs = false
        var inLvl = false
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            val ev = p.eventType
            val n = OfficeUtil.local(p.name)
            when (ev) {
                XmlPullParser.START_TAG -> when (n) {
                    "num" -> { numId = OfficeUtil.attr(p, "w:numId")?.toIntOrNull() ?: -1; inNum = true }
                    "abstractNumId" -> if (inNum) absRef = OfficeUtil.attr(p, "w:val")?.toIntOrNull() ?: -1
                    "abstractNum" -> { inAbs = true; absId = OfficeUtil.attr(p, "w:abstractNumId")?.toIntOrNull() ?: -1 }
                    "lvl" -> if (inAbs) inLvl = true
                    "numFmt" -> if (inAbs && inLvl) fmt = OfficeUtil.attr(p, "w:val")
                }
                XmlPullParser.END_TAG -> when (n) {
                    "lvl" -> inLvl = false
                    "abstractNum" -> { if (fmt != null) absFmt[absId] = fmt; inAbs = false; fmt = null }
                    "num" -> { if (numId >= 0 && absRef >= 0) result[numId] = absFmt[absRef] ?: "bullet"; inNum = false; numId = -1; absRef = -1 }
                }
            }
            p.next()
        }
        // 前缀形态映射（decimal 由渲染时计数）
        return result.entries.associate { (id, fmt) ->
            id to if (fmt == "bullet" || fmt == null) "•" else "#"
        }
    }

    /** 十进制列表计数器（供渲染层使用：numId → 当前计数） */
    class DecimalCounters {
        private val counters = HashMap<Int, Int>()
        fun next(numId: Int): Int = (counters[numId] ?: 0).also { counters[numId] = it + 1 } + 1
    }
}
