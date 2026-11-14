package com.limao.unzip.files.office

import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * XLSX（Excel）解析：workbook.xml + sharedStrings + sheetN.xml + styles.xml。
 * 输出每张表的行列文本（含日期格式化、表头加粗信息），供网格渲染。
 */
object XlsxParser {

    class SheetData(
        val name: String,
        val rows: List<List<OfficeBlock.SheetCell?>>, // 固定列数，短行补 null
        val colCount: Int,
        val colWidthDp: List<Int>,
        val truncated: Boolean,
        val rowCount: Int,
    )

    private const val MAX_ROWS = 1000
    private const val MAX_COLS = 60

    fun parse(zip: OfficeSource, onProgress: (Int) -> Unit = {}): List<OfficeBlock> {
        val shared = loadSharedStrings(zip)
        val boldFlags = loadBoldFlags(zip)
        val dateFmt = loadDateFormats(zip)

        // workbook: sheet 名 → rId
        if (!zip.has("xl/workbook.xml")) return listOf(OfficeBlock.Notice("缺少 workbook.xml"))
        val p = OfficeUtil.newParser(zip.stream("xl/workbook.xml")!!)
        val sheetNames = ArrayList<Pair<String, String>>() // name, rId
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && OfficeUtil.local(p.name) == "sheet") {
                val name = OfficeUtil.attr(p, "name") ?: "表"
                val rid = OfficeUtil.attr(p, "r:id") ?: OfficeUtil.attr(p, "id") ?: ""
                sheetNames.add(name to rid)
            }
            p.next()
        }
        val rels = OfficeUtil.loadRels(zip, "xl/_rels/workbook.xml.rels", "xl")

        val blocks = ArrayList<OfficeBlock>()
        var sheetIndex = 0
        for ((name, rid) in sheetNames) {
            val path = rels[rid] ?: continue
            if (!zip.has(path)) continue
            blocks.add(parseSheet(zip, path, name, shared, boldFlags, dateFmt, sheetIndex, onProgress))
            sheetIndex++
        }
        if (blocks.isEmpty()) return listOf(OfficeBlock.Notice("工作簿中没有工作表"))
        return blocks
    }

    /** 单张表 → SheetHeader 块（渲染层识别） */
    private fun parseSheet(
        zip: OfficeSource,
        entryPath: String,
        name: String,
        shared: List<String>,
        boldFlags: Set<Int>,
        dateFmt: Set<Int>,
        sheetIndex: Int,
        onProgress: (Int) -> Unit,
    ): OfficeBlock {
        val p = OfficeUtil.newParser(zip.stream(entryPath)!!)
        val grid = ArrayList<List<OfficeBlock.SheetCell?>>()
        var maxCol = 0
        // 列宽（字符数）
        val colW = HashMap<Int, Float>()
        var truncated = false
        var totalRows = 0

        // 逐行状态
        var rowCells = ArrayList<OfficeBlock.SheetCell?>()
        var curCol = 0
        var cellRef = ""
        var cellType = ""
        var cellStyle = 0
        var cellVal = StringBuilder()
        var inInlineStr = false
        var inlineVal = StringBuilder()

        fun colIndexOf(ref: String): Int {
            var col = 0
            for (ch in ref) {
                if (ch in 'A'..'Z') col = col * 26 + (ch - 'A' + 1)
                else if (ch.isLetter()) col = col * 26 + (ch.uppercaseChar() - 'A' + 1)
                else break
            }
            return col - 1 // 0-based
        }

        fun finishCell() {
            val v = cellVal.toString()
            val text: String = when (cellType) {
                "s" -> shared.getOrNull(v.toIntOrNull() ?: -1) ?: ""
                "inlineStr" -> inlineVal.toString()
                "b" -> if (v == "1") "TRUE" else "FALSE"
                else -> {
                    // 数值：若是日期格式则转换
                    val num = v.toDoubleOrNull()
                    if (num != null && cellStyle in dateFmt) formatSerialDate(num) else v
                }
            }
            val col = if (cellRef.isNotEmpty()) colIndexOf(cellRef) else curCol
            if (col >= 0) {
                while (rowCells.size <= col) rowCells.add(null)
                rowCells[col] = OfficeBlock.SheetCell(text, boldFlags.contains(cellStyle))
                if (col + 1 > maxCol) maxCol = col + 1
            }
            curCol = col + 1
            cellRef = ""; cellType = ""; cellStyle = 0; cellVal = StringBuilder(); inInlineStr = false
        }

        // 元素栈：TEXT 期间 getName() 不可靠（kxml2 预读），以栈顶为准
        val elementStack = ArrayDeque<String>()
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            val ev = p.eventType
            val n = OfficeUtil.local(p.name)
            when (ev) {
                XmlPullParser.START_TAG -> {
                    elementStack.addLast(n)
                    when (n) {
                        "row" -> { rowCells = ArrayList(); curCol = 0 }
                        "c" -> {
                            cellRef = OfficeUtil.attr(p, "r") ?: ""
                            cellType = OfficeUtil.attr(p, "t") ?: ""
                            cellStyle = OfficeUtil.attr(p, "s")?.toIntOrNull() ?: 0
                            cellVal = StringBuilder()
                        }
                        "is" -> inInlineStr = true
                        "v" -> { /* TEXT 事件 */ }
                    }
                }
                XmlPullParser.TEXT -> {
                    when (elementStack.lastOrNull()) {
                        "v" -> cellVal.append(p.text)
                        "t" -> if (inInlineStr) inlineVal.append(p.text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (elementStack.isNotEmpty()) elementStack.removeLast()
                    when (n) {
                        "is" -> inInlineStr = false
                        "c" -> finishCell()
                        "row" -> {
                            totalRows++
                            if (grid.size < MAX_ROWS) {
                                grid.add(rowCells)
                                if (grid.size % 200 == 0) onProgress(grid.size)
                            } else truncated = true
                            rowCells = ArrayList()
                        }
                    }
                }
            }
            p.next()
        }
        // 读取列宽
        // （重新打开流读取 cols；简单起见在网格解析后再读一次）
        val p2 = OfficeUtil.newParser(zip.stream(entryPath)!!)
        while (p2.eventType != XmlPullParser.END_DOCUMENT) {
            if (p2.eventType == XmlPullParser.START_TAG && OfficeUtil.local(p2.name) == "col") {
                val min = OfficeUtil.attr(p2, "min")?.toIntOrNull() ?: 0
                val width = OfficeUtil.attr(p2, "width")?.toFloatOrNull()
                if (width != null) {
                    for (c in (min - 1).coerceAtLeast(0)..((OfficeUtil.attr(p2, "max")?.toIntOrNull() ?: min) - 1)) {
                        colW[c] = width
                    }
                }
            }
            p2.next()
        }

        // 补齐行宽
        val widths = (0 until maxOf(maxCol, 1)).map { ((colW[it] ?: 8.43f) * 6.2f + 8f).toInt().coerceIn(46, 320) }
        val rows = grid.map { row ->
            val r = row.toMutableList()
            while (r.size < maxCol) r.add(null)
            r
        }
        val cappedCol = minOf(maxCol, MAX_COLS)
        val trimmedRows = rows.map { r ->
            if (r.size > cappedCol) r.take(cappedCol) else r
        }
        onProgress(trimmedRows.size)
        return OfficeBlock.SheetHeader(
            name = name,
            index = sheetIndex,
            rows = trimmedRows,
            colCount = cappedCol,
            colWidthDp = widths.take(cappedCol),
            truncated = truncated || maxCol > MAX_COLS,
            rowCount = totalRows,
        )
    }

    private fun formatSerialDate(serial: Double): String {
        // Excel 日期序列：1900-01-01 起（serial 1 = 1900-01-01），兼容 1900 闰年 bug
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(1899, Calendar.DECEMBER, 30) // 以 1899-12-30 为 0 点（跳过 1900-02-29 bug）
        cal.add(Calendar.DAY_OF_YEAR, serial.toInt())
        val frac = serial - serial.toInt()
        val secs = (frac * 86400).toInt()
        cal.add(Calendar.SECOND, secs)
        val d: Date = cal.time
        return if (frac > 0.00001) SimpleDateFormat("yyyy/M/d HH:mm:ss", Locale.getDefault()).format(d)
        else SimpleDateFormat("yyyy/M/d", Locale.getDefault()).format(d)
    }

    private fun loadSharedStrings(zip: OfficeSource): List<String> {
        if (!zip.has("xl/sharedStrings.xml")) return emptyList()
        val list = ArrayList<String>()
        val p = OfficeUtil.newParser(zip.stream("xl/styles.xml")!!)
        val sb = StringBuilder()
        var inSi = false
        var inR = false
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            val ev = p.eventType
            val n = OfficeUtil.local(p.name)
            when (ev) {
                XmlPullParser.START_TAG -> when (n) {
                    "si" -> { inSi = true; sb.setLength(0) }
                    "r" -> if (inSi) inR = true
                }
                XmlPullParser.TEXT -> if (inSi) sb.append(p.text)
                XmlPullParser.END_TAG -> when (n) {
                    "r" -> inR = false
                    "si" -> { inSi = false; list.add(sb.toString()); sb.setLength(0) }
                }
            }
            p.next()
        }
        return list
    }

    /** cellXfs 中带加粗字体的样式索引集合 */
    private fun loadBoldFlags(zip: OfficeSource): Set<Int> {
        if (!zip.has("xl/styles.xml")) return emptySet()
        val p = OfficeUtil.newParser(zip.stream("xl/styles.xml")!!)
        val fontBold = ArrayList<Boolean>() // fontId → bold
        val xfFont = ArrayList<Int>() // xfId → fontId
        var inFonts = false
        var inXfs = false
        var curFontBold = false
        var curXfFont = 0
        var inXf = false
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            val ev = p.eventType
            val n = OfficeUtil.local(p.name)
            when (ev) {
                XmlPullParser.START_TAG -> when (n) {
                    "fonts" -> inFonts = true
                    "b" -> if (inFonts && OfficeUtil.attr(p, "w:val") != "0") curFontBold = true
                    "font" -> curFontBold = false
                    "cellXfs" -> inXfs = true
                    "xf" -> if (inXfs) { inXf = true; curXfFont = OfficeUtil.attr(p, "fontId")?.toIntOrNull() ?: 0 }
                }
                XmlPullParser.END_TAG -> when (n) {
                    "font" -> fontBold.add(curFontBold)
                    "fonts" -> inFonts = false
                    "xf" -> if (inXf) { xfFont.add(curXfFont); inXf = false }
                    "cellXfs" -> inXfs = false
                }
            }
            p.next()
        }
        val bold = HashSet<Int>()
        xfFont.forEachIndexed { i, f -> if (fontBold.getOrNull(f) == true) bold.add(i) }
        return bold
    }

    /** 内建日期格式 numFmtId 集合（14-22, 45-47） */
    private fun loadDateFormats(zip: OfficeSource): Set<Int> {
        if (!zip.has("xl/styles.xml")) return emptySet()
        val p = OfficeUtil.newParser(zip.stream("xl/styles.xml")!!)
        val builtin = setOf(14, 15, 16, 17, 18, 19, 20, 21, 22, 45, 46, 47)
        val customDate = HashSet<String>()
        val xfNumFmt = ArrayList<Int>()
        val numFmtIds = HashSet<Int>()
        var inXfs = false
        var inXf = false
        var inNumFmts = false
        var curNumFmt = 0
        var curFmtCode: String? = null
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            val ev = p.eventType
            val n = OfficeUtil.local(p.name)
            when (ev) {
                XmlPullParser.START_TAG -> when (n) {
                    "numFmts" -> inNumFmts = true
                    "numFmt" -> if (inNumFmts) {
                        curNumFmt = OfficeUtil.attr(p, "numFmtId")?.toIntOrNull() ?: 0
                        curFmtCode = OfficeUtil.attr(p, "formatCode")
                        if (curFmtCode?.uppercase(Locale.ROOT)?.containsAny("Y", "M/D", "H:M") == true ||
                            curFmtCode?.uppercase(Locale.ROOT)?.matches(Regex(".*[YM/DH].*")) == true
                        ) {
                            customDate.add(curFmtCode ?: "")
                            numFmtIds.add(curNumFmt)
                        }
                    }
                    "cellXfs" -> inXfs = true
                    "xf" -> if (inXfs) { inXf = true; curNumFmt = OfficeUtil.attr(p, "numFmtId")?.toIntOrNull() ?: 0 }
                }
                XmlPullParser.END_TAG -> when (n) {
                    "numFmts" -> inNumFmts = false
                    "xf" -> if (inXf) { xfNumFmt.add(curNumFmt); inXf = false }
                    "cellXfs" -> inXfs = false
                }
            }
            p.next()
        }
        val result = HashSet<Int>()
        xfNumFmt.forEachIndexed { i, nf -> if (nf in builtin || nf in numFmtIds) result.add(i) }
        return result
    }

    private fun String.containsAny(vararg parts: String): Boolean = parts.any { contains(it, ignoreCase = true) }
}
