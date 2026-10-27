package com.limao.unzip.native

/**
 * native 引擎桥（libarchive）。失败安全：libxe 缺失/加载失败时 available=false，
 * 路由层回退参考引擎。JVM 单测环境恒为 false（保证既有测试行为不变）。
 *
 * 映射码与 xe_jni.cpp 对齐：FMT_ZIP=1 FMT_SEVEN_Z=2 FMT_TAR=3 FMT_GZ=11 FMT_RAW=12 …
 */
object XeBridge {

    const val FMT_UNKNOWN = 0
    const val FMT_ZIP = 1
    const val FMT_SEVEN_Z = 2
    const val FMT_TAR = 3
    const val FMT_GZ = 11
    const val FMT_RAW = 12

    /** 引擎错误码（与 EngineErrorCode 对齐）。 */
    const val XE_OK = 0
    const val XE_ABORTED = 1
    const val XE_ERR_FORMAT = 5
    const val XE_ERR_READ = 6
    const val XE_ERR_WRITE = 7
    const val XE_ERR_LIMIT = 13
    const val XE_ERR_ENCRYPTED = 2

    val available: Boolean = try {
        System.loadLibrary("xe")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    fun probe(fd: Int): Int = if (available) nativeProbe(fd) else FMT_UNKNOWN

    /** 返回 [entryCount, totalUncompressed, anyEncrypted]；失败返回空数组。 */
    fun scan(fd: Int, sink: Any): LongArray = if (available) nativeScan(fd, sink) else LongArray(0)

    /** 返回 [code, entriesDone, bytesDone]。passphrase 供加密 zip 解密。 */
    fun extract(
        fd: Int,
        callbacks: Any,
        skipBits: LongArray?,
        ignoreEntryErrors: Boolean,
        passphrase: String?,
    ): LongArray = if (available) nativeExtract(fd, callbacks, skipBits, ignoreEntryErrors, passphrase)
    else longArrayOf(XE_ERR_FORMAT.toLong(), 0, 0)

    fun lastError(): String = if (available) nativeGetLastError() else ""

    /** 压缩格式码（与 xe_jni.cpp 对齐）：1=zip 2=7z 3=tar 4=tar.gz。 */
    const val CFMT_ZIP = 1
    const val CFMT_SEVEN_Z = 2
    const val CFMT_TAR = 3
    const val CFMT_TAR_GZ = 4

    /** 返回 [code, entriesDone, bytesInput, bytesOutput]。 */
    fun compress(outFd: Int, formatCode: Int, level: Int, callbacks: Any): LongArray =
        if (available) nativeCompress(outFd, formatCode, level, callbacks)
        else longArrayOf(XE_ERR_FORMAT.toLong(), 0, 0, 0)

    private external fun nativeProbe(fd: Int): Int
    private external fun nativeScan(fd: Int, sink: Any): LongArray
    private external fun nativeExtract(fd: Int, callbacks: Any, skipBits: LongArray?, ignoreEntryErrors: Boolean, passphrase: String?): LongArray
    private external fun nativeCompress(outFd: Int, formatCode: Int, level: Int, callbacks: Any): LongArray
    private external fun nativeGetLastError(): String
}
