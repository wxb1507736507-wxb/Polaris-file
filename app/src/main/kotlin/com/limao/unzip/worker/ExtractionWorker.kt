package com.limao.unzip.worker

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.limao.unzip.ObjectGraph
import com.limao.unzip.engine.api.ArchiveInfo
import com.limao.unzip.engine.api.ExtractRequest
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.engine.FileChannelInput
import com.limao.unzip.engine.journal.RecoveryJournal
import com.limao.unzip.notify.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 后台解压任务：CoroutineWorker + setForeground（dataSync 类型）。
 * 进度经 ObjectGraph.activeProgress 供 UI 直连，同时按 1Hz 节流刷新通知栏。
 * 断点恢复：进程被杀 → WorkManager retry → journal 重放 skipBits 跳过已完成条目。
 * 取消链路：通知/UI → cancelWorkById → 协程 CancellationException → job.cancel() → 引擎标志位。
 */
class ExtractionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        Notifications.foregroundInfo(applicationContext, id.toString(), indeterminate = true)

    override suspend fun doWork(): Result {
        return try {
            doWorkInner()
        } catch (t: Throwable) {
            android.util.Log.e("PolarisWorker", "doWork crashed run=$runAttemptCount", t)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun doWorkInner(): Result {
        setForeground(getForegroundInfo())
        // 路径模式（v0.4：全文件权限直路径）优先；Uri/SAF 模式保留兼容
        val srcPath = inputData.getString(KEY_SRC_PATH)
        val dstPath = inputData.getString(KEY_DST_PATH)
        if (srcPath != null && dstPath != null) {
            return runPathExtraction(srcPath, dstPath)
        }
        val srcUri = Uri.parse(inputData.getString(KEY_SRC) ?: return Result.failure())
        val dstUri = Uri.parse(inputData.getString(KEY_DST) ?: return Result.failure())

        val resolver = applicationContext.contentResolver
        resolver.openFileDescriptor(srcUri, "r")?.let { pfd ->
            // fd 所有权转移给 AndroidFdInput（native 引擎直接消费裸 fd）
            val source = com.limao.unzip.io.AndroidFdInput(pfd.detachFd())
            val info = ObjectGraph.extractor.probe(source)

            val journalDir = File(applicationContext.filesDir, "jobs").apply { mkdirs() }
            val journalFile = File(journalDir, "$id.journal")
            val journal = RecoveryJournal.open(journalFile)
                ?: RecoveryJournal.create(journalFile, info.entryCount)

            val request = ExtractRequest(
                source = source,
                destination = ObjectGraph.sink(dstUri),
                journal = journal,
            )
            val job = ObjectGraph.extractor.submit(request)
            ObjectGraph.activeJobId.value = id

            // 进度 → UI/通知；协程挂在 worker 作业下，随取消一起回收
            val notifier = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
                job.progress.collect { snap ->
                    ObjectGraph.activeProgress.value = snap
                    Notifications.updateProgress(applicationContext, id.toString(), snap)
                }
            }

            try {
                val result = job.await()
                notifier.cancel()
                ObjectGraph.activeResult.value = result
                return finishWith(result, journalFile)
            } catch (ce: CancellationException) {
                // WorkManager 取消：置位引擎中止标志，等待协作式收尾（Aborted 结果丢弃，journal 保留）
                job.cancel()
                withTimeoutOrNull(GRACEFUL_ABORT_MS) { runCatching { job.await() } }
                notifier.cancel()
                Notifications.showFinal(applicationContext, id.toString(), failed = false, text = "已取消，可随时继续")
                throw ce
            }
        }
        Notifications.showFinal(applicationContext, id.toString(), failed = true, text = "无法打开压缩包")
        return Result.failure()
    }

    private fun finishWith(result: ExtractResult, journalFile: File): Result = when (result) {
        is ExtractResult.Completed -> {
            Notifications.showFinal(
                applicationContext, id.toString(), failed = false,
                text = "共 ${result.entriesDone} 个条目 · ${result.elapsedMs / 1000} 秒" +
                    if (result.warnings.isNotEmpty()) "（${result.warnings.size} 项警告）" else "",
            )
            journalFile.delete()
            Result.success()
        }
        is ExtractResult.Aborted -> {
            Notifications.showFinal(
                applicationContext, id.toString(), failed = false,
                text = "已取消：已完整解压 ${result.entriesDone} 个条目，可随时继续",
            )
            Result.failure() // 用户取消不重试；journal 保留供续传
        }
        is ExtractResult.Failed -> {
            Notifications.showFinal(
                applicationContext, id.toString(), failed = true,
                text = result.error.title + "：" + result.error.message,
            )
            if (result.error.id == com.limao.unzip.engine.error.ErrorId.PROVIDER_IO && runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private suspend fun runPathExtraction(srcPath: String, dstPath: String): Result {
        return try {
            ObjectGraph.activeDstPath = dstPath
            val pfd = android.os.ParcelFileDescriptor.open(
                java.io.File(srcPath),
                android.os.ParcelFileDescriptor.MODE_READ_ONLY,
            )
            val source = com.limao.unzip.io.AndroidFdInput(pfd.detachFd())
            // 复用点击压缩包时的预检缓存，消除重复全量扫描（点击卡顿根因）
            val cached = ObjectGraph.cachedProbe
            val info = if (cached != null && cached.first == srcPath) {
                cached.second
            } else {
                ObjectGraph.extractor.probe(source).also {
                    ObjectGraph.cachedProbe = srcPath to it
                }
            }
            val journalDir = java.io.File(applicationContext.filesDir, "jobs").apply { mkdirs() }
            val journalFile = java.io.File(journalDir, "$id.journal")
            val journal = RecoveryJournal.open(journalFile)
                ?: RecoveryJournal.create(journalFile, info.entryCount)
            val request = ExtractRequest(
                source = source,
                destination = com.limao.unzip.io.AndroidFileSink(java.io.File(dstPath)),
                journal = journal,
                options = com.limao.unzip.engine.api.ExtractOptions(
                    password = inputData.getString(KEY_PASSWORD),
                ),
            )
            val job = ObjectGraph.extractor.submit(request)
            ObjectGraph.activeJobId.value = id
            val notifier = CoroutineScope(coroutineContext).launch {
                job.progress.collect { snap ->
                    ObjectGraph.activeProgress.value = snap
                    Notifications.updateProgress(applicationContext, id.toString(), snap)
                }
            }
            try {
                val result = job.await()
                notifier.cancel()
                ObjectGraph.activeResult.value = result
                // 记录解压历史：压缩包列表据此显示"已解压"并可直达目录
                if (result is ExtractResult.Completed && result.entriesDone > 0) {
                    runCatching { com.limao.unzip.files.ExtractHistory.record(applicationContext, srcPath, dstPath) }
                }
                finishWith(result, journalFile)
            } catch (ce: CancellationException) {
                job.cancel()
                withTimeoutOrNull(GRACEFUL_ABORT_MS) { runCatching { job.await() } }
                notifier.cancel()
                Notifications.showFinal(applicationContext, id.toString(), failed = false, text = "已取消，可随时继续")
                throw ce
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            android.util.Log.e("PolarisWorker", "path extract failed: $srcPath -> $dstPath", t)
            Notifications.showFinal(applicationContext, id.toString(), failed = true, text = t.message ?: "无法打开压缩包")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_SRC = "src_uri"
        const val KEY_DST = "dst_uri"
        const val KEY_SRC_PATH = "src_path"
        const val KEY_DST_PATH = "dst_path"
        const val KEY_PASSWORD = "password"
        const val GRACEFUL_ABORT_MS = 10_000L
    }
}
