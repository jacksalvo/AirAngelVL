package com.airangelvl.app.camera

import android.net.Uri
import android.view.Surface
import com.airangelvl.core.camera.CameraEvent
import com.airangelvl.core.camera.CameraMetrics
import com.airangelvl.core.camera.CameraSource
import com.airangelvl.core.camera.MediaStoreCollection
import com.airangelvl.core.camera.OrientationMatrix
import com.airangelvl.core.camera.PreviewTarget
import com.airangelvl.core.camera.RecordingOutput
import com.airangelvl.core.camera.RecordingParams
import com.airangelvl.core.camera.RecordingProfile
import com.airangelvl.core.camera.RecordingSession
import com.airangelvl.core.media.MediaResult
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraSessionControllerTest {
    private val target = PreviewTarget.Surface(mockk<Surface>(relaxed = true), 640, 480)
    private val params = RecordingParams(RecordingProfile(640, 480, 30, 2_000_000, 1),
        RecordingOutput.MediaStore(MediaStoreCollection("AirAngelVL", "")), false, null)

    @Test fun `preview is not ready until a first frame arrives`() = runTest {
        val controller = CameraSessionController(backgroundScope)
        val camera = FakeCamera().apply { previewGate = CompletableDeferred() }
        controller.connect(camera, target)
        runCurrent()
        assertEquals(CameraPhase.Connecting, controller.state.value.phase)
        controller.startRecording(params)
        assertEquals(0, camera.starts)
        camera.previewGate!!.complete(Unit)
        runCurrent()
        assertEquals(CameraPhase.Previewing, controller.state.value.phase)
    }

    @Test fun `double record taps create only one encoder`() = runTest {
        val controller = CameraSessionController(backgroundScope)
        val camera = FakeCamera().apply { startGate = CompletableDeferred() }
        controller.connect(camera, target)
        runCurrent()
        controller.startRecording(params)
        controller.startRecording(params)
        runCurrent()
        assertEquals(1, camera.starts)
        assertEquals(CameraPhase.StartingRecording, controller.state.value.phase)
        camera.startGate!!.complete(Unit)
        runCurrent()
        assertEquals(CameraPhase.Recording, controller.state.value.phase)
    }

    @Test fun `cancel during encoder startup aborts without publishing`() = runTest {
        val controller = CameraSessionController(backgroundScope)
        val camera = FakeCamera().apply { startGate = CompletableDeferred() }
        controller.connect(camera, target)
        runCurrent()
        controller.startRecording(params)
        runCurrent()
        controller.stopRecording()
        runCurrent()
        assertEquals(1, camera.cancelledStarts)
        assertEquals(0, camera.stops)
        assertEquals(CameraPhase.Previewing, controller.state.value.phase)
        camera.startGate!!.complete(Unit)
        runCurrent()
        assertEquals(CameraPhase.Previewing, controller.state.value.phase)
    }

    @Test fun `cancel before startup coroutine runs allows another recording`() = runTest {
        val controller = CameraSessionController(backgroundScope)
        val camera = FakeCamera()
        controller.connect(camera, target)
        runCurrent()
        controller.startRecording(params)
        controller.stopRecording()
        runCurrent()
        assertEquals(0, camera.starts)
        assertEquals("Recording cancelled.", controller.state.value.message)
        controller.startRecording(params)
        runCurrent()
        assertEquals(1, camera.starts)
        assertEquals(CameraPhase.Recording, controller.state.value.phase)
    }

    @Test fun `disconnect cancels startup and ignores stale completion`() = runTest {
        val controller = CameraSessionController(backgroundScope)
        val camera = FakeCamera().apply { startGate = CompletableDeferred() }
        controller.connect(camera, target)
        runCurrent()
        controller.startRecording(params)
        runCurrent()
        controller.disconnect("Disconnected")
        runCurrent()
        camera.startGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, camera.cancelledStarts)
        assertEquals(1, camera.closes)
        assertEquals(CameraPhase.Idle, controller.state.value.phase)
    }

    @Test fun `background plus surface loss finalizes exactly once before release`() = runTest {
        val controller = CameraSessionController(backgroundScope)
        val camera = FakeCamera()
        val media = mutableListOf<MediaResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.media.collect { media.add(it) } }
        controller.connect(camera, target)
        runCurrent()
        controller.startRecording(params)
        runCurrent()
        var released = false
        controller.disconnect("Background")
        controller.disconnect("Surface closed", afterClose = {
            assertEquals(1, camera.stops)
            assertEquals(1, camera.closes)
            released = true
        })
        runCurrent()
        assertEquals(1, camera.stops)
        assertEquals(listOf(camera.video), media)
        assertTrue(released)
        assertFalse(controller.state.value.isRecording)
    }

    @Test fun `encoder setup error never reports recording`() = runTest {
        val controller = CameraSessionController(backgroundScope)
        val camera = FakeCamera().apply { startFailure = IllegalStateException("Unsupported encoder") }
        controller.connect(camera, target)
        runCurrent()
        controller.startRecording(params)
        runCurrent()
        assertEquals(CameraPhase.Previewing, controller.state.value.phase)
        assertEquals("Unsupported encoder", controller.state.value.message)
        assertEquals(0, camera.stops)
    }

    @Test fun `failed muxer finalization is visible and never emits success`() = runTest {
        val controller = CameraSessionController(backgroundScope)
        val camera = FakeCamera().apply { stopFailure = IllegalStateException("Storage write failed") }
        val media = mutableListOf<MediaResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.media.collect { media.add(it) } }
        controller.connect(camera, target)
        runCurrent()
        controller.startRecording(params)
        runCurrent()
        controller.stopRecording()
        runCurrent()
        assertTrue(media.isEmpty())
        assertEquals("Storage write failed", controller.state.value.message)
        assertEquals(CameraPhase.Previewing, controller.state.value.phase)
    }

    @Test fun `asynchronous recorder failure clears REC and retains preview`() = runTest {
        val controller = CameraSessionController(backgroundScope)
        val camera = FakeCamera()
        controller.connect(camera, target)
        runCurrent()
        controller.startRecording(params)
        runCurrent()
        camera.events.emit(CameraEvent.RecordingFailed("Encoder stopped"))
        runCurrent()
        assertEquals(1, camera.stops)
        assertEquals(CameraPhase.Previewing, controller.state.value.phase)
        assertTrue(controller.state.value.message.contains("Encoder stopped"))
    }

    @Test fun `replacing a connecting camera cannot promote its stale preview`() = runTest {
        val controller = CameraSessionController(backgroundScope)
        val old = FakeCamera().apply { previewGate = CompletableDeferred() }
        val next = FakeCamera()
        controller.connect(old, target)
        runCurrent()
        controller.connect(next, target)
        runCurrent()
        old.previewGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, old.closes)
        assertEquals(0, next.closes)
        assertEquals(CameraPhase.Previewing, controller.state.value.phase)
    }

    @Test fun `asynchronous camera failure closes camera and offers retry`() = runTest {
        val controller = CameraSessionController(backgroundScope)
        val camera = FakeCamera()
        controller.connect(camera, target)
        runCurrent()
        camera.events.emit(CameraEvent.Error("No frames"))
        runCurrent()
        assertEquals(1, camera.closes)
        assertEquals(CameraPhase.Error, controller.state.value.phase)
        assertTrue(controller.state.value.canRetry)
    }

    @Test fun `photo failure never emits a capture success`() = runTest {
        val controller = CameraSessionController(backgroundScope)
        val camera = FakeCamera().apply { photoFailure = IllegalStateException("Storage access denied") }
        val media = mutableListOf<MediaResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.media.collect { media.add(it) } }
        controller.connect(camera, target)
        runCurrent()
        controller.capture()
        runCurrent()
        assertEquals("Storage access denied", controller.state.value.message)
        assertTrue(media.isEmpty())
        assertFalse(controller.state.value.captureInProgress)
    }

    @Test fun `invalidated surface replacement retains old surface until camera closes`() = runTest {
        val controller = CameraSessionController(backgroundScope)
        val camera = FakeCamera()
        controller.connect(camera, target)
        runCurrent()
        val replacement = PreviewTarget.Surface(mockk<Surface>(relaxed = true), 320, 240)
        controller.updatePreview(replacement)
        var released = false
        controller.releaseRetiredPreview(matches = { it === target }) {
            assertEquals("The old EGL target must close before its texture is released", 1, camera.closes)
            released = true
        }
        controller.disconnect("Background")
        runCurrent()
        assertTrue(released)
        assertEquals(1, camera.closes)
    }

    @Test fun `successful surface replacement releases only the retired target`() = runTest {
        val controller = CameraSessionController(backgroundScope)
        val camera = FakeCamera()
        controller.connect(camera, target)
        runCurrent()
        val replacement = PreviewTarget.Surface(mockk<Surface>(relaxed = true), 320, 240)
        controller.updatePreview(replacement)
        var released = false
        controller.releaseRetiredPreview(matches = { it === target }) { released = true }
        runCurrent()
        assertTrue(released)
        assertEquals(0, camera.closes)
        assertEquals(CameraPhase.Previewing, controller.state.value.phase)
    }

    private class FakeCamera : CameraSource {
        var previewGate: CompletableDeferred<Unit>? = null
        var startGate: CompletableDeferred<Unit>? = null
        var startFailure: Exception? = null
        var stopFailure: Exception? = null
        var photoFailure: Exception? = null
        var starts = 0
        var stops = 0
        var closes = 0
        var cancelledStarts = 0
        val video = MediaResult.Video(mockk<Uri>(), 1_000, 2_000_000, 1)
        val events = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 8)
        override suspend fun open() = Unit
        override suspend fun close() { closes++ }
        override suspend fun startPreview(target: PreviewTarget) { previewGate?.await() }
        override suspend fun stopPreview() = Unit
        override suspend fun capturePhoto(): MediaResult.Photo {
            photoFailure?.let { throw it }
            return MediaResult.Photo(mockk<Uri>(), 640, 480, 1L)
        }
        override suspend fun startRecording(params: RecordingParams): RecordingSession {
            starts++
            try { startGate?.await() } catch (cancelled: CancellationException) {
                cancelledStarts++
                throw cancelled
            }
            startFailure?.let { throw it }
            return object : RecordingSession {
                override val id = "test-session"
                override val profile = params.profile
                override val startedAtMs = 1L
                override fun cancel() = Unit
            }
        }
        override suspend fun stopRecording(): MediaResult.Video {
            stops++
            stopFailure?.let { throw it }
            return video
        }
        override fun setOrientationMatrix(matrix: OrientationMatrix) = Unit
        override fun observeEvents() = events
        override fun observeMetrics() = emptyFlow<CameraMetrics>()
    }
}
