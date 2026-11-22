package com.limao.unzip.files

import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * 阅读器分章/分页引擎（纯计算，后台线程调用）。
 *
 * 架构（对照番茄免费小说）：
 *  - 全文按章节标记切分（第X章/节/卷…；无标记按 12000 字符智能分段）
 *  - 阅读单元 = 章：章内容（通常 50~300KB）在后台线程整章分页（StaticLayout 二分），
 *    页首偏移数组缓存；RecyclerView 按页虚拟化渲染——字体/行距变更只需重排当前章（<1s），
 *    滚动/翻页零重排，彻底消除巨型 TextView 的整段重排卡顿
 */
class ReaderPaginator(
    private val text: String,
    private val paint: TextPaint,
    private val pageWidth: Int,
    private val pageHeight: Int,
    private val lineSpacingAdd: Float,
    private val lineSpacingMult: Float,
) {
    data class Chapter(val title: String, val start: Int)

    companion object {
        private val CHAPTER_RE =
            Regex("^[\t 　]*第[0-9〇零一二三四五六七八九十百千万两]+[章节卷回部集篇][^\\n]{0,50}", RegexOption.MULTILINE)
        const val SEGMENT_CHARS = 12000

        fun splitChapters(text: String): List<Chapter> {
            val out = ArrayList<Chapter>()
            CHAPTER_RE.findAll(text).forEach { m -> out.add(Chapter(m.value.trim().take(30), m.range.first)) }
            if (out.size < 3) {
                // 无章节标记：按固定字符数分段
                out.clear()
                var off = 0
                var idx = 1
                while (off < text.length) {
                    out.add(Chapter("第 $idx 节", off))
                    idx++
                    off += SEGMENT_CHARS
                }
            }
            return out
        }
    }

    fun chapterEnd(chapters: List<Chapter>, idx: Int): Int =
        if (idx + 1 < chapters.size) chapters[idx + 1].start else text.length

    /** 二分：从 start 起一页最多容纳的字符数（StaticLayout 高度 ≤ 页高） */
    fun pageEnd(start: Int, end: Int): Int {
        if (start >= end) return start
        var lo = start + 1
        var hi = end
        var best = start + 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val seq = text.subSequence(start, mid)
            val layout = StaticLayout.Builder.obtain(seq, 0, seq.length, paint, pageWidth)
                .setLineSpacing(lineSpacingAdd, lineSpacingMult)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
            if (layout.height <= pageHeight) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }

    /** 整章分页：返回页首偏移数组（最后一页结束于章尾） */
    fun paginateChapter(start: Int, end: Int): IntArray {
        val starts = ArrayList<Int>()
        var s = start
        var guard = 0
        while (s < end && guard++ < 50000) {
            starts.add(s)
            val e = pageEnd(s, end)
            if (e <= s) break
            s = e
        }
        if (starts.isEmpty()) starts.add(start)
        return starts.toIntArray()
    }
}
