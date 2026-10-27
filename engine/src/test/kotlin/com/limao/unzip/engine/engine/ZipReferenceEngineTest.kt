package com.limao.unzip.engine.engine

import com.limao.unzip.engine.TestZips
import com.limao.unzip.engine.api.EntryCursor
import com.limao.unzip.engine.api.EntryErrorPolicy
import com.limao.unzip.engine.api.ExtractOptions
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.api.WarningCode
import com.limao.unzip.engine.error.EngineErrorCode
import com.limao.unzip.engine.error.EngineException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** N01/N04/N06/E01/E03/E04：参考 ZIP 引擎的正确性与损坏矩阵。 */
class ZipReferenceEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = ZipReferenceEngine()

    private fun content(n: Int, seed: Int = 0): ByteArray =
        ByteArray(n) { ((it * 31 + seed) % 251).toByte() }

    @Test
    fun `deflate roundtrip with dirs deep paths unicode and empty file`() {
        val zip = tmp.newFile("a.zip")
        val entries = listOf(
            "a.txt" to content(10_000, 1),
            "deep/nest/ed/b.bin" to content(50_000, 2),
            "深目录/文件 β.txt" to content(3, 3),
            "dir/" to ByteArray(0),
            "empty.txt" to ByteArray(0),
        )
        TestZips.makeZip(zip, entries, method = ZipOutputStream.DEFLATED)

        val out = extractTo(zip)
        for ((name, data) in entries) {
            val f = File(out, name)
            if (name.endsWith("/")) {
                assertTrue(f.isDirectory, name)
            } else {
                assertTrue(f.isFile, name)
                assertTrue(f.readBytes().contentEquals(data), name)
            }
        }
        // 时间戳在容差内（dos 精度 + 时区口径差异）
        assertTrue(
            Math.abs(File(out, "a.txt").lastModified() - TestZips.FIXED_TIME) < 26L * 3600 * 1000,
            "mtime=${File(out, "a.txt").lastModified()}",
        )
        // 无 .part 残留
        assertTrue(out.walkTopDown().none { it.name.endsWith(".part") })
    }

    @Test
    fun `stored roundtrip`() {
        val zip = tmp.newFile("s.zip")
        val entries = listOf(
            "one.dat" to content(100_000, 7),
            "two.dat" to content(1, 8),
        )
        TestZips.makeZip(zip, entries, method = ZipOutputStream.STORED)
        val out = extractTo(zip)
        for ((name, data) in entries) {
            assertTrue(File(out, name).readBytes().contentEquals(data), name)
        }
    }

    @Test
    fun `gbk names decode via heuristic when utf8 flag absent`() {
        val zip = tmp.newFile("g.zip")
        TestZips.makeZip(
            zip,
            listOf("项目资料/设计图.pdf" to content(64, 9)),
            method = ZipOutputStream.DEFLATED,
            charset = Charset.forName("GBK"),
        )
        val out = extractTo(zip)
        assertTrue(File(out, "项目资料/设计图.pdf").isFile)
    }

    @Test
    fun `input and output counters never mixed`() {
        // 可压缩内容：deflate 后 input << output；STORED：input == output
        val zip = tmp.newFile("c.zip")
        val entries = listOf(
            "r1.bin" to ByteArray(200_000) { 'A'.code.toByte() },
            "r2.bin" to ByteArray(100_000) { 'B'.code.toByte() },
        )
        TestZips.makeZip(zip, entries, method = ZipOutputStream.DEFLATED)

        var compTotal = 0L
        engine.scan(FileChannelInput.open(zip)) { c: EntryCursor -> compTotal += c.compressedSize }

        var input = 0L
        var output = 0L
        val result = extract(zip) { listener ->
            object : RecordingListener(listener) {
                override fun onInputBytes(deltaBytes: Long) { input += deltaBytes }
                override fun onOutputBytes(deltaBytes: Long) { output += deltaBytes }
            }
        } as ExtractResult.Completed

        assertEquals(300_000L, output)
        assertEquals(300_000L, result.bytesDone)
        assertEquals(compTotal, input, "deflate input 应精确等于压缩字节总量")
        assertEquals(compTotal, result.bytesInput)
        assertTrue(input < output / 10, "可压缩内容 input 应远小于 output")
    }

    @Test
    fun `crc mismatch skips entry under SKIP_CONTINUE and aborts under ABORT`() {
        val zip = tmp.newFile("bad.zip")
        val good = "good.txt" to content(1_000, 1)
        val badContent = "MARKER-BAD-BYTES-HERE".repeat(50).toByteArray()
        // STORED：内容原样落盘，才能按内容标记定位并翻转字节
        TestZips.makeZip(zip, listOf(good, "bad.txt" to badContent), method = ZipOutputStream.STORED)

        flipMarkerByte(zip, badContent)

        val out = tmp.newFolder()
        val result = extract(
            zip,
            out,
            ExtractOptions(entryErrorPolicy = EntryErrorPolicy.SKIP_CONTINUE),
        )
        assertTrue(result is ExtractResult.Completed)
        result as ExtractResult.Completed
        assertEquals(1, result.warnings.size)
        assertEquals(WarningCode.CRC_MISMATCH_SKIPPED, result.warnings[0].code)
        assertTrue(File(out, "good.txt").readBytes().contentEquals(good.second))
        assertTrue(!File(out, "bad.txt").exists())

        val out2 = tmp.newFolder()
        val ex = assertFailsWith<EngineException> {
            extract(zip, out2, ExtractOptions(entryErrorPolicy = EntryErrorPolicy.ABORT))
        }
        assertEquals(EngineErrorCode.CRC, ex.code)
    }

    @Test
    fun `truncated archive fails with FORMAT`() {
        val zip = tmp.newFile("t.zip")
        TestZips.makeZip(zip, listOf("a.txt" to content(1_000, 1)))
        val bytes = zip.readBytes()
        zip.writeBytes(bytes.copyOf(bytes.size - 30)) // 吃掉 EOCD

        assertFailsWith<EngineException> { engine.probe(FileChannelInput.open(zip)) }
            .let { assertEquals(EngineErrorCode.FORMAT, it.code) }
    }

    @Test
    fun `unsupported method warns or throws by policy`() {
        val zip = tmp.newFile("m.zip")
        TestZips.writeRawZip(zip, "lzma.bin", content(500), method = 14)

        val out = tmp.newFolder()
        val result = extract(zip, out, ExtractOptions(entryErrorPolicy = EntryErrorPolicy.SKIP_CONTINUE))
        result as ExtractResult.Completed
        assertEquals(1, result.warnings.size)
        assertEquals(WarningCode.UNSUPPORTED_METHOD_SKIPPED, result.warnings[0].code)
        assertTrue(!File(out, "lzma.bin").exists())

        assertFailsWith<EngineException> {
            extract(zip, tmp.newFolder(), ExtractOptions(entryErrorPolicy = EntryErrorPolicy.ABORT))
        }.let { assertEquals(EngineErrorCode.METHOD, it.code) }
    }

    @Test
    fun `encrypted flag is detected by scan`() {
        val zip = tmp.newFile("enc.zip")
        TestZips.writeRawZip(zip, "secret.txt", content(100), method = 0, flags = 1)
        val summary = engine.scan(FileChannelInput.open(zip)) { }
        assertTrue(summary.anyEncrypted)
    }

    @Test
    fun `empty archive completes with zero entries`() {
        val zip = tmp.newFile("e.zip")
        TestZips.makeZip(zip, emptyList())
        val result = extract(zip, tmp.newFolder())
        result as ExtractResult.Completed
        assertEquals(0L, result.entriesDone)
        assertEquals(0L, result.entriesTotal)
    }

    // ---- helpers ----

    private fun extractTo(zip: File): File {
        val out = tmp.newFolder()
        val result = extract(zip, out)
        assertTrue(result is ExtractResult.Completed, "unexpected: $result")
        return out
    }

    private fun extract(
        zip: File,
        outDir: File = tmp.newFolder(),
        options: ExtractOptions = ExtractOptions(),
        wrap: (com.limao.unzip.engine.api.EngineListener) -> com.limao.unzip.engine.api.EngineListener = { it },
    ): com.limao.unzip.engine.api.ExtractResult {
        val count = engine.probe(FileChannelInput.open(zip)).entryCount
        val selected = LongArray(BitOps.wordsFor(count)) { -1L } // 全选
        val plan = ExtractPlan(
            destination = FileSink(outDir, options.overwritePolicy, options.preserveTimestamps),
            selectedBits = selected,
            skipBits = null,
            options = options,
        )
        val base = object : com.limao.unzip.engine.api.EngineListener {
            override fun shouldAbort() = false
            override fun onInputBytes(deltaBytes: Long) {}
            override fun onOutputBytes(deltaBytes: Long) {}
            override fun onEntryStart(index: Long, path: String) {}
            override fun onEntryDone(index: Long, path: String, bytesWritten: Long) {}
        }
        return engine.extract(FileChannelInput.open(zip), plan, wrap(base))
    }

    private fun flipMarkerByte(zip: File, markerContent: ByteArray) {
        val marker = "MARKER-BAD".toByteArray()
        val all = zip.readBytes()
        var at = -1
        outer@ for (i in 0..all.size - marker.size) {
            for (j in marker.indices) {
                if (all[i + j] != marker[j]) continue@outer
            }
            at = i + marker.size // 标记之后的第一个字节
            break
        }
        check(at in 0 until all.size) { "marker not found" }
        all[at] = (all[at].toInt() xor 0x55).toByte()
        zip.writeBytes(all)
    }

    private open class RecordingListener(
        private val base: com.limao.unzip.engine.api.EngineListener,
    ) : com.limao.unzip.engine.api.EngineListener {
        override fun shouldAbort() = base.shouldAbort()
        override fun onInputBytes(deltaBytes: Long) = base.onInputBytes(deltaBytes)
        override fun onOutputBytes(deltaBytes: Long) = base.onOutputBytes(deltaBytes)
        override fun onEntryStart(index: Long, path: String) = base.onEntryStart(index, path)
        override fun onEntryDone(index: Long, path: String, bytesWritten: Long) = base.onEntryDone(index, path, bytesWritten)
    }
}
