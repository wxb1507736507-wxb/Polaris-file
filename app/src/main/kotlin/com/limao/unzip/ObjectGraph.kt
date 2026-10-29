package com.limao.unzip

import android.content.Context
import android.net.Uri
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.engine.api.ProgressSnapshot
import com.limao.unzip.engine.extractor.ArchiveExtractorImpl
import com.limao.unzip.engine.engine.ZipReferenceEngine
import com.limao.unzip.saf.DocumentTreeSink
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/** 极简服务定位 + 进程内进度通道（解压/压缩共用通道，v0.2 路由引擎）。 */
object ObjectGraph {

    val extractor by lazy { com.limao.unzip.io.RoutingExtractor() }
    val compressor by lazy { com.limao.unzip.io.RoutingCompressor() }

    /** 当前活动任务 id（取消链路）与进度/终态通道（UI 直连，conflate 语义）。 */
    val activeJobId = MutableStateFlow<UUID?>(null)
    val activeProgress = MutableStateFlow<ProgressSnapshot?>(null)
    val activeResult = MutableStateFlow<ExtractResult?>(null)

    /** 最近一次用户动作的提示（文件页跳转反馈）。 */
    @Volatile var lastAction: String? = null

    /** 当前解压任务的目标目录（ProgressActivity"查看已解压文件"用）。 */
    @Volatile var activeDstPath: String? = null

    /** 解压预检缓存：path → probe 结果（消除点击压缩包后的重复全量扫描，此为卡顿根因）。 */
    @Volatile var cachedProbe: Pair<String, com.limao.unzip.engine.api.ArchiveInfo>? = null

    fun sink(treeUri: Uri): DocumentTreeSink =
        DocumentTreeSink(appContext, treeUri)

    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
