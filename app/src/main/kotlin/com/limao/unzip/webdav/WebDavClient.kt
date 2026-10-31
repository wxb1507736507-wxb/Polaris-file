package com.limao.unzip.webdav

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/** WebDAV 客户端（OkHttp，支持任意 HTTP 方法）：PROPFIND / GET / PUT / DELETE / MKCOL。 */
class WebDavClient(private val base: String, private val user: String?, private val pass: String?) {

    data class DavEntry(val href: String, val name: String, val isDir: Boolean, val size: Long, val mtime: Long)

    private val xmlType = "application/xml".toMediaType()

    companion object {
        // 全局共享连接池/HTTP2 复用：多次浏览与并发请求不重复握手
        private val sharedHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
            .build()
    }

    private val http: OkHttpClient = sharedHttp

    private fun request(url: String, method: String, body: ByteArray? = null): okhttp3.Response {
        val b = okhttp3.Request.Builder().url(url)
        if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) {
            val auth = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
            b.header("Authorization", "Basic $auth")
        }
        if (body != null) {
            b.method(method, RequestBody.create(xmlType, body))
        } else {
            b.method(method, null)
        }
        return http.newCall(b.build()).execute()
    }

    private fun joinUrl(path: String): String {
        val b = base.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return b + p
    }

    /** 列目录：PROPFIND Depth 1。 */
    fun list(path: String): List<DavEntry> {
        val propBody = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>".toByteArray()
        request(joinUrl(path), "PROPFIND", propBody).use { resp ->
            if (resp.code != 207) throw RuntimeException("HTTP ${resp.code}")
            val body = resp.body!!.string()
            android.util.Log.d("WebDavRaw", "body bytes=" + body.length + " head=" + body.take(100))
            val baseTail = base.trimEnd('/').substringAfterLast('/')
            val parsed = parseMultistatus(body)
            android.util.Log.d("WebDavRaw", "parsed entries=" + parsed.size)
            return parsed.filter { it.name != baseTail }
        }
    }

    /** 下载到本地文件，返回字节数。onProgress(bytesDone, totalBytes)（total 未知时为 -1）。 */
    fun download(remotePath: String, out: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): Long {
        request(joinUrl(remotePath), "GET", null).use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val contentLength = resp.body?.contentLength() ?: -1L
            var total = 0L
            var lastReport = 0L
            resp.body!!.byteStream().use { ins ->
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        total += n
                        // 进度回调 200ms 节流，避免跨线程刷屏
                        if (total - lastReport >= 200 * 1024 || (contentLength > 0 && total >= contentLength)) {
                            lastReport = total
                            onProgress(total, contentLength)
                        }
                    }
                }
            }
            onProgress(total, contentLength)
            return total
        }
    }

    /** 上传本地文件（PUT），返回字节数。 */
    fun upload(local: File, remotePath: String): Long {
        val bytes = local.readBytes()
        request(joinUrl(remotePath), "PUT", bytes).use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
        }
        return bytes.size.toLong()
    }

    /** 删除远程文件/目录（DELETE）。 */
    fun delete(remotePath: String) {
        request(joinUrl(remotePath), "DELETE", null).use { resp ->
            val code = resp.code
            if (code !in 200..299 && code != 404) throw RuntimeException("HTTP $code")
        }
    }

    /** 新建远程目录（MKCOL）。 */
    fun mkcol(remotePath: String) {
        request(joinUrl(remotePath), "MKCOL", null).use { resp ->
            val code = resp.code
            if (code !in 200..299 && code != 405) throw RuntimeException("HTTP $code")
        }
    }

    private fun parseMultistatus(xml: String): List<DavEntry> {
        val out = ArrayList<DavEntry>()
        val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser().also { it.setInput(xml.reader()) }
        val rfc1123 = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        var href = ""
        var size = 0L
        var mtime = 0L
        var isDir = false
        var tag = ""
        var ev = parser.eventType
        while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (ev) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    val name = parser.name.substringAfterLast(':').lowercase()
                    when (name) {
                        "response" -> { href = ""; size = 0; mtime = 0; isDir = false }
                        "href" -> tag = "href"
                        "getcontentlength" -> tag = "len"
                        "getlastmodified" -> tag = "mtime"
                        "resourcetype" -> tag = "type"
                        "collection" -> if (tag == "type") isDir = true
                    }
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> when (tag) {
                    "href" -> href = parser.text ?: ""
                    "len" -> size = parser.text?.toLongOrNull() ?: 0
                    "mtime" -> mtime = runCatching { rfc1123.parse(parser.text.trim()).time }.getOrDefault(0L)
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    val name = parser.name.substringAfterLast(':').lowercase()
                    if (name in setOf("getcontentlength", "getlastmodified", "resourcetype")) tag = ""
                    if (name == "response") {
                        val decoded = URLDecoder.decode(href, "UTF-8")
                        val namePart = decoded.trimEnd('/').substringAfterLast('/')
                        if (namePart.isNotEmpty()) out.add(DavEntry(decoded, namePart, isDir, size, mtime))
                    }
                }
            }
            ev = parser.next()
        }
        return out
    }
}
