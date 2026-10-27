package com.limao.unzip

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.api.ProgressState
import com.limao.unzip.engine.api.basis
import com.limao.unzip.engine.error.ErrorCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** UI 状态机（§1.3 的 v0.1 子集：Preparing/Running/Done/Aborted/Failed/Idle）。 */
sealed interface ExtractionUiState {
    data object Idle : ExtractionUiState
    data object Preparing : ExtractionUiState
    data class Running(val percent: Int, val statsText: String, val currentEntryPath: String) : ExtractionUiState
    data class Done(val entriesDone: Long, val bytesDone: Long) : ExtractionUiState
    data class Aborted(val entriesDone: Long) : ExtractionUiState
    data class Failed(val title: String, val message: String) : ExtractionUiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val uiState = MutableStateFlow<ExtractionUiState>(ExtractionUiState.Idle)

    fun track(workId: UUID) {
        viewModelScope.launch {
            ObjectGraph.activeProgress.collect { snap ->
                if (snap == null) return@collect
                when (snap.state) {
                    ProgressState.RUNNING, ProgressState.ABORTING ->
                        uiState.value = render(snap.outputBytesDone, snap.outputBytesTotal, snap.entriesDone, snap.entriesTotal, snap.currentEntryPath)
                    ProgressState.COMPLETED -> uiState.value = ExtractionUiState.Preparing // 等结果事件覆盖
                    ProgressState.ABORTED, ProgressState.FAILED -> Unit // 由 result 事件渲染
                }
            }
        }
        viewModelScope.launch {
            ObjectGraph.activeResult.collect { result ->
                if (result == null) return@collect
                uiState.value = when (result) {
                    is ExtractResult.Completed -> ExtractionUiState.Done(result.entriesDone, result.bytesDone)
                    is ExtractResult.Aborted -> ExtractionUiState.Aborted(result.entriesDone)
                    is ExtractResult.Failed -> ExtractionUiState.Failed(result.error.title, result.error.message)
                }
            }
        }
    }

    fun cancel() {
        ObjectGraph.activeJobId.value?.let {
            runCatching { WorkManager.getInstance(getApplication()).cancelWorkById(it) }
        }
    }

    private fun render(out: Long, outTotal: Long, entriesDone: Long, entriesTotal: Long, path: String?): ExtractionUiState {
        val pct = if (outTotal > 0) (out * 100 / outTotal).toInt() else if (entriesTotal > 0) (entriesDone * 100 / entriesTotal).toInt() else 0
        val stats = if (outTotal > 0) {
            "${ErrorCatalog.formatBytes(out)} / ${ErrorCatalog.formatBytes(outTotal)} · ${entriesDone}/${entriesTotal} 条目"
        } else {
            "${entriesDone}/${entriesTotal} 条目"
        }
        return ExtractionUiState.Running(pct, stats, path ?: "")
    }
}
