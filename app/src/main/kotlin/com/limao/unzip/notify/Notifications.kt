package com.limao.unzip.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.limao.unzip.ObjectGraph
import com.limao.unzip.R
import com.limao.unzip.engine.api.ProgressSnapshot
import com.limao.unzip.engine.api.basis
import com.limao.unzip.engine.error.ErrorCatalog
import com.limao.unzip.worker.CancelReceiver

/** 通知栏：进度（≤1Hz，与引擎节流同源）+ 取消按钮（CancelReceiver → WorkManager 取消链路）。 */
object Notifications {

    private const val CHANNEL_ID = "extraction"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun foregroundInfo(context: Context, workId: String, indeterminate: Boolean): ForegroundInfo {
        ensureChannel(context)
        val notification = build(context, workId, indeterminate, 0, "", "")
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(workId.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(workId.hashCode(), notification)
        }
    }

    fun updateProgress(context: Context, workId: String, snap: ProgressSnapshot) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val indeterminate = snap.outputBytesTotal <= 0
        val (pct, text) = percentOf(snap)
        nm.notify(workId.hashCode(), build(context, workId, indeterminate, pct, text, snap.currentEntryPath ?: ""))
    }

    fun showFinal(context: Context, workId: String, failed: Boolean, text: String) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(if (failed) R.string.notif_title_failed else R.string.notif_title_done))
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify(workId.hashCode(), n)
    }

    private fun build(context: Context, workId: String, indeterminate: Boolean, pct: Int, text: String, file: String): Notification {
        val cancelIntent = PendingIntent.getBroadcast(
            context,
            workId.hashCode(),
            Intent(context, CancelReceiver::class.java).putExtra(CancelReceiver.EXTRA_WORK_ID, workId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.notif_title_running))
            .setContentText(text.ifEmpty { context.getString(R.string.preparing) })
            .setSubText(file.ifEmpty { null })
            .setProgress(100, pct, indeterminate)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, context.getString(R.string.notif_cancel), cancelIntent)
            .build()
    }

    private fun percentOf(snap: ProgressSnapshot): Pair<Int, String> = when (snap.basis()) {
        com.limao.unzip.engine.api.ProgressBasis.OUTPUT_BYTES ->
            (snap.outputBytesDone * 100 / snap.outputBytesTotal).toInt() to
                "${ErrorCatalog.formatBytes(snap.outputBytesDone)} / ${ErrorCatalog.formatBytes(snap.outputBytesTotal)}"
        com.limao.unzip.engine.api.ProgressBasis.ENTRY_COUNT ->
            (snap.entriesDone * 100 / snap.entriesTotal).toInt() to
                "${snap.entriesDone} / ${snap.entriesTotal} 个条目"
        com.limao.unzip.engine.api.ProgressBasis.INDETERMINATE ->
            0 to "已解压 ${snap.entriesDone} 个条目"
    }
}
