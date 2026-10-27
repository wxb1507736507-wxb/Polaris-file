package com.limao.unzip.engine.engine

import com.limao.unzip.engine.api.CompressRequest
import com.limao.unzip.engine.api.CompressOptions
import com.limao.unzip.engine.api.EngineListener
import com.limao.unzip.engine.api.ExtractRequest
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.extractor.ArchiveCompressorImpl
import com.limao.unzip.engine.extractor.ArchiveExtractorImpl
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 压缩：回环（压缩→解压→内容一致）、双计数器、中止、.part 语义。 */
class ZipReferenceCompressorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun content(n: Int, seed: Int = 0): ByteArray =
        ByteArray(n) { ((it * 31 + seed) % 251).toByte() }

    private fun makeSourceDir(): File {
        val root = tmp.newFolder("src")
        File(root, "sub").mkdirs()
        File(root, "a.txt").writeBytes(content(50_000, 1))
        File(root, "sub/深文件 β.txt").writeBytes(content(3, 2))
        File(root, "sub/blob.bin").writeBytes(content(100_000, 3))
        File(root, "empty.txt").writeBytes(ByteArray(0))
        return root
    }

    @Test
    fun `roundtrip create then extract contents match`() {
        val src = makeSourceDir()
        val zip = File(tmp.newFolder(), "out.zip")

        val compressor = ArchiveCompressorImpl(ZipReferenceCompressor())
        val result = kotlinx.coroutines.runBlocking {
            compressor.compress(CompressRequest(DirCompressSource(src), FileArchiveOutput(zip)))
        }

        assertTrue(result is ExtractResult.Completed, "unexpected: $result")
        result as ExtractResult.Completed
        // 4 文件 + sub 目录 = 5 条目（根目录本身不作为条目）
        assertEquals(5L, result.entriesDone)
        assertEquals(150_003L, result.bytesDone) // 源字节精确

        // 用解压引擎回读
        val out = tmp.newFolder("extracted")
        val extractor = ArchiveExtractorImpl(ZipReferenceEngine())
        val ex = kotlinx.coroutines.runBlocking {
            extractor.extract(ExtractRequest(FileChannelInput.open(zip), FileSink(out)))
        }
        assertTrue(ex is ExtractResult.Completed, "unexpected: $ex")

        assertTrue(File(out, "a.txt").readBytes().contentEquals(content(50_000, 1)))
        assertTrue(File(out, "sub/深文件 β.txt").readBytes().contentEquals(content(3, 2)))
        assertTrue(File(out, "sub/blob.bin").readBytes().contentEquals(content(100_000, 3)))
        assertTrue(File(out, "empty.txt").length() == 0L)
        // 压缩应有效（可压缩内容）
        assertTrue(zip.length() < 150_003L, "zip=${zip.length()}")
    }

    @Test
    fun `input counter equals source bytes and progress monotonic`() {
        val src = makeSourceDir()
        val zip = File(tmp.newFolder(), "out.zip")

        var input = 0L
        val engine = object : com.limao.unzip.engine.api.CompressionEngine by ZipReferenceCompressor() {
            override fun compress(
                source: com.limao.unzip.engine.api.CompressSource,
                output: com.limao.unzip.engine.api.ArchiveOutput,
                listener: EngineListener,
            ): ExtractResult {
                val wrapped = object : EngineListener {
                    override fun shouldAbort() = listener.shouldAbort()
                    override fun onInputBytes(deltaBytes: Long) { input += deltaBytes; listener.onInputBytes(deltaBytes) }
                    override fun onOutputBytes(deltaBytes: Long) = listener.onOutputBytes(deltaBytes)
                    override fun onEntryStart(index: Long, path: String) = listener.onEntryStart(index, path)
                    override fun onEntryDone(index: Long, path: String, bytesWritten: Long) = listener.onEntryDone(index, path, bytesWritten)
                }
                return ZipReferenceCompressor().compress(source, output, wrapped)
            }
        }
        val result = kotlinx.coroutines.runBlocking {
            ArchiveCompressorImpl(engine).compress(CompressRequest(DirCompressSource(src), FileArchiveOutput(zip)))
        }
        assertTrue(result is ExtractResult.Completed)
        assertEquals(150_003L, input)
    }

    @Test
    fun `abort discards part archive`() {
        val src = makeSourceDir()
        val dir = tmp.newFolder()
        val zip = File(dir, "out.zip")

        val jobHolder = arrayOfNulls<com.limao.unzip.engine.api.ExtractionJob>(1)
        val compressor = ArchiveCompressorImpl(ZipReferenceCompressor())
        val job = compressor.submit(
            CompressRequest(
                source = DirCompressSource(src),
                output = FileArchiveOutput(zip),
                options = CompressOptions(onEntryDone = { if (it.index == 1L) jobHolder[0]?.cancel() }),
            ),
        )
        jobHolder[0] = job
        val result = kotlinx.coroutines.runBlocking { job.await() }

        assertTrue(result is ExtractResult.Aborted, "unexpected: $result")
        assertTrue(!zip.exists(), "中止后不应有归档产物")
        assertTrue(dir.listFiles().isNullOrEmpty(), "不应有 .part 残留: ${dir.listFiles()?.toList()}")
    }
}
