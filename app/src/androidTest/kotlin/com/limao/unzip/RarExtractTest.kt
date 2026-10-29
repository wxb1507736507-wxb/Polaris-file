package com.limao.unzip

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limao.unzip.engine.api.ArchiveFormat
import com.limao.unzip.engine.api.ExtractOptions
import com.limao.unzip.engine.api.ExtractRequest
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.io.AndroidFdInput
import com.limao.unzip.io.AndroidFileSink
import com.limao.unzip.io.RoutingExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * RAR 解压端到端（instrumented，无需解锁屏幕）：
 *  - 手工构造的 RAR4(STORE)（tools/GenRar4.java 生成，经官方 UnRAR 7.23 校验）
 *  - 官方 rar 7.23 生成的 RAR5（-m5，含中文名与大文件）
 *  - RAR5 加密（-p123456）
 *  - rarlab 官网真实样例（RAR4 + 注释头）
 */
@RunWith(AndroidJUnit4::class)
class RarExtractTest {

    @Before
    fun grantAllFilesAccess() {
        // 读取 /sdcard/Download 需要 MANAGE_EXTERNAL_STORAGE（appop，运行时权限之外）
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "appops set com.limao.unzip MANAGE_EXTERNAL_STORAGE allow"
        ).close()
    }

    private fun openRar(name: String): AndroidFdInput {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val rar = listOf(
            File("/sdcard/Download/$name"),
            File(ctx.getExternalFilesDir(null), name),
        ).firstOrNull { it.isFile } ?: throw AssertionError("测试样例未找到: $name")
        return AndroidFdInput(
            ParcelFileDescriptor.open(rar, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
        )
    }

    private fun outDir(name: String): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val d = File(ctx.getExternalFilesDir(null), name)
        d.deleteRecursively()
        return d
    }

    private suspend fun extract(name: String, out: File, password: String? = null): ExtractResult.Completed {
        val result = RoutingExtractor().extract(
            ExtractRequest(
                source = openRar(name),
                destination = AndroidFileSink(out),
                options = ExtractOptions(password = password),
            )
        )
        return result as? ExtractResult.Completed ?: throw AssertionError("$name 解压未完成: $result")
    }

    @Test
    fun rar4ProbeRecognizesFormat() = runBlocking {
        assertEquals(ArchiveFormat.RAR, RoutingExtractor().probe(openRar("polaris_test.rar")).format)
    }

    @Test
    fun rar4ExtractWritesCorrectContent() = runBlocking {
        val out = outDir("rar4_out")
        assertEquals(2L, extract("polaris_test.rar", out).entriesDone)
        assertEquals("Polaris RAR4 extraction OK\n", File(out, "hello.txt").readText())
        assertEquals("nested 42\n", File(out, "sub/nested.txt").readText())
    }

    @Test
    fun rar5ExtractWithChineseNameAndBigFile() = runBlocking {
        val out = outDir("rar5_out")
        val info = RoutingExtractor().probe(openRar("polaris_rar5.rar"))
        assertEquals(ArchiveFormat.RAR, info.format)
        extract("polaris_rar5.rar", out)
        assertEquals("Polaris RAR extraction OK\n", File(out, "hello.txt").readText())
        assertEquals("nested 42\n", File(out, "sub/nested.txt").readText())
        assertTrue("中文文件名应保留", File(out, "中文.txt").isFile)
        assertEquals("中文名内容", "中文文件名测试\n", File(out, "中文.txt").readText())
        assertEquals("大文件字节数", 80000L, File(out, "big.txt").length())
    }

    @Test
    fun rar5EncryptedWithPassword() = runBlocking {
        val out = outDir("rar5p_out")
        assertEquals(3L, extract("polaris_rar5p.rar", out, password = "123456").entriesDone)
        assertEquals("nested 42\n", File(out, "sub/nested.txt").readText())
    }

    @Test
    fun rar5WrongPasswordFailsWithWrongPassword() = runBlocking {
        val out = outDir("rar5p_bad_out")
        val result = RoutingExtractor().extract(
            ExtractRequest(
                source = openRar("polaris_rar5p.rar"),
                destination = AndroidFileSink(out),
                options = ExtractOptions(password = "wrong-pass"),
            )
        )
        val failed = result as? ExtractResult.Failed ?: throw AssertionError("错误密码应失败: $result")
        assertEquals("错误密码应报 WRONG_PASSWORD", com.limao.unzip.engine.error.ErrorId.WRONG_PASSWORD, failed.error.id)
        assertEquals(0L, failed.entriesDone)
    }

    @Test
    fun zipCryptoWithPassword() = runBlocking {
        val out = outDir("zipc_out")
        assertEquals(3L, extract("polaris_zipcrypto.zip", out, password = "123456").entriesDone)
        assertEquals("nested 42\n", File(out, "sub/nested.txt").readText())
    }

    @Test
    fun rarlabRealWorldSample() = runBlocking {
        val out = outDir("rarlab_out")
        val r = extract("rarlab_sample.rar", out)
        assertTrue("条目数应>0，实际 ${r.entriesDone}", r.entriesDone > 0)
        assertTrue("UnRAR SP.exe 应存在", File(out, "UnRAR SP.exe").isFile)
    }
}
