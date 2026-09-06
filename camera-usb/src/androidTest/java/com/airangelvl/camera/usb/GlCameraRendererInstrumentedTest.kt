package com.airangelvl.camera.usb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airangelvl.core.camera.PreviewTarget
import com.airangelvl.core.camera.RecordingProfile
import com.airangelvl.core.camera.RenderSettings
import com.airangelvl.core.media.VideoSaveHandle
import com.serenegiant.usb.UVCCamera
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real SurfaceTexture, GLES2 and MediaCodec; it makes no claim about USB hardware. */
@RunWith(AndroidJUnit4::class)
class GlCameraRendererInstrumentedTest {
    @Test fun bundledNativeCameraLibraryLoadsAndReleasesWithoutUsbHardware() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            repeat(3) { UVCCamera().destroy() }
        }
    }

    @Test fun transformedPhotoAndEncodedVideoSharePixelsAndElapsedTime() = runBlocking {
        val drainThread = HandlerThread("TestPreviewDrain").apply { start() }
        val preview = ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 3)
        preview.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.close() }, Handler(drainThread.looper))
        val frameEvents = Channel<Long>(Channel.UNLIMITED)
        val failure = AtomicReference<Throwable?>()
        val renderer = GlCameraRenderer({ frameEvents.trySend(it) }, { failure.set(it) }, { failure.set(it) })
        var input: Surface? = null
        var recorder: UsbVideoRecorder? = null
        var reference: Bitmap? = null
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("renderer-validation-", ".mp4", context.cacheDir)
        try {
            val texture = renderer.initialize(320, 240, PreviewTarget.Surface(preview.surface, 320, 240))
            input = Surface(texture)
            suspend fun nextFrame() {
                drawQuadrants(requireNotNull(input))
                withTimeout(5_000) { frameEvents.receive() }
                failure.get()?.let { throw AssertionError("Rendering failed", it) }
            }
            nextFrame()
            renderer.awaitFirstFrame()
            renderer.capture().let { original ->
                try {
                    assertRgb(Color.RED, original.getPixel(80, 60), 8)
                    assertRgb(Color.GREEN, original.getPixel(240, 60), 8)
                    assertRgb(Color.BLUE, original.getPixel(80, 180), 8)
                    assertRgb(Color.YELLOW, original.getPixel(240, 180), 8)
                } finally { original.recycle() }
            }
            renderer.setSettings(RenderSettings(rotationDegrees = 90, mirrorHorizontal = true))
            nextFrame()
            renderer.capture().let { rotated ->
                try {
                    assertEquals(240, rotated.width)
                    assertEquals(320, rotated.height)
                    assertRgb(Color.RED, rotated.getPixel(60, 80), 8)
                    assertRgb(Color.BLUE, rotated.getPixel(180, 80), 8)
                    assertRgb(Color.GREEN, rotated.getPixel(60, 240), 8)
                    assertRgb(Color.YELLOW, rotated.getPixel(180, 240), 8)
                } finally { rotated.recycle() }
            }
            renderer.setSettings(RenderSettings(brightness = 32, saturation = 0, rotationDegrees = 90, mirrorHorizontal = true))
            nextFrame()
            val captured = renderer.capture()
            try {
                val jpeg = ByteArrayOutputStream().use { bytes ->
                    check(captured.compress(Bitmap.CompressFormat.JPEG, 90, bytes))
                    bytes.toByteArray()
                }
                reference = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
            } finally { captured.recycle() }
            val pixel = requireNotNull(reference).getPixel(60, 80)
            assertTrue("Saturation adjustment should produce gray", kotlin.math.abs(Color.red(pixel) - Color.green(pixel)) <= 2)
            assertTrue("Brightness should be reflected in the saved pixels", Color.red(pixel) in 80..92)
            val handle = VideoSaveHandle(Uri.fromFile(file), null, file, System.currentTimeMillis())
            recorder = UsbVideoRecorder(handle, RecordingProfile(240, 320, 30, 1_500_000, 1)) { failure.set(it) }
            val ready = requireNotNull(recorder).start()
            renderer.attachEncoder(ready.surface, ready.profile.width, ready.profile.height, ready.profile.fps)
            repeat(7) {
                nextFrame()
                if (it < 6) delay(200)
            }
            renderer.detachEncoder()
            val stats = requireNotNull(recorder).stop()
            assertTrue("5fps input must retain elapsed duration, not become a fast 30fps clip", stats.durationMs >= 1_000)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val duration = requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
                assertTrue("Saved MP4 duration was $duration ms", duration in 1_000..10_000)
                val decoded = requireNotNull(retriever.getFrameAtTime(300_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
                try {
                    val expected = requireNotNull(reference)
                    for ((x, y) in listOf(0.25 to 0.25, 0.75 to 0.25, 0.25 to 0.75, 0.75 to 0.75)) {
                        assertRgb(expected.getPixel((expected.width * x).toInt(), (expected.height * y).toInt()),
                            decoded.getPixel((decoded.width * x).toInt(), (decoded.height * y).toInt()), 14)
                    }
                } finally { decoded.recycle() }
            } finally { retriever.release() }
            assertNull(failure.get())
        } finally {
            withContext(NonCancellable) {
                runCatching { renderer.detachEncoder() }
                runCatching { recorder?.stop() }
                input?.release()
                renderer.release()
                reference?.recycle()
                preview.close()
                drainThread.quitSafely()
                frameEvents.close()
                check(file.delete() || !file.exists()) { "Could not remove test recording" }
            }
        }
    }

    private fun drawQuadrants(surface: Surface) {
        val canvas = surface.lockCanvas(null)
        try {
            val paint = Paint()
            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()
            paint.color = Color.RED; canvas.drawRect(0f, 0f, w / 2, h / 2, paint)
            paint.color = Color.GREEN; canvas.drawRect(w / 2, 0f, w, h / 2, paint)
            paint.color = Color.BLUE; canvas.drawRect(0f, h / 2, w / 2, h, paint)
            paint.color = Color.YELLOW; canvas.drawRect(w / 2, h / 2, w, h, paint)
        } finally { surface.unlockCanvasAndPost(canvas) }
    }

    private fun assertRgb(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("Expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}",
            kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
                kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
                kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance)
    }
}
