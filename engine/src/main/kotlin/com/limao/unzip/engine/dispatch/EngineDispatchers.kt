package com.limao.unzip.engine.dispatch

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.math.max
import kotlin.math.min

/**
 * 引擎级全局并发预算（F8 修正）：所有任务共享同一个受限调度器，
 * 并发 native/引擎会话总数被钳制在 min(核数-1, 4)，而非每任务各自为政。
 */
@OptIn(ExperimentalCoroutinesApi::class)
object EngineDispatchers {
    val WORKERS: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(
        max(1, min(Runtime.getRuntime().availableProcessors() - 1, 4)),
    )
}
