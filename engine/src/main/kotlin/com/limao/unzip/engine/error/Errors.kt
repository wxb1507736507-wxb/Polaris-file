package com.limao.unzip.engine.error

import com.limao.unzip.engine.api.ArchiveFormat
import com.limao.unzip.engine.api.EntryErrorPolicy

/** 引擎层错误码（与 native xe_result.code 一一对应）。 */
enum class EngineErrorCode(val code: Int) {
    OK(0),
    ABORTED(1),
    ENCRYPTED(2),
    BAD_PASSWORD(3),
    CRC(4),
    FORMAT(5),
    READ(6),
    WRITE(7),
    NO_SPACE(8),
    METHOD(9),
    NAME_UNSAFE(10),
    TOO_LARGE(11),
    IO(12),
    LIMIT(13),
}

class EngineException(
    val code: EngineErrorCode,
    message: String,
    val entryIndex: Long = -1,
    val entryPath: String? = null,
) : RuntimeException(message)

enum class ErrorId {
    NEED_PASSWORD, WRONG_PASSWORD, PASSWORD_LIMIT, CRC_MISMATCH, ARCHIVE_CORRUPT,
    NOT_ENOUGH_SPACE, UNSUPPORTED_FORMAT, UNSUPPORTED_METHOD, UNSUPPORTED_ENCRYPTED,
    ENTRY_NAME_UNSAFE, PERMISSION_LOST, ARCHIVE_CHANGED, PROVIDER_IO, ENTRY_TOO_LARGE, CANCELLED,
}

enum class ErrorAction { RETRY, RETRY_PASSWORD, CONTINUE_PARTIAL, PICK_NEW_SOURCE, PICK_NEW_DEST, OPEN_SETTINGS, NONE }

/** 已渲染好的用户级错误（文案模板填充在引擎层完成，logDetail 不进 UI）。 */
data class UserError(
    val id: ErrorId,
    val title: String,
    val message: String,
    val actions: List<ErrorAction>,
    val logDetail: String,
)

object ErrorCatalog {
    const val MAX_PASSWORD_ATTEMPTS = 3

    fun needsPassword(): UserError = UserError(
        ErrorId.NEED_PASSWORD, "需要密码",
        "此压缩包已加密，请输入密码解压。",
        listOf(ErrorAction.RETRY_PASSWORD), "encrypted needs password",
    )

    fun wrongPassword(attempt: Int): UserError = UserError(
        ErrorId.WRONG_PASSWORD, "密码不正确",
        "密码不正确（第 $attempt/$MAX_PASSWORD_ATTEMPTS 次）。",
        listOf(ErrorAction.RETRY_PASSWORD), "pwd attempt=$attempt",
    )

    fun passwordLimit(): UserError = UserError(
        ErrorId.PASSWORD_LIMIT, "密码错误次数过多",
        "密码连续错误 $MAX_PASSWORD_ATTEMPTS 次，解压已停止。",
        listOf(ErrorAction.RETRY_PASSWORD), "pwd limit",
    )

    fun crcMismatch(path: String, policy: EntryErrorPolicy): UserError = UserError(
        ErrorId.CRC_MISMATCH, "文件校验失败",
        "文件“$path”校验失败，压缩包可能已损坏。" +
            if (policy == EntryErrorPolicy.SKIP_CONTINUE) "已跳过该文件并继续解压其余文件。" else "",
        listOf(ErrorAction.CONTINUE_PARTIAL, ErrorAction.RETRY), "crc path=$path",
    )

    fun archiveCorrupt(detail: String): UserError = UserError(
        ErrorId.ARCHIVE_CORRUPT, "压缩包已损坏",
        "无法读取压缩包：文件结构已损坏。若是下载所得，可能下载不完整。",
        listOf(ErrorAction.PICK_NEW_SOURCE), "corrupt: $detail",
    )

    fun notEnoughSpace(needBytes: Long?, availBytes: Long?): UserError = UserError(
        ErrorId.NOT_ENOUGH_SPACE, "存储空间不足",
        if (needBytes != null && availBytes != null) {
            "存储空间不足：还需约 ${formatBytes(needBytes)}，当前剩余 ${formatBytes(availBytes)}。"
        } else {
            "存储空间不足，无法完成解压。"
        },
        listOf(ErrorAction.PICK_NEW_DEST), "enospc",
    )

    fun unsupportedMethod(path: String, method: Int): UserError = UserError(
        ErrorId.UNSUPPORTED_METHOD, "暂不支持的压缩算法",
        "文件“$path”使用了暂不支持的压缩算法（方法 $method），已跳过。",
        listOf(ErrorAction.NONE), "method=$method path=$path",
    )

    fun unsupportedEncrypted(format: ArchiveFormat): UserError = UserError(
        ErrorId.UNSUPPORTED_ENCRYPTED, "加密压缩包",
        "此${formatLabel(format)}压缩包已加密。加密格式将由原生引擎（NDK 阶段）支持，当前构建暂不可解。",
        listOf(ErrorAction.NONE), "encrypted ref-engine",
    )

    fun entryTooLarge(limit: Long): UserError = UserError(
        ErrorId.ENTRY_TOO_LARGE, "超出解压上限",
        "压缩包内容超过应用设定的上限（${formatBytes(limit)}），已停止以保护设备。",
        listOf(ErrorAction.NONE), "limit=$limit",
    )

    fun providerIo(detail: String): UserError = UserError(
        ErrorId.PROVIDER_IO, "读写出错",
        "读取或写入文件时出错：$detail",
        listOf(ErrorAction.RETRY, ErrorAction.PICK_NEW_DEST), "io: $detail",
    )

    fun cancelled(entriesDone: Long): UserError = UserError(
        ErrorId.CANCELLED, "已取消",
        "已取消：已完整解压 $entriesDone 个条目，可随时继续。",
        listOf(ErrorAction.CONTINUE_PARTIAL), "cancelled at=$entriesDone",
    )

    fun archiveChanged(): UserError = UserError(
        ErrorId.ARCHIVE_CHANGED, "压缩包已变化",
        "压缩包在解压期间被修改，已停止以避免数据错乱。",
        listOf(ErrorAction.RETRY), "source digest mismatch",
    )

    private fun formatLabel(format: ArchiveFormat): String = when (format) {
        ArchiveFormat.SEVEN_Z -> " 7z "
        else -> ""
    }

    fun formatBytes(bytes: Long): String {
        val gb = bytes.toDouble() / (1L shl 30)
        val mb = bytes.toDouble() / (1L shl 20)
        return when {
            gb >= 1 -> String.format("%.1f GB", gb)
            mb >= 1 -> String.format("%.0f MB", mb)
            else -> "${bytes / 1024} KB"
        }
    }
}
