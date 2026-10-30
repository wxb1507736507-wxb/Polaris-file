package com.limao.unzip.files

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.text.format.DateFormat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.limao.unzip.R
import com.limao.unzip.files.ProgressActivity
import com.limao.unzip.files.TextEditorActivity
import com.limao.unzip.files.ImagePreviewActivity
import com.limao.unzip.files.PdfReaderActivity
import com.limao.unzip.files.PlayerActivity
import com.limao.unzip.worker.ExtractionWorker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一文件打开分派器：首页最近文件与分类列表点击同一文件时，行为与目标页面完全一致。
 * 顺序：目录 → 压缩包 → PDF → 图片 → 音视频 → Office → 文本 → 外部应用。
 */
object FileOpener {

    fun open(activity: Activity, name: String, path: String?, uri: Uri?, isDirHint: Boolean = false) {
        when {
            isDirHint || (path != null && File(path).isDirectory) -> activity.startActivity(
                Intent(activity, FileListActivity::class.java).apply {
                    putExtra(FileListActivity.EXTRA_MODE, "browser")
                    putExtra(FileListActivity.EXTRA_PATH, path)
                },
            )

            path != null && ArchiveScanner.isArchive(name) -> openArchive(activity, File(path))

            name.endsWith(".pdf", true) && path != null -> activity.startActivity(
                Intent(activity, PdfReaderActivity::class.java).putExtra(PdfReaderActivity.EXTRA_PATH, path),
            )

            path != null && isImageName(name) -> activity.startActivity(
                Intent(activity, ImagePreviewActivity::class.java).putExtra(ImagePreviewActivity.EXTRA_PATH, path),
            )

            (PlayerActivity.isVideoExt(name) || FileItems.isAudioName(name)) && path != null -> activity.startActivity(
                Intent(activity, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_PATH, path),
            )

            OfficeText.supported(name) && path != null -> openEditor(activity, File(path))

            FileItems.isTextName(name) && path != null -> openEditor(activity, File(path))

            uri != null -> runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, activity.contentResolver.getType(uri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                )
            }.onFailure {
                android.widget.Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_cant_open, name),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }

            path != null -> runCatching {
                val uri2 = androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", File(path))
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri2, activity.contentResolver.getType(uri2) ?: "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                )
            }.onFailure {
                android.widget.Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_cant_open, name),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun openEditor(activity: Activity, file: File) {
        activity.startActivity(
            Intent(activity, TextEditorActivity::class.java).putExtra(TextEditorActivity.EXTRA_PATH, file.absolutePath),
        )
    }

    /** 压缩包打开：有解压记录先询问（打开目录/重新解压），否则直接解压。首页与分类页共用。 */
    fun openArchive(activity: Activity, file: File) {
        val rec = ExtractHistory.lookup(activity, file.absolutePath)
        if (rec != null) {
            val dfmt = SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault())
            AlertDialog.Builder(activity)
                .setTitle(file.name)
                .setMessage(activity.getString(R.string.ask_msg, dfmt.format(Date(rec.time))))
                .setPositiveButton(R.string.ask_open) { _, _ ->
                    activity.startActivity(
                        Intent(activity, FileListActivity::class.java).apply {
                            putExtra(FileListActivity.EXTRA_MODE, "browser")
                            putExtra(FileListActivity.EXTRA_PATH, rec.dstDir)
                        },
                    )
                }
                .setNegativeButton(R.string.ask_re) { _, _ ->
                    val dst = File(file.parentFile ?: Environment.getExternalStorageDirectory(), file.name.substringBeforeLast('.'))
                    dst.mkdirs()
                    startExtract(activity, file, dst)
                }
                .setNeutralButton(R.string.btn_cancel, null)
                .show()
        } else {
            val dst = File(file.parentFile ?: Environment.getExternalStorageDirectory(), file.name.substringBeforeLast('.'))
            dst.mkdirs()
            startExtract(activity, file, dst)
        }
    }

    fun startExtract(activity: Activity, src: File, dst: File, password: String? = null) {
        val request = OneTimeWorkRequestBuilder<ExtractionWorker>()
            .setInputData(
                workDataOf(
                    ExtractionWorker.KEY_SRC_PATH to src.absolutePath,
                    ExtractionWorker.KEY_DST_PATH to dst.absolutePath,
                    ExtractionWorker.KEY_PASSWORD to password,
                ),
            )
            .build()
        WorkManager.getInstance(activity).enqueue(request)
        activity.startActivity(
            Intent(activity, ProgressActivity::class.java)
                .putExtra(ProgressActivity.EXTRA_NAME, src.name)
                .putExtra(ProgressActivity.EXTRA_SRC, src.absolutePath)
                .putExtra(ProgressActivity.EXTRA_DST, dst.absolutePath),
        )
    }

    fun isImageName(name: String): Boolean = FileItems.iconResFor(name) == R.drawable.ic_image
}
