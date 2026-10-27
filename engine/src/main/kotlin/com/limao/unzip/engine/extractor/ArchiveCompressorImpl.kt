package com.limao.unzip.engine.extractor

import com.limao.unzip.engine.api.ArchiveCompressor
import com.limao.unzip.engine.api.CompressRequest
import com.limao.unzip.engine.api.EngineListener
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.api.ExtractionJob
import com.limao.unzip.engine.api.ProgressSnapshot
import com.limao.unzip.engine.api.ProgressState
import com.limao.unzip.engine.api.EntryDone
import com.limao.unzip.engine.dispatch.EngineDispatchers
import com.limao.unzip.engine.engine.ZipReferenceCompressor
import com.limao.unzip.engine.error.EngineException
import com.limao.unzip.engine.error.ErrorCatalog
import com.limao.unzip.engine.error.UserError
import com.limao.unzip.engine.progress.ProgressThrottler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 压缩门面（镜像 ArchiveExtractorImpl）：scan 预计总量（input=源字节）→ 压缩 → 节流进度。
 * 进度基准 = INPUT_BYTES（源总量已知，归档大小未知）。
 */
class ArchiveCompressorImpl(
    private val engine: com.limao.unzip.engine.api.CompressionEngine = ZipReferenceCompressor(),
) : ArchiveCompressor {

    override fun submit(request: CompressRequest): ExtractionJob {
        val state = MutableStateFlow<ProgressSnapshot?>(null)
        val throttler = ProgressThrottler(request.options.progressMinIntervalMs) { state.value = it }
        val cancelFlag = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + EngineDispatchers.WORKERS)
        val deferred = scope.async { runJob(request, throttler, cancelFlag) }
        return CompressJobImpl(state.filterNotNull(), deferred, cancelFlag, scope)
    }

    override suspend fun compress(request: CompressRequest): ExtractResult = submit(request).await()

    private suspend fun runJob(
        request: CompressRequest,
        throttler: ProgressThrottler,
        cancelFlag: AtomicBoolean,
    ): ExtractResult {
        val opts = request.options
        return try {
            var entriesTotal = 0L
            var inputTotal = 0L
            request.source.scan { c ->
                if (c.index >= opts.maxEntryCount) {
                    throw EngineException(com.limao.unzip.engine.error.EngineErrorCode.LIMIT, "压缩条目数超上限 ${opts.maxEntryCount}")
                }
                entriesTotal++
                if (c.uncompressedSize > 0) inputTotal += c.uncompressedSize
            }
            throttler.setTotals(inputBytesTotal = inputTotal, outputBytesTotal = -1, totalEntries = entriesTotal)

            val listener = object : EngineListener {
                override fun shouldAbort() = cancelFlag.get()
                override fun onInputBytes(deltaBytes: Long) = throttler.offerInput(deltaBytes)
                override fun onOutputBytes(deltaBytes: Long) = throttler.offerOutput(deltaBytes)
                override fun onEntryStart(index: Long, path: String) = throttler.setCurrentPath(path)
                override fun onEntryDone(index: Long, path: String, bytesWritten: Long) {
                    throttler.onEntryDone()
                    opts.onEntryDone?.invoke(EntryDone(index, path, bytesWritten))
                }
            }
            val result = engine.compress(request.source, request.output, listener)
            throttler.finish(terminalOf(result))
            result
        } catch (e: EngineException) {
            runCatching { request.output.discard() }
            throttler.finish(ProgressState.FAILED)
            ExtractResult.Failed(mapError(e), throttler.currentEntriesDone())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            runCatching { request.output.discard() }
            throttler.finish(ProgressState.FAILED)
            ExtractResult.Failed(
                ErrorCatalog.providerIo(t.message ?: t.javaClass.simpleName),
                throttler.currentEntriesDone(),
            )
        } finally {
            runCatching { request.source.close() }
            runCatching { request.output.close() }
        }
    }

    private fun terminalOf(result: ExtractResult): ProgressState = when (result) {
        is ExtractResult.Completed -> ProgressState.COMPLETED
        is ExtractResult.Aborted -> ProgressState.ABORTED
        is ExtractResult.Failed -> ProgressState.FAILED
    }

    private fun mapError(e: EngineException): UserError = when (e.code) {
        com.limao.unzip.engine.error.EngineErrorCode.LIMIT ->
            com.limao.unzip.engine.error.ErrorCatalog.entryTooLarge(Long.MAX_VALUE)
        else -> ErrorCatalog.providerIo(e.message ?: "")
    }

    private class CompressJobImpl(
        override val progress: Flow<ProgressSnapshot>,
        private val deferred: Deferred<ExtractResult>,
        private val cancelFlag: AtomicBoolean,
        private val scope: CoroutineScope,
    ) : ExtractionJob {
        override fun cancel() {
            cancelFlag.set(true)
        }

        override suspend fun await(): ExtractResult {
            val result = deferred.await()
            scope.cancel()
            return result
        }
    }
}
