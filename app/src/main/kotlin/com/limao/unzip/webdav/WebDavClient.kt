package com.limao.unzip.webdav

import android.util.Base64
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale

/** 极简 WebDAV 客户端（Basic Auth）：PROPFIND 列目录 / GET 下载。 */
class WebDavClient(private val base: String, private val user: String?, private val pass: String?) {

    data class DavEntry(val href: String, val name: String, val isDir: Boolean, val size: Long, val mtime: Long)

    private fun open(url: String, method: String, depth: Int? = null): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) {
            val auth = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $auth")
        }
        if (depth != null) conn.setRequestProperty("Depth", depth.toString())
        return conn
    }

    /** 列目录：PROPFIND Depth 1。path 形如 "/dir/"（以 / 结尾）。 */
    fun list(path: String): List<DavEntry> {
        val url = joinUrl(base, path)
        val conn = open(url, "PROPFIND", depth = 1)
        conn.setRequestProperty("Content-Type", "application/xml")
        conn.doOutput = true
        conn.outputStream.use {
            it.write("<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>".toByteArray())
        }
        val code = conn.responseCode
        if (code != 207) throw RuntimeException("HTTP $code")
        val body = conn.inputStream.readBytes().toString(Charsets.UTF_8)
        val baseTail = base.trimEnd('/').substringAfterLast('/')
        return parseMultistatus(body).filter { e ->
            e.name != baseTail // 排除目录自身
        }
    }

    /** 下载到本地文件，返回字节数。 */
    fun download(remotePath: String, out: File): Long {
        val conn = open(joinUrl(base, remotePath), "GET")
        if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
        var total = 0L
        conn.inputStream.use { ins ->
            FileOutputStream(out).use { fos ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    fos.write(buf, 0, n)
                    total += n
                }
            }
        }
        return total
    }

    private fun parseMultistatus(xml: String): List<DavEntry> {
        val out = ArrayList<DavEntry>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())
        val rfc1123 = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        var href = ""
        var size = 0L
        var mtime = 0L
        var isDir = false
        var tag = ""
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase()
                    when (name) {
                        "response" -> { href = ""; size = 0; mtime = 0; isDir = false }
                        "getcontentlength" -> tag = "len"
                        "getlastmodified" -> tag = "mtime"
                        "resourcetype" -> tag = "type"
                        "collection" -> if (tag == "type") isDir = true
                    }
                }
                XmlPullParser.TEXT -> when (tag) {
                    "len" -> size = parser.text?.toLongOrNull() ?: 0
                    "mtime" -> mtime = runCatching { rfc1123.parse(parser.text.trim()).time }.getOrDefault(0L)
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase()
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

    private fun joinUrl(base: String, path: String): String {
        val b = base.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return b + p
    }
}
