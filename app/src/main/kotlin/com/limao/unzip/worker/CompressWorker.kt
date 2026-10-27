package com.limao.unzip.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.limao.unzip.ObjectGraph
import com.limao.unzip.engine.api.CompressRequest
import com.limao.unzip.engine.api.CreateFormat
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.api.ProgressState
import com.limao.unzip.io.SafArchiveOutput
import com.limao.unzip.io.SafTreeCompressSource
import com.limao.unzip.notify.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 后台压缩任务：SAF 树 → 归档（7z/tar.gz 走 native，zip 走参考引擎）。取消链路与解压一致。 */
class CompressWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        Notifications.foregroundInfo(applicationContext, id.toString(), indeterminate = true)

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())
        // 路径模式（v0.5：全文件权限直通道）优先；SAF 树模式保留兼容
        val pSrc = inputData.getString(KEY_SRC_PATH)
        val pDst = inputData.getString(KEY_OUT_PATH)
        if (pSrc != null && pDst != null) {
            return runPathCompression(pSrc, pDst)
        }
        val srcUri = Uri.parse(inputData.getString(KEY_SRC_TREE) ?: return Result.failure())
        val format = CreateFormat.valueOf(inputData.getString(KEY_FORMAT) ?: CreateFormat.ZIP.name)
        val archiveName = inputData.getString(KEY_ARCHIVE_NAME) ?: "archive.${format.extension}"
        val dstPath = inputData.getString(KEY_DST_PATH)

        val output: com.limao.unzip.engine.api.ArchiveOutput = if (dstPath != null) {
            com.limao.unzip.io.AndroidFileArchiveOutput(java.io.File(dstPath))
        } else {
            val dstTree = Uri.parse(inputData.getString(KEY_DST_TREE) ?: return Result.failure())
            SafArchiveOutput(applicationContext, dstTree, archiveName)
        }
        val request = CompressRequest(
            source = SafTreeCompressSource(applicationContext, srcUri),
            output = output,
            format = format,
        )
        return runRequest(request, archiveName)
    }

    private suspend fun runPathCompression(srcPath: String, dstPath: String): Result {
        val format = CreateFormat.valueOf(inputData.getString(KEY_FORMAT) ?: CreateFormat.ZIP.name)
        val request = CompressRequest(
            source = com.limao.unzip.io.DirFdCompressSource(java.io.File(srcPath)),
            output = com.limao.unzip.io.AndroidFileArchiveOutput(java.io.File(dstPath)),
            format = format,
        )
        return runRequest(request, java.io.File(dstPath).name)
    }

    private suspend fun runRequest(request: CompressRequest, archiveName: String): Result {
        val job = ObjectGraph.compressor.submit(request)
        ObjectGraph.activeJobId.value = id

        val notifier = CoroutineScope(coroutineContext).launch {
            job.progress.collect { snap ->
                ObjectGraph.activeProgress.value = snap
                Notifications.updateProgress(applicationContext, id.toString(), snap)
            }
        }
        return try {
            val result = job.await()
            notifier.cancel()
            ObjectGraph.activeResult.value = result
            when (result) {
                is ExtractResult.Completed -> {
                    Notifications.showFinal(applicationContext, id.toString(), failed = false,
                        text = "$archiveName · ${result.entriesDone} 个条目")
                    Result.success()
                }
                is ExtractResult.Aborted -> {
                    Notifications.showFinal(applicationContext, id.toString(), failed = false,
                        text = "已取消压缩（$archiveName）")
                    Result.failure()
                }
                is ExtractResult.Failed -> {
                    Notifications.showFinal(applicationContext, id.toString(), failed = true,
                        text = result.error.title + "：" + result.error.message)
                    Result.failure()
                }
            }
        } catch (ce: CancellationException) {
            job.cancel()
            withTimeoutOrNull(ExtractionWorker.GRACEFUL_ABORT_MS) { runCatching { job.await() } }
            notifier.cancel()
            Notifications.showFinal(applicationContext, id.toString(), failed = false, text = "已取消压缩")
            throw ce
        }
    }

    companion object {
        const val KEY_SRC_TREE = "src_tree_uri"
        const val KEY_DST_TREE = "dst_tree_uri"
        const val KEY_FORMAT = "format"
        const val KEY_ARCHIVE_NAME = "archive_name"
        const val KEY_DST_PATH = "dst_path"
        const val KEY_SRC_PATH = "src_path"
        const val KEY_OUT_PATH = "out_path"
    }
}
