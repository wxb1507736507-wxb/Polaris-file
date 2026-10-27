package com.limao.unzip.engine.extractor

import com.limao.unzip.engine.api.ArchiveExtractor
import com.limao.unzip.engine.api.ArchiveFormat
import com.limao.unzip.engine.api.EntryDone
import com.limao.unzip.engine.api.ExtractRequest
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.api.ExtractionJob
import com.limao.unzip.engine.api.EngineListener
import com.limao.unzip.engine.api.ProgressSnapshot
import com.limao.unzip.engine.api.ProgressState
import com.limao.unzip.engine.api.SeekableInput
import com.limao.unzip.engine.dispatch.EngineDispatchers
import com.limao.unzip.engine.engine.BitOps
import com.limao.unzip.engine.engine.ExtractPlan
import com.limao.unzip.engine.engine.ExtractionEngine
import com.limao.unzip.engine.engine.ZipReferenceEngine
import com.limao.unzip.engine.error.EngineErrorCode
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
 * 门面实现：probe → scan（过滤 + 总量）→ journal(skipBits) → extract → journal 收尾。
 * 引擎执行在 [EngineDispatchers.WORKERS]（全局并发预算 F8），进度经节流器写入
 * StateFlow（conflate，线程安全），终态由 await 返回。
 */
class ArchiveExtractorImpl(
    private val engine: ExtractionEngine = ZipReferenceEngine(),
) : ArchiveExtractor {

    override suspend fun probe(source: SeekableInput) = engine.probe(source)

    override fun submit(request: ExtractRequest): ExtractionJob {
        val state = MutableStateFlow<ProgressSnapshot?>(null)
        val throttler = ProgressThrottler(request.options.progressMinIntervalMs) { state.value = it }
        val cancelFlag = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + EngineDispatchers.WORKERS)
        val deferred = scope.async { runJob(request, throttler, cancelFlag) }
        return ExtractionJobImpl(state.filterNotNull(), deferred, cancelFlag, scope)
    }

    override suspend fun extract(request: ExtractRequest): ExtractResult = submit(request).await()

    private suspend fun runJob(
        request: ExtractRequest,
        throttler: ProgressThrottler,
        cancelFlag: AtomicBoolean,
    ): ExtractResult {
        val opts = request.options
        try {
            val info = engine.probe(request.source)
            if (info.entryCount > opts.maxEntryCount) {
                throw EngineException(EngineErrorCode.LIMIT, "条目数 ${info.entryCount} 超上限 ${opts.maxEntryCount}")
            }
            // 扫描阶段会确认 anyEncrypted；加密包在参考引擎上 fast-fail（原生引擎就位后由其接管）
            val selected = LongArray(BitOps.wordsFor(info.entryCount))
            val summary = engine.scan(request.source) { cursor ->
                if (request.filter.test(cursor)) BitOps.set(selected, cursor.index)
            }
            if (summary.anyEncrypted && !engine.id.isNative) {
                throw EngineException(EngineErrorCode.ENCRYPTED, "参考引擎不支持加密压缩包")
            }
            if (summary.totalUncompressedBytes > opts.maxTotalUncompressedBytes) {
                throw EngineException(
                    EngineErrorCode.TOO_LARGE,
                    "解压后总量 ${summary.totalUncompressedBytes} 超上限 ${opts.maxTotalUncompressedBytes}",
                )
            }
            throttler.setTotals(
                inputBytesTotal = request.source.sizeBytes,
                outputBytesTotal = if (opts.progressMode == com.limao.unzip.engine.api.ProgressMode.INDETERMINATE) -1L else summary.totalUncompressedBytes,
                totalEntries = summary.entryCount,
            )
            val journal = request.journal
            val plan = ExtractPlan(
                destination = request.destination,
                selectedBits = selected,
                skipBits = journal?.skipBits(),
                options = opts,
            )
            val listener = object : EngineListener {
                override fun shouldAbort() = cancelFlag.get()
                override fun onInputBytes(deltaBytes: Long) = throttler.offerInput(deltaBytes)
                override fun onOutputBytes(deltaBytes: Long) = throttler.offerOutput(deltaBytes)
                override fun onEntryStart(index: Long, path: String) = throttler.setCurrentPath(path)
                override fun onEntryDone(index: Long, path: String, bytesWritten: Long) {
                    throttler.onEntryDone()
                    journal?.record(index)
                    opts.onEntryDone?.invoke(EntryDone(index, path, bytesWritten))
                }
            }

            val result = engine.extract(request.source, plan, listener)
            when (result) {
                is ExtractResult.Completed -> journal?.markCompletedAndClose()
                else -> journal?.abandon()
            }
            throttler.finish(terminalStateOf(result))
            return result
        } catch (e: EngineException) {
            request.journal?.abandon()
            throttler.finish(ProgressState.FAILED)
            return ExtractResult.Failed(mapEngineError(e, opts), throttler.currentEntriesDone())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            request.journal?.abandon()
            throttler.finish(ProgressState.FAILED)
            return ExtractResult.Failed(
                ErrorCatalog.providerIo(t.message ?: t.javaClass.simpleName),
                throttler.currentEntriesDone(),
            )
        } finally {
            runCatching { request.source.close() }
            runCatching { request.destination.close() }
        }
    }

    private fun terminalStateOf(result: ExtractResult): ProgressState = when (result) {
        is ExtractResult.Completed -> ProgressState.COMPLETED
        is ExtractResult.Aborted -> ProgressState.ABORTED
        is ExtractResult.Failed -> ProgressState.FAILED
    }

    private fun mapEngineError(e: EngineException, opts: com.limao.unzip.engine.api.ExtractOptions): UserError =
        when (e.code) {
            EngineErrorCode.ENCRYPTED -> ErrorCatalog.needsPassword()
            EngineErrorCode.BAD_PASSWORD -> ErrorCatalog.wrongPassword(1)
            EngineErrorCode.CRC -> ErrorCatalog.crcMismatch(e.entryPath ?: "?", opts.entryErrorPolicy)
            EngineErrorCode.FORMAT, EngineErrorCode.READ -> ErrorCatalog.archiveCorrupt(e.message ?: "")
            EngineErrorCode.NO_SPACE -> ErrorCatalog.notEnoughSpace(null, null)
            EngineErrorCode.METHOD -> ErrorCatalog.unsupportedMethod(e.entryPath ?: "?", 0)
            EngineErrorCode.TOO_LARGE, EngineErrorCode.LIMIT -> ErrorCatalog.entryTooLarge(opts.maxTotalUncompressedBytes)
            EngineErrorCode.WRITE, EngineErrorCode.IO, EngineErrorCode.NAME_UNSAFE ->
                ErrorCatalog.providerIo(e.message ?: "")
            EngineErrorCode.ABORTED, EngineErrorCode.OK -> ErrorCatalog.cancelled(0)
        }

    private class ExtractionJobImpl(
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
