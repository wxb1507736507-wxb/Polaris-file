package com.limao.unzip.files

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Office（OOXML）文本提取：docx/xlsx/pptx → 纯文本预览（只读）。 */
object OfficeText {

    val SUPPORTED = listOf("docx", "xlsx", "pptx")

    fun supported(name: String): Boolean = SUPPORTED.any { name.lowercase().endsWith(it) }

    var extractedFull = true
        private set

    /** 返回 (文本, 是否完整)。 */
    fun extract(file: File, maxChars: Int = 400_000): Pair<String, Boolean> {
        val ext = file.extension.lowercase()
        extractedFull = true
        ZipFile(file).use { zf ->
            val text = when (ext) {
                "docx" -> extractDocx(zf, maxChars)
                "xlsx" -> extractXlsx(zf, maxChars)
                "pptx" -> extractPptx(zf, maxChars)
                else -> ""
            }
            return text to extractedFull
        }
    }

    // ==================== 通用 XML 解析 ====================

    private fun newParser(ins: InputStream): XmlPullParser {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(ins, null)
        return p
    }

    private fun tag(parser: XmlPullParser): String = parser.name.substringAfterLast(':').lowercase()

    // ==================== DOCX ====================

    private fun extractDocx(zf: ZipFile, maxChars: Int): String {
        val sb = StringBuilder()
        val entry = zf.getEntry("word/document.xml") ?: return "（空文档）"
        zf.getInputStream(entry).use { ins ->
            val p = newParser(ins)
            var inP = false; var inT = false; var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> { val n = tag(p); if (n == "p") inP = true; if (n == "t") inT = true }
                    XmlPullParser.TEXT -> if (inT) { sb.append(p.text); if (sb.length > maxChars) { extractedFull = false; return sb.toString() } }
                    XmlPullParser.END_TAG -> { val n = tag(p); if (n == "t") inT = false; if (n == "p") sb.append('\n') }
                }
                ev = p.next()
            }
        }
        return sb.toString().ifEmpty { "（空文档）" }
    }

    // ==================== XLSX ====================

    private fun extractXlsx(zf: ZipFile, maxChars: Int): String {
        extractedFull = true
        // 1. 解析 sharedStrings
        val shared = ArrayList<String>()
        zf.getEntry("xl/sharedStrings.xml")?.let { entry ->
            zf.getInputStream(entry).use { ins ->
                val p = newParser(ins)
                var inSi = false; var inT = false; val cur = StringBuilder(); var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    when (ev) {
                        XmlPullParser.START_TAG -> { val n = tag(p); if (n == "si") { inSi = true; cur.clear() }; if (n == "t" && inSi) inT = true }
                        XmlPullParser.TEXT -> if (inT) cur.append(p.text)
                        XmlPullParser.END_TAG -> { val n = tag(p); if (n == "t") inT = false; if (n == "si") { shared.add(cur.toString()); inSi = false } }
                    }
                    ev = p.next()
                }
            }
        }

        // 2. 解析各 worksheet，按行输出单元格值
        val sb = StringBuilder()
        val sheetNames = zf.entries().toList()
            .map { it.name }
            .filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .sortedBy { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }

        for ((sheetIdx, sheetName) in sheetNames.withIndex()) {
            if (sb.length > maxChars) { extractedFull = false; break }
            sb.append("\n=== Sheet").append(sheetIdx + 1).append(" ===\n")
            zf.getInputStream(zf.getEntry(sheetName)).use { ins ->
                val p = newParser(ins)
                var inRow = false; var inCell = false; var inV = false; var inIs = false; var inIsT = false
                var cellType = ""; var cellRaw = StringBuilder()
                val rowData = StringBuilder()
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    when (ev) {
                        XmlPullParser.START_TAG -> {
                            val n = tag(p)
                            when (n) {
                                "row" -> { inRow = true; rowData.clear() }
                                "c" -> { inCell = true; cellType = p.getAttributeValue(null, "t") ?: ""; cellRaw.clear() }
                                "v" -> if (inCell) inV = true
                                "is" -> if (inCell) inIs = true
                                "t" -> if (inIs) inIsT = true
                            }
                        }
                        XmlPullParser.TEXT -> {
                            if (inV) cellRaw.append(p.text)
                            if (inIsT) cellRaw.append(p.text)
                        }
                        XmlPullParser.END_TAG -> {
                            val n = tag(p)
                            when (n) {
                                "v" -> inV = false
                                "t" -> inIsT = false
                                "is" -> inIs = false
                                "c" -> {
                                    inCell = false
                                    val raw = cellRaw.toString()
                                    val resolved = if (cellType == "s") {
                                        shared.getOrNull(raw.toIntOrNull() ?: -1) ?: raw
                                    } else raw
                                    rowData.append(resolved).append('\t')
                                }
                                "row" -> {
                                    inRow = false
                                    sb.append(rowData.toString().trimEnd('\t')).append('\n')
                                    if (sb.length > maxChars) { extractedFull = false; return sb.toString() }
                                }
                            }
                        }
                    }
                    ev = p.next()
                }
            }
        }
        return sb.toString().ifEmpty { "（空表格）" }
    }

    // ==================== PPTX ====================

    private fun extractPptx(zf: ZipFile, maxChars: Int): String {
        extractedFull = true
        val sb = StringBuilder()
        val slideNames = zf.entries().toList()
            .map { it.name }
            .filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            .sortedBy { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        for (s in slideNames) {
            if (sb.length > maxChars) { extractedFull = false; break }
            sb.append("\n— 第 ").append(s.filter { c -> c.isDigit() }.toIntOrNull() ?: 0).append(" 页 —\n")
            zf.getInputStream(zf.getEntry(s)).use { ins ->
                val p = newParser(ins)
                var inT = false; var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    when (ev) {
                        XmlPullParser.START_TAG -> if (tag(p) == "t") inT = true
                        XmlPullParser.TEXT -> if (inT) { sb.append(p.text); if (sb.length > maxChars) { extractedFull = false; break } }
                        XmlPullParser.END_TAG -> if (tag(p) == "t") inT = false
                    }
                    ev = p.next()
                }
            }
            sb.append('\n')
        }
        return sb.toString().ifEmpty { "（空演示文稿）" }
    }
}
