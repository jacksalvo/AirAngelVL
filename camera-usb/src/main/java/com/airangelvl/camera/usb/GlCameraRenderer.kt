package com.airangelvl.camera.usb

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.airangelvl.core.camera.PreviewTarget
import com.airangelvl.core.camera.RenderSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine

/** Owns one GL context. Camera frames are transformed identically for every output. */
internal class GlCameraRenderer(
    private val onFrame: (Long) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val onRecordingFailure: (Throwable) -> Unit
) {
    private val thread = HandlerThread("UsbRender").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    @Volatile private var settings = RenderSettings()
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var recordableConfig = false
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var preview: Window? = null
    private var encoder: Window? = null
    private var input: SurfaceTexture? = null
    private var textureId = 0
    private var program = 0
    private var width = 0
    private var height = 0
    private var hasFrame = false
    private var firstFrame = CompletableDeferred<Unit>()
    private var lastFrameNs = 0L
    private var framePacer = FramePacer(30)
    private val producerMatrix = FloatArray(16)
    private val positions = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val coordinates = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    suspend fun initialize(width: Int, height: Int, target: PreviewTarget): SurfaceTexture = call {
        this.width = width
        this.height = height
        initializeEgl()
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        replacePreview(target)
        SurfaceTexture(textureId).also {
            input = it
            it.setDefaultBufferSize(width, height)
            it.setOnFrameAvailableListener({ renderFrame() }, handler)
        }
    }

    suspend fun awaitFirstFrame() = firstFrame.await()

    fun setSettings(value: RenderSettings) {
        settings = value.normalized()
        handler.post {
            if (hasFrame && !closed.get() && !failed.get()) {
                try { drawPreview(settings) } catch (error: Throwable) {
                    if (failed.compareAndSet(false, true)) onFailure(error)
                }
            }
        }
    }

    suspend fun updatePreview(target: PreviewTarget) = call {
        replacePreview(target)
        if (hasFrame) drawPreview(settings)
    }

    suspend fun outputSize(): Pair<Int, Int> = call {
        encoder?.let { it.width to it.height } ?: RenderGeometry.outputSize(width, height, settings)
    }

    suspend fun attachEncoder(surface: Surface, outputWidth: Int, outputHeight: Int, fps: Int) = call {
        check(encoder == null) { "An encoder is already attached" }
        check(recordableConfig) { "This phone supports preview and photos, but has no compatible video rendering configuration" }
        encoder = createWindow(surface, outputWidth, outputHeight, ownsSurface = false)
        framePacer = FramePacer(fps)
    }

    suspend fun detachEncoder() = call {
        makeCurrent(pbuffer)
        encoder?.let(::destroyWindow)
        encoder = null
    }

    suspend fun capture(): Bitmap = call(onDiscard = { it.recycle() }) {
        check(hasFrame && SystemClock.elapsedRealtimeNanos() - lastFrameNs < STALE_FRAME_NS) {
            "No recent camera frame is available"
        }
        val snapshot = settings
        val size = encoder?.let { it.width to it.height } ?: RenderGeometry.outputSize(width, height, snapshot)
        makeCurrent(pbuffer)
        val textures = IntArray(1)
        val framebuffers = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size.first, size.second,
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, textures[0], 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                "Photo framebuffer is unavailable"
            }
            draw(size.first, size.second, size.first.toDouble() / size.second, snapshot)
            val pixels = ByteBuffer.allocateDirect(size.first * size.second * 4)
            GLES20.glReadPixels(0, 0, size.first, size.second, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            checkGl("photo readback")
            // OpenGL rows start at the bottom. Convert RGBA explicitly, independent of CPU endian.
            val argb = IntArray(size.first * size.second)
            for (y in 0 until size.second) for (x in 0 until size.first) {
                val index = ((size.second - 1 - y) * size.first + x) * 4
                val r = pixels.get(index).toInt() and 255
                val g = pixels.get(index + 1).toInt() and 255
                val b = pixels.get(index + 2).toInt() and 255
                argb[y * size.first + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
            Bitmap.createBitmap(argb, size.first, size.second, Bitmap.Config.ARGB_8888)
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, framebuffers, 0)
            GLES20.glDeleteTextures(1, textures, 0)
        }
    }

    suspend fun release() {
        if (closed.get()) return
        try {
            call {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    val glCurrent = runCatching { makeCurrent(pbuffer) }.isSuccess
                    input?.setOnFrameAvailableListener(null)
                    input?.release()
                    input = null
                    if (glCurrent && textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                    if (glCurrent && program != 0) GLES20.glDeleteProgram(program)
                    encoder?.let(::destroyWindow)
                    preview?.let(::destroyWindow)
                    encoder = null
                    preview = null
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(display)
                }
                firstFrame.cancel()
            }
        } finally {
            closed.set(true)
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
    }

    private fun renderFrame() {
        if (closed.get() || failed.get()) return
        try {
            makeCurrent(pbuffer)
            input?.updateTexImage() ?: return
            input?.getTransformMatrix(producerMatrix)
            // Use the host monotonic clock; some UVC drivers publish zero or discontinuous timestamps.
            lastFrameNs = SystemClock.elapsedRealtimeNanos()
            hasFrame = true
            val snapshot = settings
            drawPreview(snapshot)
            encoder?.let { sink ->
                val pts = framePacer.presentationTimeNs(lastFrameNs) ?: return@let
                try {
                    makeCurrent(sink.egl)
                    draw(sink.width, sink.height, sink.width.toDouble() / sink.height, snapshot)
                    check(EGLExt.eglPresentationTimeANDROID(display, sink.egl, pts)) { "Encoder timestamp failed" }
                    check(EGL14.eglSwapBuffers(display, sink.egl)) { "Encoder surface rejected a frame" }
                } catch (error: Throwable) {
                    makeCurrent(pbuffer)
                    destroyWindow(sink)
                    encoder = null
                    onRecordingFailure(error)
                }
            }
            firstFrame.complete(Unit)
            onFrame(lastFrameNs)
        } catch (error: Throwable) {
            firstFrame.completeExceptionally(error)
            if (failed.compareAndSet(false, true)) onFailure(error)
        }
    }

    private fun drawPreview(snapshot: RenderSettings) {
        val window = preview ?: return
        makeCurrent(window.egl)
        val output = encoder?.let { it.width to it.height } ?: RenderGeometry.outputSize(width, height, snapshot)
        draw(window.width, window.height, output.first.toDouble() / output.second, snapshot)
        check(EGL14.eglSwapBuffers(display, window.egl)) { "Preview surface is no longer available" }
    }

    private fun draw(outputWidth: Int, outputHeight: Int, outputRatio: Double, snapshot: RenderSettings) {
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val viewport = RenderGeometry.fittedViewport(outputWidth, outputHeight, outputRatio)
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "aPosition")
        val tex = GLES20.glGetAttribLocation(program, "aTexture")
        positions.position(0)
        coordinates.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, coordinates)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uProducer"), 1, false, producerMatrix, 0)
        GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(program, "uGeometry"), 1, false,
            RenderGeometry.textureMatrix(width, height, snapshot, outputRatio), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uBrightness"), snapshot.brightness / 255f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uContrast"), snapshot.contrast / 100f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSaturation"), snapshot.saturation / 100f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uCamera"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(tex)
        checkGl("render")
    }

    private fun replacePreview(target: PreviewTarget) {
        require(targetWidth(target) > 0 && targetHeight(target) > 0) { "Preview has no size" }
        makeCurrent(pbuffer)
        preview?.let(::destroyWindow)
        preview = null
        val surface = when (target) {
            is PreviewTarget.Surface -> target.surface
            is PreviewTarget.Texture -> Surface(target.texture)
        }
        try {
            preview = createWindow(surface, targetWidth(target), targetHeight(target), target is PreviewTarget.Texture)
        } catch (error: Throwable) {
            if (target is PreviewTarget.Texture) surface.release()
            throw error
        }
    }

    private fun createWindow(surface: Surface, width: Int, height: Int, ownsSurface: Boolean): Window {
        check(surface.isValid) { "Output surface is invalid" }
        val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(egl != EGL14.EGL_NO_SURFACE) { "Could not create EGL window: ${EGL14.eglGetError()}" }
        return Window(egl, surface, width, height, ownsSurface)
    }

    private fun destroyWindow(window: Window) {
        EGL14.eglDestroySurface(display, window.egl)
        if (window.ownsSurface) window.surface.release()
    }

    private fun initializeEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "EGL display unavailable" }
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) { "EGL initialization failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        for (recordable in listOf(true, false)) {
            for (alphaBits in listOf(8, 0)) {
                val attrs = mutableListOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, alphaBits, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT)
                if (recordable) attrs += listOf(EGL_RECORDABLE_ANDROID, 1)
                attrs += EGL14.EGL_NONE
                if (EGL14.eglChooseConfig(display, attrs.toIntArray(), 0, configs, 0, 1, count, 0) && count[0] > 0) {
                    config = configs[0]
                    recordableConfig = recordable
                    break
                }
            }
            if (config != null) break
        }
        check(config != null) { "No GLES2 preview configuration is available" }
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "GLES2 context creation failed" }
        pbuffer = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(pbuffer != EGL14.EGL_NO_SURFACE) { "EGL offscreen surface creation failed" }
        makeCurrent(pbuffer)
    }

    private fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "EGL context lost: ${EGL14.eglGetError()}" }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun <T> call(onDiscard: (T) -> Unit = {}, block: () -> T): T = suspendCancellableCoroutine { continuation ->
        if (closed.get()) {
            continuation.resumeWithException(IllegalStateException("Renderer is closed"))
        } else if (!handler.post {
            if (continuation.isActive) {
                try {
                    val result = block()
                    continuation.resume(result, onCancellation = { onDiscard(result) })
                } catch (error: Throwable) { continuation.resumeWithException(error) }
            }
        }) {
            continuation.resumeWithException(IllegalStateException("Renderer thread is unavailable"))
        }
    }

    private data class Window(val egl: EGLSurface, val surface: Surface, val width: Int, val height: Int, val ownsSurface: Boolean)

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val STALE_FRAME_NS = 3_000_000_000L
        private fun targetWidth(target: PreviewTarget) = when (target) { is PreviewTarget.Surface -> target.width; is PreviewTarget.Texture -> target.width }
        private fun targetHeight(target: PreviewTarget) = when (target) { is PreviewTarget.Surface -> target.height; is PreviewTarget.Texture -> target.height }
        private fun floats(vararg values: Float): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
        private fun checkGl(operation: String) {
            val error = GLES20.glGetError()
            check(error == GLES20.GL_NO_ERROR) { "$operation failed (GL $error)" }
        }
        private fun createProgram(vertex: String, fragment: String): Int {
            fun compile(type: Int, source: String): Int {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val status = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
                if (status[0] == 0) {
                    val message = GLES20.glGetShaderInfoLog(shader)
                    GLES20.glDeleteShader(shader)
                    error("Camera shader failed: $message")
                }
                return shader
            }
            val v = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val f = try { compile(GLES20.GL_FRAGMENT_SHADER, fragment) } catch (error: Throwable) {
                GLES20.glDeleteShader(v); throw error
            }
            val program = GLES20.glCreateProgram()
            try {
                GLES20.glAttachShader(program, v)
                GLES20.glAttachShader(program, f)
                GLES20.glLinkProgram(program)
                val status = IntArray(1)
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
                check(status[0] != 0) { "Camera shader link failed: ${GLES20.glGetProgramInfoLog(program)}" }
                return program
            } catch (error: Throwable) {
                GLES20.glDeleteProgram(program)
                throw error
            } finally {
                GLES20.glDeleteShader(v)
                GLES20.glDeleteShader(f)
            }
        }
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexture;
            uniform mat4 uProducer;
            uniform mat3 uGeometry;
            varying vec2 vTexture;
            void main() {
                gl_Position = aPosition;
                vec3 p = uGeometry * vec3(aTexture, 1.0);
                vTexture = (uProducer * vec4(p.xy, 0.0, 1.0)).xy;
            }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uCamera;
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;
            varying vec2 vTexture;
            void main() {
                vec3 color = texture2D(uCamera, vTexture).rgb;
                float luminance = dot(color, vec3(0.213, 0.715, 0.072));
                color = mix(vec3(luminance), color, uSaturation);
                gl_FragColor = vec4(clamp(color * uContrast + uBrightness, 0.0, 1.0), 1.0);
            }
        """
    }
}
