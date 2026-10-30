package com.limao.unzip

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withText
import android.widget.EditText
import com.limao.unzip.engine.api.CompressRequest
import com.limao.unzip.engine.api.CreateFormat
import com.limao.unzip.engine.api.ExtractOptions
import com.limao.unzip.engine.api.ExtractRequest
import com.limao.unzip.engine.api.ExtractResult
import com.limao.unzip.io.AndroidFdInput
import com.limao.unzip.io.AndroidFileSink
import com.limao.unzip.io.RoutingCompressor
import com.limao.unzip.io.RoutingExtractor
import com.limao.unzip.files.PathsCompressSource
import com.limao.unzip.files.TextEditorActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 全功能端到端回归（真机/模拟器）：
 *  Office 提取、文本编辑保存（UTF-8/GBK/只读）、压缩→解压回环（ZIP/7z/TAR.GZ）、
 *  样例压缩包解压、PDF 渲染、图片解码、解压性能。
 * 样例由 tools/e2e 预先 adb push 到 /sdcard/Download/e2e。
 */
@RunWith(AndroidJUnit4::class)
class E2eTest {

    private val fixDir get() = File("/sdcard/Download/e2e")
    private val work get() = File(
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!,
        "e2e_work",
    )

    @Before
    fun setup() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "appops set com.limao.unzip MANAGE_EXTERNAL_STORAGE allow"
        ).close()
        work.deleteRecursively()
        work.mkdirs()
    }

    private fun fix(name: String): File {
        val f = File(fixDir, name)
        assertTrue("缺少样例 $name（先 adb push tools/e2e/* /sdcard/Download/e2e/）", f.isFile)
        return f
    }

    // ---------- Office 文本提取 ----------

    @Test
    fun officeDocxExtract() {
        val (text, full) = com.limao.unzip.files.OfficeText.extract(fix("sample.docx"))
        assertTrue(full)
        assertTrue("docx 应含标题，实际: $text", text.contains("Polaris DocX Title"))
        assertTrue("docx 应含中文段落", text.contains("第二段落"))
    }

    @Test
    fun officeXlsxExtract() {
        val (text, full) = com.limao.unzip.files.OfficeText.extract(fix("sample.xlsx"))
        assertTrue(full)
        assertTrue("xlsx 应含表头", text.contains("Product"))
        assertTrue("xlsx 应含行内字符串中文", text.contains("苹果 Apple"))
        assertTrue("xlsx 应含数值", text.contains("42"))
    }

    @Test
    fun officePptxExtract() {
        val (text, full) = com.limao.unzip.files.OfficeText.extract(fix("sample.pptx"))
        assertTrue(full)
        assertTrue("pptx 应含幻灯片文字", text.contains("Polaris Slide One"))
        assertTrue("pptx 应含中文", text.contains("幻灯片中文"))
    }

    // ---------- 文本编辑器（打开 / 修改 / 保存 / 编码回写） ----------

    private fun copyToWork(name: String, rename: String? = null): File {
        val dst = File(work, rename ?: name)
        fix(name).copyTo(dst, overwrite = true)
        return dst
    }

    private fun waitFileContent(f: File, expected: String, timeoutMs: Long = 5000): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (f.isFile && runCatching { f.readText() }.getOrNull() == expected) return true
            SystemClock.sleep(100)
        }
        return false
    }

    @Test
    fun editorOpenAndSaveUtf8() {
        val f = copyToWork("note_utf8.txt")
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, TextEditorActivity::class.java)
            .putExtra(TextEditorActivity.EXTRA_PATH, f.absolutePath)
        ActivityScenario.launch<TextEditorActivity>(intent).use {
            // 等内容加载完成
            onView(isAssignableFrom(EditText::class.java)).check(matches(withText(org.hamcrest.Matchers.containsString("你好 Polaris"))))
            // 修改并保存
            onView(isAssignableFrom(EditText::class.java)).perform(replaceText("modified 内容 123"))
            onView(withText(R.string.editor_save)).perform(click())
            assertTrue("保存后文件内容应为新文本", waitFileContent(f, "modified 内容 123"))
        }
    }

    @Test
    fun editorGbkDetectAndSaveBackGbk() {
        val f = copyToWork("note_gbk.txt")
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, TextEditorActivity::class.java)
            .putExtra(TextEditorActivity.EXTRA_PATH, f.absolutePath)
        ActivityScenario.launch<TextEditorActivity>(intent).use {
            onView(isAssignableFrom(EditText::class.java)).check(matches(withText(org.hamcrest.Matchers.containsString("GBK编码中文内容"))))
            onView(isAssignableFrom(EditText::class.java)).perform(replaceText("GBK编码中文内容\n已修改保存"))
            onView(withText(R.string.editor_save)).perform(click())
            // 保存后文件应仍为 GBK 编码
            val end = SystemClock.uptimeMillis() + 5000
            var ok = false
            while (SystemClock.uptimeMillis() < end && !ok) {
                ok = runCatching {
                    String(f.readBytes(), charset("GBK")) == "GBK编码中文内容\n已修改保存"
                }.getOrDefault(false)
                SystemClock.sleep(100)
            }
            assertTrue("保存后应按 GBK 回写", ok)
        }
    }

    @Test
    fun editorBigFileReadonly() {
        val f = copyToWork("big.txt")
        val before = f.readText().substring(0, 100)
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, TextEditorActivity::class.java)
            .putExtra(TextEditorActivity.EXTRA_PATH, f.absolutePath)
        ActivityScenario.launch<TextEditorActivity>(intent).use {
            onView(isAssignableFrom(EditText::class.java)).check(matches(withText(org.hamcrest.Matchers.containsString("line 299"))))
            // 只读：文本不可更改（isEnabled=false）
            onView(isAssignableFrom(EditText::class.java)).check { view, _ ->
                assertTrue("大文件应为只读", !view.isEnabled)
            }
        }
        assertEquals("只读文件不应被改动", before, f.readText().substring(0, 100))
    }

    // ---------- 压缩 → 解压回环 ----------

    private fun roundTrip(format: CreateFormat, outName: String) {
        val srcDir = File(work, "rt_src").apply {
            mkdirs(); File(this, "hello.txt").writeText("round trip 你好\n")
            File(this, "sub").mkdirs(); File(this, "sub/note.txt").writeText("nested 42\n")
        }
        val out = File(work, outName)
        val started = System.nanoTime()
        val result = runBlocking {
            RoutingCompressor().compress(
                CompressRequest(
                    source = PathsCompressSource(listOf(srcDir)),
                    output = com.limao.unzip.io.AndroidFileArchiveOutput(out),
                    format = format,
                )
            )
        }
        val compressMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("$format 压缩失败: $result", result is ExtractResult.Completed)
        assertTrue("$format 产物应存在", out.isFile && out.length() > 0)

        val dst = File(work, "rt_out_$outName")
        val ext = runBlocking {
            val input = AndroidFdInput(
                ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
            )
            RoutingExtractor().extract(ExtractRequest(source = input, destination = AndroidFileSink(dst), options = ExtractOptions()))
        }
        assertTrue("$format 解压未完成: $ext", ext is ExtractResult.Completed)
        assertEquals("round trip 你好\n", File(dst, "rt_src/hello.txt").readText())
        assertEquals("nested 42\n", File(dst, "rt_src/sub/note.txt").readText())
        android.util.Log.e("E2E", "$format compress=${compressMs}ms")
    }

    @Test
    fun roundTripZip() = roundTrip(CreateFormat.ZIP, "rt.zip")

    @Test
    fun roundTrip7z() = roundTrip(CreateFormat.SEVEN_Z, "rt.7z")

    @Test
    fun roundTripTarGz() = roundTrip(CreateFormat.TAR_GZ, "rt.tar.gz")

    // ---------- 样例压缩包解压 ----------

    private fun extractFixture(name: String, checker: (File) -> Unit) {
        val out = File(work, "x_$name")
        val started = System.nanoTime()
        val result = runBlocking {
            val input = AndroidFdInput(
                ParcelFileDescriptor.open(fix(name), ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
            )
            RoutingExtractor().extract(ExtractRequest(source = input, destination = AndroidFileSink(out), options = ExtractOptions()))
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("$name 解压未完成: $result", result is ExtractResult.Completed)
        checker(out)
        android.util.Log.e("E2E", "$name extract=${ms}ms")
    }

    @Test
    fun extractProjZip() = extractFixture("proj.zip") { out ->
        assertEquals("archive zip readme 中文", File(out, "proj/readme.txt").readText())
        assertEquals(256 * 40L, File(out, "proj/data.bin").length())
    }

    @Test
    fun extractProjTarGz() = extractFixture("proj.tar.gz") { out ->
        assertEquals("hello tar gz\n", File(out, "proj_dir/hello.txt").readText())
        assertEquals("nested tar content\n", File(out, "proj_dir/sub/note.txt").readText())
    }

    @Test
    fun extractProj7z() = extractFixture("proj.7z") { out ->
        assertEquals("hello tar gz\n", File(out, "proj_dir/hello.txt").readText())
        assertEquals("nested tar content\n", File(out, "proj_dir/sub/note.txt").readText())
    }

    // ---------- PDF / 图片 ----------

    @Test
    fun pdfRenders() {
        ParcelFileDescriptor.open(fix("sample.pdf"), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            android.graphics.pdf.PdfRenderer(pfd).use { r ->
                assertEquals(1, r.pageCount)
                r.openPage(0).use { page ->
                    val bmp = android.graphics.Bitmap.createBitmap(612, 792, android.graphics.Bitmap.Config.ARGB_8888)
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    assertTrue("PDF 页应有非白像素（渲染了内容）", hasNonWhite(bmp))
                }
            }
        }
    }

    private fun hasNonWhite(bmp: android.graphics.Bitmap): Boolean {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return px.any { it != -1 }
    }

    @Test
    fun imagesDecode() {
        val png = BitmapFactory.decodeFile(fix("img_red.png").absolutePath)
        assertNotNull("PNG 应可解码", png)
        assertEquals(64, png.width)
        // JPEG 走运行时生成（fixture 手工字节流不保证合法）：Bitmap→JPEG→解码回环
        val jpgFile = File(work, "rt.jpg")
        android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
            .compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, jpgFile.outputStream())
        val decoded = BitmapFactory.decodeFile(jpgFile.absolutePath)
        assertNotNull("JPEG 应可解码", decoded)
        assertEquals(48, decoded.width)
    }

    @Test
    fun audioPlayerPlays() {
        val intent = android.content.Intent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            com.limao.unzip.files.PlayerActivity::class.java,
        ).putExtra(com.limao.unzip.files.PlayerActivity.EXTRA_PATH, fix("tone.wav").absolutePath)
        ActivityScenario.launch<com.limao.unzip.files.PlayerActivity>(intent).use { scenario ->
            // prepare 后应自动播放（异步，最长等 5s）
            val end = SystemClock.uptimeMillis() + 5000
            var playing = false
            while (SystemClock.uptimeMillis() < end && !playing) {
                scenario.onActivity { playing = it.isPlayingNow() }
                if (!playing) SystemClock.sleep(200)
            }
            assertTrue("音频应处于播放状态", playing)
        }
    }
}
