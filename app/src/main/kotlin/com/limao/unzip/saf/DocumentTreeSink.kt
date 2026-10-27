package com.limao.unzip.saf

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.limao.unzip.engine.api.OutputSink
import com.limao.unzip.engine.api.OverwritePolicy
import com.limao.unzip.engine.error.EngineErrorCode
import com.limao.unzip.engine.error.EngineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException

/**
 * SAF 树输出：每个条目 createDocument(name.part) + openFileDescriptor("rw")，
 * 拿到裸 fd 后全部分块写直通引擎（SAF IPC 只在条目首尾各一次）。
 * commit = renameDocument 收尾；discard = deleteDocument。
 */
class DocumentTreeSink(
    private val context: Context,
    private val treeUri: Uri,
    private val overwritePolicy: OverwritePolicy = OverwritePolicy.REPLACE,
) : OutputSink, com.limao.unzip.engine.api.FdCapableSink {

    private val resolver = context.contentResolver
    private val treeDocId = runCatching {
        DocumentsContract.getTreeDocumentId(treeUri)
    }.getOrNull() ?: throw EngineException(EngineErrorCode.IO, "无效的目标目录 Uri")

    private val parent: Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

    override fun beginEntry(name: String, expectBytes: Long, mtimeMs: Long, isDirectory: Boolean): OutputSink.EntryOutput? {
        // 目录条目：SAF 的 createDocument(mimetype dir) 在部分 provider 不可靠，
        // 文档按需隐式创建父目录，目录条目本身不落盘
        if (isDirectory) return NoopEntry

        val segments = name.split('/')
        val fileName = segments.last()
        val dirs = segments.dropLast(1)
        var dir = parent
        for (d in dirs) {
            dir = findOrCreateDir(dir, d)
        }

        val existing = findChild(dir, fileName)
        if (existing != null) {
            when (overwritePolicy) {
                OverwritePolicy.SKIP -> return null
                OverwritePolicy.FAIL -> throw EngineException(EngineErrorCode.WRITE, "目标已存在: $name")
                OverwritePolicy.REPLACE -> runCatching { DocumentsContract.deleteDocument(resolver, existing) }
            }
        }

        val partUri = DocumentsContract.createDocument(resolver, dir, MIME_BINARY, fileName + PART_SUFFIX)
            ?: throw EngineException(EngineErrorCode.IO, "createDocument 失败: $fileName")
        return DocumentEntryOutput(partUri, fileName)
    }

    private fun findOrCreateDir(dir: Uri, name: String): Uri {
        findChild(dir, name)?.let { return it }
        return DocumentsContract.createDocument(resolver, dir, MIME_DIR, name)
            ?: throw EngineException(EngineErrorCode.WRITE, "无法创建目录: $name")
    }

    private fun findChild(dir: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(dir))
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                }
            }
        }
        return null
    }

    override fun close() {}

    // ---- fd 通道（native 引擎用）：SAF 建 .part 文档 → 裸 fd 直写 → rename 收尾 ----
    override fun beginEntryFd(
        name: String,
        expectBytes: Long,
        mtimeMs: Long,
        isDirectory: Boolean,
    ): com.limao.unzip.engine.api.FdCapableSink.FdEntryOutput? {
        if (isDirectory) {
            // SAF 的 dir 文档创建在部分 provider 不可靠：文件按需隐式建父目录，目录条目不落盘
            return NoopFd
        }
        val segments = name.split('/')
        val fileName = segments.last()
        var dir = parent
        for (d in segments.dropLast(1)) {
            dir = findOrCreateDir(dir, d)
        }
        val existing = findChild(dir, fileName)
        if (existing != null) {
            when (overwritePolicy) {
                OverwritePolicy.SKIP -> return null
                OverwritePolicy.FAIL -> throw EngineException(EngineErrorCode.WRITE, "目标已存在: $name")
                OverwritePolicy.REPLACE -> runCatching { DocumentsContract.deleteDocument(resolver, existing) }
            }
        }
        val partUri = DocumentsContract.createDocument(resolver, dir, MIME_BINARY, fileName + PART_SUFFIX)
            ?: throw EngineException(EngineErrorCode.IO, "createDocument 失败: $fileName")
        return SafFdEntryOutput(partUri, fileName)
    }

    private object NoopFd : com.limao.unzip.engine.api.FdCapableSink.FdEntryOutput {
        override val fd: Int get() = -2
        override fun commitFd() {}
        override fun discardFd() {}
        override fun leavePartFd() {}
        override fun afterFdClosed() {}
    }

    private inner class SafFdEntryOutput(
        private val partUri: Uri,
        private val finalName: String,
    ) : com.limao.unzip.engine.api.FdCapableSink.FdEntryOutput {
        // 所有权转移给 native：fd 由 native close()，本类只负责 SAF 文档收尾
        override val fd: Int = (resolver.openFileDescriptor(partUri, "rw")
            ?: throw EngineException(EngineErrorCode.IO, "openFileDescriptor 失败: $finalName")).detachFd()
        private var finalized = false

        override fun commitFd() {
            finalized = true
            val renamed = runCatching { DocumentsContract.renameDocument(resolver, partUri, finalName) }.getOrNull()
            if (renamed == null) {
                runCatching { DocumentsContract.deleteDocument(resolver, partUri) }
                throw EngineException(EngineErrorCode.IO, "收尾失败（rename 不支持）: $finalName")
            }
        }

        override fun discardFd() {
            finalized = true
            runCatching { DocumentsContract.deleteDocument(resolver, partUri) }
        }

        override fun leavePartFd() { finalized = true }

        override fun afterFdClosed() { finalized = true }
    }

    private object NoopEntry : OutputSink.EntryOutput {
        override fun write(src: ByteArray, offset: Int, length: Int) {}
        override fun commit() {}
        override fun discard() {}
        override fun leaveAsPart() {}
    }

    private inner class DocumentEntryOutput(
        private val partUri: Uri,
        private val finalName: String,
    ) : OutputSink.EntryOutput {
        private val pfd = resolver.openFileDescriptor(partUri, "rw")
            ?: throw EngineException(EngineErrorCode.IO, "openFileDescriptor 失败: $finalName")
        private val out = FileOutputStream(pfd.fileDescriptor)
        private var closed = false

        override fun write(src: ByteArray, offset: Int, length: Int) {
            out.write(src, offset, length)
        }

        override fun commit() {
            closeFd()
            val renamed = runCatching {
                DocumentsContract.renameDocument(resolver, partUri, finalName)
            }.getOrNull()
            if (renamed == null) {
                // provider 不支持 rename：退化为"直写名即最终名"的完整性策略由 CRC 失败即删兜底
                runCatching { DocumentsContract.deleteDocument(resolver, partUri) }
                throw EngineException(EngineErrorCode.IO, "收尾失败（rename 不支持）: $finalName")
            }
        }

        override fun discard() {
            closeFd()
            runCatching { DocumentsContract.deleteDocument(resolver, partUri) }
        }

        override fun leaveAsPart() {
            closeFd()
        }

        private fun closeFd() {
            if (!closed) {
                closed = true
                runCatching { out.close() }
                runCatching { pfd.close() }
            }
        }
    }

    companion object {
        const val PART_SUFFIX = ".part"
        const val MIME_DIR = DocumentsContract.Document.MIME_TYPE_DIR
        const val MIME_BINARY = "application/octet-stream"
    }
}
