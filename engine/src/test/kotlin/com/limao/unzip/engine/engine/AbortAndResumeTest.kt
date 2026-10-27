package com.limao.unzip.engine.engine

import com.limao.unzip.engine.TestZips
import com.limao.unzip.engine.api.ExtractOptions
import com.limao.unzip.engine.api.ExtractRequest
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.extractor.ArchiveExtractorImpl
import com.limao.unzip.engine.journal.RecoveryJournal
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** I01/I02/I04：条目间中止、单文件块间中止、journal 断点恢复。 */
class AbortAndResumeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val extractor = ArchiveExtractorImpl(ZipReferenceEngine())

    private fun content(n: Int, seed: Int = 0): ByteArray =
        ByteArray(n) { ((it * 31 + seed) % 251).toByte() }

    @Test
    fun `abort between entries keeps completed files intact`() {
        val zip = tmp.newFile("m.zip")
        val entries = (0 until 30).map { "f%02d.txt".format(it) to content(200, it) }
        TestZips.makeZip(zip, entries)

        val out = tmp.newFolder()
        val journalFile = tmp.newFile("m.journal")

        // 第 10 个条目完成后请求中止
        var handle: com.limao.unzip.engine.api.ExtractionJob? = null
        val req = ExtractRequest(
            source = FileChannelInput.open(zip),
            destination = FileSink(out),
            journal = RecoveryJournal.create(journalFile, 30),
            options = ExtractOptions(onEntryDone = { if (it.index == 9L) handle?.cancel() }),
        )
        handle = extractor.submit(req)
        val result = runBlocking { handle.await() }

        assertTrue(result is ExtractResult.Aborted, "unexpected: $result")
        result as ExtractResult.Aborted
        assertEquals(10L, result.entriesDone)
        for (i in 0 until 10) assertTrue(File(out, "f%02d.txt".format(i)).isFile)
        assertTrue(out.walkTopDown().none { it.name.endsWith(".part") }, "不应有 .part 残留")
    }

    @Test
    fun `abort inside a large file leaves no final file and honours part policy`() {
        val zip = tmp.newFile("big.zip")
        val data = content(8 * 1024 * 1024, 5)
        TestZips.writeRawZip(zip, "big.bin", data, method = 0)

        // deleteIncompleteOnAbort = true：无 big.bin、无 .part
        run {
            val out = tmp.newFolder()
            val engine = ZipReferenceEngine()
            var output = 0L
            val listener = object : com.limao.unzip.engine.api.EngineListener {
                override fun shouldAbort() = output > 4L * 1024 * 1024
                override fun onInputBytes(deltaBytes: Long) {}
                override fun onOutputBytes(deltaBytes: Long) { output += deltaBytes }
                override fun onEntryStart(index: Long, path: String) {}
                override fun onEntryDone(index: Long, path: String, bytesWritten: Long) {}
            }
            val plan = ExtractPlan(FileSink(out), LongArray(1) { -1L }, null, ExtractOptions())
            val result = engine.extract(FileChannelInput.open(zip), plan, listener)
            assertTrue(result is ExtractResult.Aborted, "unexpected: $result")
            result as ExtractResult.Aborted
            assertEquals("big.bin", result.abortPoint.partialPath)
            assertTrue(result.abortPoint.partialBytes > 4L * 1024 * 1024, "partial=${result.abortPoint.partialBytes}")
            assertTrue(result.abortPoint.partialBytes < 8L * 1024 * 1024)
            assertTrue(!File(out, "big.bin").exists())
            assertTrue(!File(out, "big.bin.part").exists())
        }

        // deleteIncompleteOnAbort = false：保留 .part，长度等于已写字节
        run {
            val out = tmp.newFolder()
            val engine = ZipReferenceEngine()
            var output = 0L
            val listener = object : com.limao.unzip.engine.api.EngineListener {
                override fun shouldAbort() = output > 4L * 1024 * 1024
                override fun onInputBytes(deltaBytes: Long) {}
                override fun onOutputBytes(deltaBytes: Long) { output += deltaBytes }
                override fun onEntryStart(index: Long, path: String) {}
                override fun onEntryDone(index: Long, path: String, bytesWritten: Long) {}
            }
            val plan = ExtractPlan(FileSink(out), LongArray(1) { -1L }, null, ExtractOptions(deleteIncompleteOnAbort = false))
            val result = engine.extract(FileChannelInput.open(zip), plan, listener)
            result as ExtractResult.Aborted
            val part = File(out, "big.bin.part")
            assertTrue(part.isFile)
            assertEquals(result.abortPoint.partialBytes, part.length())
        }
    }

    @Test
    fun `resume skips journaled entries and finishes the rest`() {
        val zip = tmp.newFile("r.zip")
        val entries = (0 until 30).map { "f%02d.txt".format(it) to content(200, it) }
        TestZips.makeZip(zip, entries)

        val out = tmp.newFolder()
        val journalFile = tmp.newFile("r.journal")

        // 第一段：10 个条目后中止
        var handle: com.limao.unzip.engine.api.ExtractionJob? = null
        val first = extractor.submit(
            ExtractRequest(
                source = FileChannelInput.open(zip),
                destination = FileSink(out),
                journal = RecoveryJournal.create(journalFile, 30),
                options = ExtractOptions(onEntryDone = { if (it.index == 9L) handle?.cancel() }),
            )
        )
        handle = first
        assertTrue(runBlocking { first.await() } is ExtractResult.Aborted)

        val mtimeBefore = (0 until 10).associate { it to File(out, "f%02d.txt".format(it)).lastModified() }

        // 第二段：journal 重新打开，恢复
        val resumed = RecoveryJournal.open(journalFile)!!
        assertEquals(10L, resumed.completedCount())
        val second = extractor.submit(
            ExtractRequest(
                source = FileChannelInput.open(zip),
                destination = FileSink(out),
                journal = resumed,
            )
        )
        val result = runBlocking { second.await() }
        assertTrue(result is ExtractResult.Completed, "unexpected: $result")
        result as ExtractResult.Completed
        assertEquals(30L, result.entriesDone)

        for (i in 0 until 30) {
            val f = File(out, "f%02d.txt".format(i))
            assertTrue(f.isFile, "f$i")
            assertTrue(f.readBytes().contentEquals(content(200, i)), "f$i content")
        }
        for (i in 0 until 10) {
            assertEquals(mtimeBefore.getValue(i), File(out, "f%02d.txt".format(i)).lastModified(), "已恢复条目不应被重写: f$i")
        }
        assertTrue(RecoveryJournal.open(journalFile)!!.markedCompleted())
    }
}
