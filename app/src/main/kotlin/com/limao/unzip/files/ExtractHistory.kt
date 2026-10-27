package com.limao.unzip.files

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 解压历史：压缩包绝对路径 → { 目标目录, 时间 }。
 * 用于压缩包列表的"已解压"标记，避免重复解压；点击时询问打开已解压目录还是重新解压。
 */
object ExtractHistory {

    private const val PREFS = "extract_history"

    data class Record(val dstDir: String, val time: Long)

    fun record(context: Context, srcPath: String, dstDir: String) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = JSONObject(sp.getString("map", "{}"))
        all.put(srcPath, JSONObject().put("dst", dstDir).put("time", System.currentTimeMillis()))
        sp.edit().putString("map", all.toString()).apply()
    }

    /** 返回记录且目标目录仍存在才认为"已解压"。 */
    fun lookup(context: Context, srcPath: String): Record? {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = runCatching { JSONObject(sp.getString("map", "{}")) }.getOrNull() ?: return null
        val rec = runCatching { all.optJSONObject(srcPath) }.getOrNull() ?: return null
        val dst = rec.optString("dst") ?: return null
        if (dst.isEmpty() || !File(dst).isDirectory) return null
        return Record(dst, rec.optLong("time"))
    }

    /** 全部有效记录（目标目录仍存在），按时间倒序。 */
    fun all(context: Context): List<Triple<String, String, Long>> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = runCatching { JSONObject(sp.getString("map", "{}")) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Triple<String, String, Long>>(16)
        val keys = all.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val rec = runCatching { all.optJSONObject(k) }.getOrNull() ?: continue
            val dst = rec.optString("dst")
            if (dst.isNotEmpty() && File(dst).isDirectory) {
                out.add(Triple(k, dst, rec.optLong("time")))
            }
        }
        return out.sortedByDescending { it.third }
    }
}
