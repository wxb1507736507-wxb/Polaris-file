package com.limao.unzip.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import java.util.UUID

/** 通知栏"取消"→ WorkManager.cancelWorkById → Worker 协程被取消 → 引擎 abort 标志置位。 */
class CancelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val workId = intent.getStringExtra(EXTRA_WORK_ID) ?: return
        runCatching { WorkManager.getInstance(context).cancelWorkById(UUID.fromString(workId)) }
    }

    companion object {
        const val EXTRA_WORK_ID = "work_id"
    }
}
