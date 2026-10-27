package com.limao.unzip.engine.extractor

import com.limao.unzip.engine.TestZips
import com.limao.unzip.engine.api.ExtractOptions
import com.limao.unzip.engine.api.ExtractRequest
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.api.ProgressState
import com.limao.unzip.engine.api.basis
import com.limao.unzip.engine.api.ProgressBasis
import com.limao.unzip.engine.engine.FileChannelInput
import com.limao.unzip.engine.engine.FileSink
import com.limao.unzip.engine.engine.ZipReferenceEngine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** N06/线程模型：进度流 conflate、单调、终态必达；basis 选择规则。 */
class ExtractorFlowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `progress flow is monotonic and terminates with COMPLETED`() = runBlocking {
        val zip = tmp.newFile("p.zip")
        TestZips.makeZip(zip, (0 until 5).map { "f${it}.txt" to ByteArray(50_000) { 'x'.code.toByte() } })

        val extractor = ArchiveExtractorImpl(ZipReferenceEngine())
        val job = extractor.submit(
            ExtractRequest(
                source = FileChannelInput.open(zip),
                destination = FileSink(tmp.newFolder()),
                options = ExtractOptions(progressMinIntervalMs = 20),
            )
        )

        val snapshots = mutableListOf<com.limao.unzip.engine.api.ProgressSnapshot>()
        val completedSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
        val collector = launch {
            job.progress.collect { s ->
                snapshots.add(s)
                if (s.state == ProgressState.COMPLETED) completedSignal.complete(Unit)
            }
        }
        val result = job.await()
        withTimeout(10_000) { completedSignal.await() }
        collector.cancel()

        assertTrue(result is ExtractResult.Completed, "unexpected: $result")
        assertTrue(snapshots.isNotEmpty(), "进度流必须有事件")
        assertEquals(ProgressState.COMPLETED, snapshots.last().state, "终态必达")
        assertEquals(5L, snapshots.first().entriesTotal, "首个快照应携带预扫描总量")

        var lastOut = -1L
        var lastIn = -1L
        for (s in snapshots) {
            assertTrue(s.outputBytesDone >= lastOut, "output 单调不减: ${s.outputBytesDone} < $lastOut")
            assertTrue(s.inputBytesDone >= lastIn, "input 单调不减")
            lastOut = s.outputBytesDone
            lastIn = s.inputBytesDone
        }
        assertEquals(5L * 50_000, snapshots.last().outputBytesDone)
    }

    @Test
    fun `basis selection prefers output bytes then entries then indeterminate`() {
        fun snap(outTotal: Long, entriesTotal: Long) =
            com.limao.unzip.engine.api.ProgressSnapshot(
                ProgressState.RUNNING, 0, 0, 0, outTotal, 0, entriesTotal, null,
            )
        assertEquals(ProgressBasis.OUTPUT_BYTES, snap(100, 10).basis())
        assertEquals(ProgressBasis.ENTRY_COUNT, snap(0, 10).basis())
        assertEquals(ProgressBasis.INDETERMINATE, snap(0, 0).basis())
    }
}
