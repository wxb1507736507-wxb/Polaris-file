package com.limao.unzip.engine.error

import com.limao.unzip.engine.api.ArchiveFormat
import com.limao.unzip.engine.api.EntryErrorPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 异常体系：用户文案模板与结构化映射（§4）。 */
class ErrorCatalogTest {

    @Test
    fun `space message formats need and available`() {
        val e = ErrorCatalog.notEnoughSpace(needBytes = (2.13 * 1024 * 1024 * 1024).toLong(), availBytes = 800L shl 20)
        assertEquals(ErrorId.NOT_ENOUGH_SPACE, e.id)
        assertTrue(e.message.contains("2.1 GB"), e.message)
        assertTrue(e.message.contains("800 MB"), e.message)
        assertTrue(e.actions.contains(ErrorAction.PICK_NEW_DEST))
    }

    @Test
    fun `crc message names the file and policy outcome`() {
        val skip = ErrorCatalog.crcMismatch("Photos/IMG_1.nef", EntryErrorPolicy.SKIP_CONTINUE)
        assertTrue(skip.message.contains("Photos/IMG_1.nef"))
        assertTrue(skip.message.contains("跳过"))
        val abort = ErrorCatalog.crcMismatch("a", EntryErrorPolicy.ABORT)
        assertTrue(!abort.message.contains("跳过"))
    }

    @Test
    fun `password attempt copy carries attempt count`() {
        val e = ErrorCatalog.wrongPassword(2)
        assertTrue(e.message.contains("第 2/${ErrorCatalog.MAX_PASSWORD_ATTEMPTS} 次"))
        assertEquals(listOf(ErrorAction.RETRY_PASSWORD), e.actions)
    }

    @Test
    fun `formatBytes uses GB then MB then KB`() {
        assertEquals("1.5 GB", ErrorCatalog.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("800 MB", ErrorCatalog.formatBytes(800L shl 20))
        assertEquals("512 KB", ErrorCatalog.formatBytes(512 * 1024))
    }

    @Test
    fun `engine code to user error mapping`() {
        assertEquals(ErrorId.ARCHIVE_CORRUPT, ErrorCatalog.archiveCorrupt("x").id)
        assertEquals(ErrorId.UNSUPPORTED_ENCRYPTED, ErrorCatalog.unsupportedEncrypted(ArchiveFormat.ZIP).id)
        assertEquals(ErrorId.PROVIDER_IO, ErrorCatalog.providerIo("EIO").id)
    }
}
