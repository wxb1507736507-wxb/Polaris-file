package com.limao.unzip.selftest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.limao.unzip.ObjectGraph
import com.limao.unzip.engine.api.ExtractOptions
import com.limao.unzip.engine.api.ExtractRequest
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.api.ProgressState
import com.limao.unzip.engine.engine.FileChannelInput
import com.limao.unzip.engine.engine.FileSink
import com.limao.unzip.engine.engine.ZipReferenceEngine
import com.limao.unzip.engine.extractor.ArchiveExtractorImpl
import com.limao.unzip.engine.journal.RecoveryJournal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest

/**
 * 真机自测入口（仅 debug 构建）：adb 广播触发，走与正式链路同一套引擎
 * （probe→scan→journal→extract→节流进度），结果与产物哈希写 logcat（tag=LimaoSelfTest）。
 *
 * 用法：
 *   adb push test.zip /sdcard/Android/data/com.limao.unzip/files/selftest.zip
 *   adb shell am broadcast -a com.limao.unzip.SELF_TEST -n com.limao.unzip/.selftest.SelfTestReceiver \
 *       --es zip_path /sdcard/Android/data/com.limao.unzip/files/selftest.zip
 *   adb shell ls -R /sdcard/Android/data/com.limao.unzip/files/selftest-out
 */
class SelfTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra(EXTRA_CMD) ?: "extract"
        when (cmd) {
            "compress" -> {
                val src = intent.getStringExtra(EXTRA_ZIP) ?: return
                val fmt = intent.getStringExtra(EXTRA_FORMAT) ?: "7z"
                val out = intent.getStringExtra(EXTRA_OUT) ?: "$src.out.$fmt"
                val pending = goAsync()
                Thread {
                    try {
                        runCompressRoundtrip(context, src, fmt, out)
                    } catch (t: Throwable) {
                        Log.e(TAG, "compress selftest crashed", t)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            else -> {
                val zipPath = intent.getStringExtra(EXTRA_ZIP) ?: run {
                    Log.e(TAG, "missing extra $EXTRA_ZIP")
                    return
                }
                val abortAfter = intent.getIntExtra(EXTRA_ABORT_AFTER, -1)
                val resume = intent.getBooleanExtra(EXTRA_RESUME, false)
                val pending = goAsync()
                Thread {
                    try {
                        runSelfTest(context, zipPath, abortAfter, resume)
                    } catch (t: Throwable) {
                        Log.e(TAG, "selftest crashed", t)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    /** 压缩回环：源目录 → 归档（RoutingCompressor）→ 解压（RoutingExtractor）→ 哈希比对。 */
    private fun runCompressRoundtrip(context: Context, srcDir: String, fmt: String, outPath: String) {
        val format = when (fmt) {
            "zip" -> com.limao.unzip.engine.api.CreateFormat.ZIP
            "tar", "tgz", "tar.gz" -> com.limao.unzip.engine.api.CreateFormat.TAR_GZ
            else -> com.limao.unzip.engine.api.CreateFormat.SEVEN_Z
        }
        val src = java.io.File(srcDir)
        val out = java.io.File(outPath)
        val request = com.limao.unzip.engine.api.CompressRequest(
            source = com.limao.unzip.io.DirFdCompressSource(src),
            output = com.limao.unzip.io.AndroidFileArchiveOutput(out),
            format = format,
        )
        val result = kotlinx.coroutines.runBlocking { ObjectGraph.compressor.compress(request) }
        if (result !is com.limao.unzip.engine.api.ExtractResult.Completed) {
            Log.e(TAG, "COMPRESS RESULT=${(result as com.limao.unzip.engine.api.ExtractResult.Failed).error.message}")
            return
        }
        Log.i(TAG, "COMPRESS OK format=$fmt entries=${result.entriesDone} srcBytes=${result.bytesDone} archiveBytes=${result.bytesInput} archiveSize=${out.length()}")

        // 回环解压
        val extractDir = java.io.File(context.getExternalFilesDir(null), "roundtrip-out")
        extractDir.deleteRecursively()
        extractDir.mkdirs()
        val pfd = android.os.ParcelFileDescriptor.open(out, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val input = com.limao.unzip.io.AndroidFdInput(pfd.detachFd())
        val ex = kotlinx.coroutines.runBlocking {
            ObjectGraph.extractor.extract(
                com.limao.unzip.engine.api.ExtractRequest(
                    source = input,
                    destination = com.limao.unzip.io.AndroidFileSink(extractDir),
                ),
            )
        }
        if (ex !is com.limao.unzip.engine.api.ExtractResult.Completed) {
            Log.e(TAG, "ROUNDTRIP EXTRACT FAILED: $ex")
            return
        }
        // 哈希比对（显式逐元素，便于暴露真实差异）
        val srcHashes: List<Pair<String, String>> = src.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(src).invariantSeparatorsPath to sha256(it) }.sortedBy { it.first }.toList()
        val outHashes: List<Pair<String, String>> = extractDir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(extractDir).invariantSeparatorsPath to sha256(it) }.sortedBy { it.first }.toList()
        var mismatch = srcHashes.size != outHashes.size
        if (!mismatch) {
            for (i in srcHashes.indices) {
                if (srcHashes[i] != outHashes[i]) {
                    mismatch = true
                    val a = srcHashes[i]; val b = outHashes[i]
                    Log.e(TAG, "diff#$i len=${a.first.length}/${b.first.length} hashEq=${a.second == b.second} [${a.first}] vs [${b.first}]")
                    break
                }
            }
        }
        Log.i(TAG, if (mismatch) "ROUNDTRIP=MISMATCH src=${srcHashes.count()} out=${outHashes.count()}" else "ROUNDTRIP=PASS files=${srcHashes.count()}")
        if (mismatch) {
            srcHashes.zip(outHashes).take(3).forEach { (a, b) -> Log.e(TAG, "diff: src=$a out=$b") }
        }
    }

    private fun runSelfTest(context: Context, zipPath: String, abortAfter: Int, resume: Boolean) {
        val extractor = ObjectGraph.extractor
        val src = File(zipPath)
        val outDir = File(context.getExternalFilesDir(null), "selftest-out")
        if (!resume) {
            outDir.deleteRecursively() // 全新任务清空输出；续传任务保留
        }
        outDir.mkdirs()

        val journalFile = File(context.filesDir, "selftest.journal")
        var emitted = 0
        val result = runBlocking {
            val pfd = android.os.ParcelFileDescriptor.open(src, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val input = com.limao.unzip.io.AndroidFdInput(pfd.detachFd())
            val info = extractor.probe(input)
            Log.i(TAG, "probe: format=${info.format} engine=${info.engineId} entries=${info.entryCount} encrypted=${info.anyEncrypted} abortAfter=$abortAfter resume=$resume")
            // resume=true：复用既有 journal（断点续传）；否则全新任务
            val journal = if (resume) {
                RecoveryJournal.open(journalFile) ?: RecoveryJournal.create(journalFile, info.entryCount)
            } else {
                journalFile.delete()
                RecoveryJournal.create(journalFile, info.entryCount)
            }
            Log.i(TAG, "journal: completed=${journal.completedCount()}/${info.entryCount}")
            var job: com.limao.unzip.engine.api.ExtractionJob? = null
            val request = ExtractRequest(
                source = input,
                destination = com.limao.unzip.io.AndroidFileSink(outDir),
                journal = journal,
                options = ExtractOptions(
                    progressMinIntervalMs = 200,
                    onEntryDone = { if (abortAfter >= 0 && it.index == abortAfter.toLong()) job?.cancel() },
                ),
            )
            val j = extractor.submit(request)
            job = j
            val collector = CoroutineScope(Dispatchers.Unconfined).launch {
                j.progress.collect { s ->
                    emitted++
                    if (emitted <= 3 || s.state != ProgressState.RUNNING) {
                        Log.i(TAG, "progress[$emitted] ${s.state} out=${s.outputBytesDone}/${s.outputBytesTotal} entries=${s.entriesDone}/${s.entriesTotal} path=${s.currentEntryPath}")
                    }
                }
            }
            val r = j.await()
            collector.cancel()
            r
        }

        when (result) {
            is ExtractResult.Completed -> {
                val files = outDir.walkTopDown().filter { it.isFile }.toList().sortedBy { it.absolutePath }
                Log.i(TAG, "RESULT=COMPLETED entries=${result.entriesDone} bytes=${result.bytesDone} " +
                    "input=${result.bytesInput} warnings=${result.warnings.size} files=${files.size} progressEvents=$emitted")
                for (f in files) {
                    Log.i(TAG, "FILE ${f.relativeTo(outDir).path} size=${f.length()} sha256=${sha256(f)}")
                }
            }
            is ExtractResult.Aborted -> Log.i(TAG, "RESULT=ABORTED entries=${result.entriesDone} partial=${result.abortPoint.partialPath}")
            is ExtractResult.Failed -> Log.i(TAG, "RESULT=FAILED id=${result.error.id} msg=${result.error.message} detail=${result.error.logDetail}")
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    companion object {
        const val TAG = "LimaoSelfTest"
        const val EXTRA_ZIP = "zip_path"
        const val EXTRA_ABORT_AFTER = "abort_after"
        const val EXTRA_RESUME = "resume"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_OUT = "out"
    }
}
