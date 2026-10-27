package com.limao.unzip.io

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.limao.unzip.engine.api.ArchiveOutput
import com.limao.unzip.engine.api.EntryCursor
import com.limao.unzip.engine.api.CompressSource
import com.limao.unzip.engine.api.FdCompressSource
import com.limao.unzip.engine.util.PathGuard
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** SAF 树压缩源：DocumentsContract 递归枚举 + fd/流双通道。 */
class SafTreeCompressSource(
    private val context: Context,
    private val treeUri: Uri,
    private val maxEntries: Int = 20_000,
) : FdCompressSource {

    private data class Doc(val relPath: String, val size: Long, val mtime: Long, val isDir: Boolean, val uri: Uri)

    private val resolver = context.contentResolver
    private var docs: List<Doc> = emptyList()

    override fun scan(sink: (EntryCursor) -> Unit) {
        val list = ArrayList<Doc>(64)
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        fun walk(parentUri: Uri, relPrefix: String) {
            if (list.size >= maxEntries) return
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parentUri))
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext() && list.size < maxEntries) {
                    val docId = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = c.getLong(3)
                    val mtime = c.getLong(4)
                    val rel = (if (relPrefix.isEmpty()) name else "$relPrefix/$name")
                    val safe = PathGuard.sanitize(rel) ?: continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    list.add(Doc(safe, size, mtime, isDir, uri))
                    if (isDir) walk(uri, safe)
                }
            }
        }
        walk(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId), "")
        docs = list
        val cursor = EntryCursor()
        docs.forEachIndexed { i, d ->
            cursor.assign(i.toLong(), d.relPath, d.size, -1, d.isDir, false, d.mtime)
            sink(cursor)
        }
    }

    override fun openStream(index: Long): InputStream? {
        val d = docs.getOrNull(index.toInt()) ?: return null
        if (d.isDir) return null
        return resolver.openInputStream(d.uri)
    }

    override fun openFd(index: Long): Int? {
        val d = docs.getOrNull(index.toInt()) ?: return null
        if (d.isDir) return null
        return resolver.openFileDescriptor(d.uri, "r")?.detachFd()
    }

    override fun close() {}
}

/** 本地文件归档输出（fd 通道：ParcelFileDescriptor detach 转移，native 只写不关）。 */
class AndroidFileArchiveOutput(private val target: File) : ArchiveOutput {

    private val part = File(target.parentFile ?: File("."), target.name + ".part")
    private var state = 0
    private var fd: Int = -1

    override fun begin(): OutputStream {
        check(state == 0)
        state = 1
        part.parentFile?.mkdirs()
        return FileOutputStream(part)
    }

    override fun beginFd(): Int {
        check(state == 0)
        state = 1
        part.parentFile?.mkdirs()
        fd = ParcelFileDescriptor.open(
            part,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        ).detachFd()
        return fd
    }

    override fun commit() {
        if (state == 2) return
        state = 2
        if (target.exists()) target.delete()
        check(part.renameTo(target)) { "rename 失败: $target" }
    }

    override fun discard() {
        state = 2
        part.delete()
    }

    override fun close() {
        if (state == 1) discard()
    }
}

/** SAF 归档输出：createDocument(.part) → fd 直写 → rename 收尾。 */
class SafArchiveOutput(
    private val context: Context,
    private val treeUri: Uri,
    private val archiveName: String,
) : ArchiveOutput {

    private val resolver = context.contentResolver
    private var partUri: Uri? = null
    private var state = 0

    private fun ensurePart(): Uri {
        if (partUri != null) return partUri!!
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        val existing = resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            var found: Uri? = null
            while (c.moveToNext()) {
                if (c.getString(1) == archiveName) {
                    found = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                    break
                }
            }
            found
        }
        existing?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
        val uri = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", archiveName + ".part")
            ?: throw IllegalStateException("createDocument 失败: $archiveName")
        partUri = uri
        state = 1
        return uri
    }

    override fun begin(): OutputStream {
        val pfd = resolver.openFileDescriptor(ensurePart(), "rw") ?: throw IllegalStateException("打开归档失败")
        return FileOutputStream(pfd.fileDescriptor)
    }

    override fun beginFd(): Int {
        val pfd = resolver.openFileDescriptor(ensurePart(), "rw") ?: throw IllegalStateException("打开归档失败")
        return pfd.detachFd()
    }

    override fun commit() {
        if (state == 2) return
        val uri = partUri ?: return
        state = 2
        val renamed = runCatching { DocumentsContract.renameDocument(resolver, uri, archiveName) }.getOrNull()
        if (renamed == null) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw IllegalStateException("归档收尾失败（rename 不支持）")
        }
    }

    override fun discard() {
        state = 2
        partUri?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
    }

    override fun close() {
        if (state == 1) discard()
    }
}

/** 本地目录压缩源（app 侧 fd 通道版，自测用）。 */
class DirFdCompressSource(private val root: File) : FdCompressSource {

    private val delegate = com.limao.unzip.engine.engine.DirCompressSource(root)
    private val metas = ArrayList<Pair<String, File>>(64)

    override fun scan(sink: (EntryCursor) -> Unit) {
        var i = 0L
        val files = LinkedHashMap<String, File>()
        root.walkTopDown().forEach { f ->
            val rel = f.relativeTo(root).invariantSeparatorsPath
            if (rel.isEmpty() || rel == ".") return@forEach
            val safe = PathGuard.sanitize(rel) ?: return@forEach
            files[safe] = f
        }
        val cursor = EntryCursor()
        val sorted = files.keys.sorted()
        metas.clear()
        for (rel in sorted) {
            val f = files.getValue(rel)
            metas.add(rel to f)
            cursor.assign(i, rel, if (f.isFile) f.length() else 0, -1, f.isDirectory, false, f.lastModified())
            sink(cursor)
            i++
        }
    }

    override fun openStream(index: Long): InputStream? {
        val (rel, f) = metas.getOrNull(index.toInt()) ?: return null
        return if (f.isFile) FileInputStream(f) else null
    }

    override fun openFd(index: Long): Int? {
        val (rel, f) = metas.getOrNull(index.toInt()) ?: return null
        if (!f.isFile) return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
    }

    override fun close() {}
}
