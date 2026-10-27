package com.limao.unzip.engine.engine

import com.limao.unzip.engine.TestZips
import com.limao.unzip.engine.api.ExtractRequest
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.extractor.ArchiveExtractorImpl
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B01-lite：3 万条目解压全程的 JVM 堆增量 < 32MB。
 * 结构性保证（中央目录流式 + Cursor 复用 + 位图 + 有界 warnings）在此验证。
 */
class HeapBudgetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `heap delta stays under 32MB for a 30k-entry archive`() {
        val zip = tmp.newFile("huge.zip")
        TestZips.makeZip(zip, (0 until 30_000).map { "f%05d.txt".format(it) to ByteArray(16) { it.toByte() } })

        val out = tmp.newFolder()
        val extractor = ArchiveExtractorImpl(ZipReferenceEngine())

        System.gc(); System.gc()
        Thread.sleep(120)
        val before = usedHeap()

        val result = kotlinx.coroutines.runBlocking {
            extractor.extract(
                ExtractRequest(
                    source = FileChannelInput.open(zip),
                    destination = FileSink(out),
                    options = com.limao.unzip.engine.api.ExtractOptions(onEntryDone = { /* 借予语义：不持有 */ }),
                )
            )
        }

        System.gc(); System.gc()
        Thread.sleep(120)
        val delta = usedHeap() - before

        assertTrue(result is ExtractResult.Completed, "unexpected: $result")
        result as ExtractResult.Completed
        assertEquals(30_000L, result.entriesDone)
        assertEquals(30_000L, out.listFiles()!!.size.toLong())
        assertTrue(delta < 32L * 1024 * 1024, "heap delta = ${delta / 1024} KB, 超出 32MB 预算")
    }

    private fun usedHeap(): Long =
        Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
}
