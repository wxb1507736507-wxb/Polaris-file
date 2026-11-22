package com.limao.unzip.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 基于 ExoPlayer + SurfaceTexture(GL 消费) 的精确视频抽帧器：
 *
 * MTK 等厂商解码器输出私有像素格式时，ImageReader(RGBA) 因格式协商失败拿不到帧；
 * GL 消费端（SurfaceTexture）接受任意硬件格式 —— updateTexImage 上传纹理后
 * 经 FBO 渲染 + glReadPixels 读出 RGB，任何设备都能出帧。
 *
 * ExoPlayer 单线程规则：Player 建在主线程，所有操作经主线程 Handler 派发；
 * GL/EGL 与 SurfaceTexture 属于本（后台）线程的独立上下文。
 *
 * frameAt(tsUs) seek EXACT + onRenderedFirstFrame/帧可用 回调同步，用完 [release]。
 */
class VideoFrameExtractor(
    context: Context,
    uri: Uri,
    private val outW: Int,
    private val outH: Int,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Object()

    @Volatile
    private var frameDelivered = false
    private var initError: Throwable? = null

    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var st: SurfaceTexture? = null

    // EGL 资源（本线程私有）
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var pbuffer: EGLSurface? = null
    private var texId = 0
    private var prog = 0
    private var aPosLoc = 0
    private var uTexMatLoc = 0
    private var fbo = 0
    private var fboTex = 0
    private val texMat = FloatArray(16)

    private val posBuf = java.nio.ByteBuffer.allocateDirect(8 * 4).order(java.nio.ByteOrder.nativeOrder()).apply {
        putFloat(-1f); putFloat(-1f); putFloat(1f); putFloat(-1f)
        putFloat(-1f); putFloat(1f); putFloat(1f); putFloat(1f)
        rewind()
    }

    init {
        try {
            setupEgl()
            createProgram()
            // SurfaceTexture 挂在本线程的 GL 上下文
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            texId = textures[0]
            val sTexture = SurfaceTexture(texId)
            sTexture.setOnFrameAvailableListener({
                synchronized(lock) {
                    frameDelivered = true
                    lock.notifyAll()
                }
            }, mainHandler)
            st = sTexture
            surface = Surface(sTexture)
        } catch (t: Throwable) {
            initError = t
        }

        val ready = CountDownLatch(1)
        mainHandler.post {
            try {
                if (initError != null) throw initError!!
                val p = ExoPlayer.Builder(context)
                    .setRenderersFactory(
                        DefaultRenderersFactory(context)
                            .setEnableDecoderFallback(true)
                            .setAllowedVideoJoiningTimeMs(0),
                    )
                    .build()
                p.setVideoSurface(surface)
                p.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                p.setSeekParameters(SeekParameters.EXACT)
                p.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        synchronized(lock) {
                            frameDelivered = true
                            lock.notifyAll()
                        }
                    }
                })
                p.setMediaItem(MediaItem.fromUri(uri))
                p.prepare()
                p.playWhenReady = false
                player = p
            } catch (t: Throwable) {
                initError = t
            } finally {
                ready.countDown()
            }
        }
        if (!ready.await(8, TimeUnit.SECONDS)) {
            throw IllegalStateException("video extractor init timeout")
        }
        initError?.let { throw IllegalStateException("video extractor init failed", it) }
    }

    // ---------- EGL / GL ----------

    private fun setupEgl() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("no EGL display")
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw IllegalStateException("eglInitialize failed")
        val cfgAttr = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, cfgAttr, 0, cfgs, 0, 1, num, 0) || num[0] == 0) {
            throw IllegalStateException("no EGL config")
        }
        val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val ctx = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
        if (ctx == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("no EGL context")
        val pbAttr = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        val pb = EGL14.eglCreatePbufferSurface(display, cfgs[0], pbAttr, 0)
        if (pb == EGL14.EGL_NO_SURFACE) throw IllegalStateException("no pbuffer")
        if (!EGL14.eglMakeCurrent(display, pb, pb, ctx)) throw IllegalStateException("makeCurrent failed")
        eglDisplay = display; eglContext = ctx; pbuffer = pb
    }

    private fun createProgram() {
        val vs = """
            attribute vec2 aPos;
            varying vec2 vUV;
            uniform mat4 uTexMat;
            void main() {
                vec2 uv = (aPos + 1.0) / 2.0;
                vUV = (uTexMat * vec4(uv, 0.0, 1.0)).xy;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUV;
            uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vUV); }
        """.trimIndent()

        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("shader: ${GLES20.glGetShaderInfoLog(s)}")
            return s
        }
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("link: ${GLES20.glGetProgramInfoLog(prog)}")
        GLES20.glUseProgram(prog)
        aPosLoc = GLES20.glGetAttribLocation(prog, "aPos")
        uTexMatLoc = GLES20.glGetUniformLocation(prog, "uTexMat")
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)

        // FBO 尺寸 = 输出尺寸
        val fb = IntArray(1); val tex = IntArray(1)
        GLES20.glGenFramebuffers(1, fb, 0)
        GLES20.glGenTextures(1, tex, 0)
        fbo = fb[0]; fboTex = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, outW, outH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /** 抽取指定时间戳的帧（微秒）。返回 ARGB 位图或 null。可在后台线程调用。 */
    fun frameAt(tsUs: Long): Bitmap? {
        synchronized(lock) { frameDelivered = false }
        val seekLatch = CountDownLatch(1)
        mainHandler.post {
            try {
                player?.seekTo(0, tsUs / 1000)
            } finally {
                seekLatch.countDown()
            }
        }
        if (!seekLatch.await(2, TimeUnit.SECONDS)) return null
        val delivered = synchronized(lock) {
            lock.wait(3000)
            frameDelivered
        }
        if (!delivered) return null
        return readFrame()
    }

    /** updateTexImage → FBO 渲染 → 读回像素（本线程 GL 上下文） */
    private fun readFrame(): Bitmap? = try {
        val sTexture = st ?: return null
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        sTexture.updateTexImage()
        sTexture.getTransformMatrix(texMat)
        GLES20.glUniformMatrix4fv(uTexMatLoc, 1, false, texMat, 0)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glFinish()
        // 读回（GL 是 Y 轴向上，翻转行序）
        val rgba = java.nio.ByteBuffer.allocateDirect(outW * outH * 4).order(java.nio.ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, outW, outH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        rgba.rewind()
        val rowBytes = outW * 4
        val flipped = java.nio.ByteBuffer.allocateDirect(outW * outH * 4).order(java.nio.ByteOrder.nativeOrder())
        for (y in outH - 1 downTo 0) {
            rgba.position(y * rowBytes)
            val row = ByteArray(rowBytes)
            rgba.get(row)
            flipped.put(row)
        }
        flipped.rewind()
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(flipped)
        bmp
    } catch (t: Throwable) {
        null
    }

    fun release() {
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                player?.release()
            } finally {
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        player = null
        runCatching { surface?.release() }
        runCatching { st?.release() }
        runCatching {
            if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, pbuffer)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        }
        eglDisplay = null; eglContext = null; pbuffer = null
    }
}
