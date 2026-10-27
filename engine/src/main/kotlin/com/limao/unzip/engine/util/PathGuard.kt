package com.limao.unzip.engine.util

/**
 * 条目路径守卫：规范化分隔符、剥离盘符/前导斜杠、拒绝 ".."、空段、控制字符与超长段。
 * 返回 null 表示该条目不安全（Zip Slip 防御），调用方记 warning 并跳过。
 * 文件路径拼接只发生在 native/输出端，Java 侧永远不做 dest+name 字符串运算（F4 规约）。
 */
object PathGuard {
    const val MAX_PATH = 1024
    const val MAX_SEGMENT = 255
    const val MAX_DEPTH = 64

    fun sanitize(raw: String): String? {
        if (raw.isEmpty() || raw.length > MAX_PATH) return null
        if ('\u0000' in raw) return null
        var name = raw.replace('\\', '/')
        // 盘符（C:/...）剥离
        if (name.length >= 2 && name[0] in 'A'..'z' && name[1] == ':') {
            name = name.substring(2)
        }
        val segments = name.split('/')
            .filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return null
        if (segments.size > MAX_DEPTH) return null
        for (segment in segments) {
            if (segment == "..") return null
            if (segment.length > MAX_SEGMENT) return null
            if (segment.any { it.code < 0x20 || it.code == 0x7F }) return null
        }
        return segments.joinToString("/")
    }

    /** 目录条目提示（以 / 结尾的条目名）。 */
    fun looksLikeDirectory(raw: String): Boolean = raw.endsWith("/") || raw.endsWith("\\")
}
