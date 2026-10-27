package com.limao.unzip.engine.api

import com.limao.unzip.engine.journal.RecoveryJournal

data class ExtractOptions(
    val chunkSizeBytes: Int = DEFAULT_CHUNK_BYTES,
    val workerThreads: Int = 1,
    val progressMinIntervalMs: Long = 1_000L,
    val progressMode: ProgressMode = ProgressMode.DETAILED,
    val deleteIncompleteOnAbort: Boolean = true,
    val overwritePolicy: OverwritePolicy = OverwritePolicy.REPLACE,
    val preserveTimestamps: Boolean = true,
    val maxEntryCount: Long = 200_000L,
    val maxTotalUncompressedBytes: Long = 512L shl 30,
    val entryErrorPolicy: EntryErrorPolicy = EntryErrorPolicy.SKIP_CONTINUE,
    /** 解密密码（加密 zip：ZipCrypto/AES-256，由 native 引擎消费）。 */
    val password: String? = null,
    /** 每条目完成回调，在 worker 线程执行，勿阻塞。 */
    val onEntryDone: ((EntryDone) -> Unit)? = null,
) {
    init {
        require(chunkSizeBytes >= MIN_CHUNK_BYTES && chunkSizeBytes <= MAX_CHUNK_BYTES) {
            "chunkSizeBytes 必须在 ${MIN_CHUNK_BYTES}..${MAX_CHUNK_BYTES} 之间"
        }
        require(chunkSizeBytes and (chunkSizeBytes - 1) == 0) { "chunkSizeBytes 必须是 2 的幂" }
        require(workerThreads in 1..8) { "workerThreads 必须在 1..8 之间" }
    }

    companion object {
        const val DEFAULT_CHUNK_BYTES: Int = 64 * 1024
        const val MIN_CHUNK_BYTES: Int = 16 * 1024
        const val MAX_CHUNK_BYTES: Int = 1 shl 20
    }
}

data class ExtractRequest(
    val source: SeekableInput,
    val destination: OutputSink,
    val filter: EntryFilter = EntryFilter.ALL,
    val passwordProvider: (suspend () -> String?)? = null,
    val options: ExtractOptions = ExtractOptions(),
    /** 断点续传：已记录的条目将被跳过（进入 skipBits）。 */
    val journal: RecoveryJournal? = null,
)
