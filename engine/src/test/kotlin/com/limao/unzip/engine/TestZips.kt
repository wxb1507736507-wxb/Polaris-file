package com.limao.unzip.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 测试用 ZIP 构造器：标准 ZipOutputStream 路径 + 手工字节级路径（损坏/非法字段注入）。 */
object TestZips {

    val FIXED_TIME: Long = LocalDateTime.of(2026, 1, 15, 12, 0, 0)
        .toInstant(ZoneOffset.UTC).toEpochMilli()

    fun makeZip(
        file: File,
        entries: List<Pair<String, ByteArray>>,
        method: Int = ZipOutputStream.DEFLATED,
        charset: Charset = Charsets.UTF_8,
    ) {
        ZipOutputStream(FileOutputStream(file).buffered(), charset).use { zos ->
            zos.setMethod(method)
            for ((name, data) in entries) {
                val entry = ZipEntry(name)
                entry.time = FIXED_TIME
                if (method == ZipOutputStream.STORED) {
                    entry.size = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                }
                zos.putNextEntry(entry)
                if (data.isNotEmpty()) zos.write(data)
                zos.closeEntry()
            }
        }
    }

    /** 手工组装单条目 zip，可注入非法 method/flags。 */
    fun writeRawZip(file: File, name: String, data: ByteArray, method: Int, flags: Int = 0) {
        val crc = CRC32().apply { update(data) }.value
        val nb = name.toByteArray(Charsets.UTF_8)
        val dosDate = ((2026 - 1980) shl 9) or (1 shl 5) or 1

        val out = ByteArrayOutputStream()
        // 本地文件头
        out.le32(0x04034b50); out.le16(20); out.le16(flags); out.le16(method)
        out.le16(0); out.le16(dosDate); out.le32(crc.toInt())
        out.le32(data.size); out.le32(data.size)
        out.le16(nb.size); out.le16(0); out.write(nb); out.write(data)
        // 中央目录头
        val cdOffset = out.size()
        out.le32(0x02014b50); out.le16(20); out.le16(20); out.le16(flags); out.le16(method)
        out.le16(0); out.le16(dosDate); out.le32(crc.toInt())
        out.le32(data.size); out.le32(data.size)
        out.le16(nb.size); out.le16(0); out.le16(0); out.le16(0); out.le16(0)
        out.le32(0); out.le32(0)
        out.write(nb)
        val cdSize = out.size() - cdOffset
        // EOCD
        out.le32(0x06054b50); out.le16(0); out.le16(0); out.le16(1); out.le16(1)
        out.le32(cdSize); out.le32(cdOffset); out.le16(0)

        FileOutputStream(file).use { it.write(out.toByteArray()) }
    }

    private fun ByteArrayOutputStream.le16(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.le32(v: Int) {
        le16(v and 0xFFFF); le16((v shr 16) and 0xFFFF)
    }
}
