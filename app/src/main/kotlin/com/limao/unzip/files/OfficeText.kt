package com.limao.unzip.files

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.zip.ZipFile

/** Office（OOXML）文本提取：docx/xlsx/pptx → 纯文本预览（只读）。 */
object OfficeText {

    val SUPPORTED = listOf("docx", "xlsx", "pptx")

    fun supported(name: String): Boolean = SUPPORTED.any { name.lowercase().endsWith(it) }

    /** 返回 (文本, 是否被截断)。 */
    fun extract(file: File, maxChars: Int = 400_000): Pair<String, Boolean> {
        val ext = file.extension.lowercase()
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

    var extractedFull = true
        private set

    private fun collect(zf: java.util.zip.ZipFile, entryName: String, parseMode: String, out: StringBuilder, maxChars: Int): Boolean {
        val entry = zf.getEntry(entryName) ?: return true
        zf.getInputStream(entry).use { ins ->
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(ins, null)
            var inTarget = false
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val n = parser.name.substringAfterLast(':').lowercase()
                        if ((parseMode == "docx" && n == "p") || (parseMode == "pptx" && n == "p")) {
                            if (out.isNotEmpty()) out.append('\n')
                        }
                        if ((parseMode == "docx" && n == "t") || (parseMode == "pptx" && n == "t") ||
                            (parseMode == "xlsx" && n == "t")
                        ) inTarget = true
                    }
                    XmlPullParser.TEXT -> if (inTarget) {
                        out.append(parser.text)
                        if (out.length > maxChars) { extractedFull = false; return false }
                    }
                    XmlPullParser.END_TAG -> {
                        val n = parser.name.substringAfterLast(':').lowercase()
                        if (inTarget && n == "t") inTarget = false
                        if ((parseMode == "docx" && n == "p") || (parseMode == "pptx" && n == "p")) out.append('\n')
                        if (parseMode == "xlsx" && n == "row") out.append('\n')
                    }
                }
                ev = parser.next()
            }
        }
        return true
    }

    private fun extractDocx(zf: java.util.zip.ZipFile, maxChars: Int): String {
        extractedFull = true
        val sb = StringBuilder()
        collect(zf, "word/document.xml", "docx", sb, maxChars)
        return sb.toString().ifEmpty { "（空文档）" }
    }

    private fun extractXlsx(zf: java.util.zip.ZipFile, maxChars: Int): String {
        extractedFull = true
        val sb = StringBuilder()
        // 单元格字符串集中在 sharedStrings
        collect(zf, "xl/sharedStrings.xml", "xlsx", sb, maxChars)
        // 各 sheet 的单元格数值/公式结果
        val sheetNames = zf.entries().toList()
            .map { it.name }
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sorted()
        for (s in sheetNames) {
            if (sb.length > maxChars) break
            sb.append("\n— ").append(s.substringAfterLast('/')).append(" —\n")
            collect(zf, s, "xlsx", sb, maxChars)
        }
        return sb.toString().ifEmpty { "（空表格）" }
    }

    private fun extractPptx(zf: java.util.zip.ZipFile, maxChars: Int): String {
        extractedFull = true
        val sb = StringBuilder()
        val slideNames = zf.entries().toList()
            .map { it.name }
            .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            .sortedBy { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        for (s in slideNames) {
            if (sb.length > maxChars) break
            sb.append("\n— 第 ").append(s.filter { c -> c.isDigit() }.toIntOrNull() ?: 0).append(" 页 —\n")
            collect(zf, s, "pptx", sb, maxChars)
        }
        return sb.toString().ifEmpty { "（空演示文稿）" }
    }
}
