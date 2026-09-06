package com.airangelvl.camera.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.airangelvl.core.camera.CameraEvent
import com.airangelvl.core.camera.OrientationMatrix
import com.airangelvl.core.camera.PreviewTarget
import com.airangelvl.core.camera.RecordingParams
import com.airangelvl.core.camera.RecordingOutput
import com.airangelvl.core.camera.RecordingProfile
import com.airangelvl.core.camera.RecordingSession
import com.airangelvl.core.camera.RenderSettings
import com.airangelvl.core.media.MediaRepository
import com.airangelvl.core.media.MediaResult
import com.airangelvl.core.media.VideoSaveHandle
import com.airangelvl.core.media.VideoSaveProfile
import com.airangelvl.core.settings.SettingsRepository
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlin.math.atan2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/** One source owns one USB session. Permission and attachment are owned by the app coordinator. */
class UsbNegotiator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metricsCollector: UsbMetricsCollector,
    private val mediaRepository: MediaRepository,
    private val settingsRepository: SettingsRepository
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = MutableSharedFlow<CameraEvent>(replay = 1, extraBufferCapacity = 16)
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var device: UsbDevice? = null
    private var monitor: USBMonitor? = null
    private var control: USBMonitor.UsbControlBlock? = null
    private var camera: UVCCamera? = null
    @Volatile private var renderer: GlCameraRenderer? = null
    private var mode: UvcMode? = null
    private var recording: ActiveRecording? = null
    private var lastVideo: MediaResult.Video? = null
    private var lastRecordingError: Throwable? = null
    private var watchdog: Job? = null
    @Volatile private var renderSettings = RenderSettings()
    @Volatile private var streamReady = false
    @Volatile private var lastFrameAtNs = 0L

    fun observeEvents() = events.asSharedFlow()
    fun observeMetrics() = metricsCollector.metrics()

    suspend fun setActiveDevice(value: UsbDevice) = locked {
        check(camera == null && renderer == null) { "Close the previous USB session before binding another camera" }
        device = value
    }

    suspend fun clearActiveDevice() = close()

    suspend fun enumerateAndSelect(): UsbDevice = locked {
        val current = checkNotNull(device) { "No USB camera is selected" }
        check(usbManager.hasPermission(current)) { "USB camera permission has not been granted" }
        try {
            openCamera()
            current
        } catch (error: Throwable) {
            withContext(NonCancellable) { destroyCamera() }
            throw error
        }
    }

    suspend fun startStream(target: PreviewTarget, orientationMatrix: OrientationMatrix) = locked {
        if (streamReady) {
            renderer?.updatePreview(target)
            return@locked
        }
        openCamera()
        val active = checkNotNull(camera)
        val advertised = buildList {
            for (format in listOf(UVCCamera.FRAME_FORMAT_MJPEG, UVCCamera.FRAME_FORMAT_YUYV)) {
                active.getSupportedSizeList(format).orEmpty().forEach { add(UvcMode(it.width, it.height, format)) }
            }
        }
        val descriptors = runCatching { control?.rawDescriptors }.getOrNull() ?: byteArrayOf()
        val ranked = UvcModeSelector.rank(advertised, descriptors)
        check(ranked.isNotEmpty()) { "The camera advertises no supported MJPEG or YUYV video mode" }
        val remembered = runCatching {
            settingsRepository.getLastKnownGoodFormats().usb[deviceKey()]
        }.getOrNull()
        // Reserve attempts for each transport; a long MJPEG list must not starve YUYV fallback.
        val candidates = (ranked.filter { encodeMode(it) == remembered } + boundedCandidates(ranked)).distinct()
        var lastError: Throwable? = null
        val startedAt = SystemClock.elapsedRealtime()
        try {
            for (candidate in candidates) {
                currentCoroutineContext().ensureActive()
                if (SystemClock.elapsedRealtime() - startedAt > NEGOTIATION_TIMEOUT_MS) break
                var candidateRenderer: GlCameraRenderer? = null
                try {
                    openCamera()
                    val nextRenderer = GlCameraRenderer(
                        onFrame = { timestamp ->
                            lastFrameAtNs = timestamp
                            metricsCollector.onFrameTimestamp(timestamp)
                        },
                        onFailure = { error -> if (streamReady) events.tryEmit(CameraEvent.Error(
                            error.message ?: "Camera rendering failed", error)) },
                        onRecordingFailure = ::onRecordingFailure
                    )
                    candidateRenderer = nextRenderer
                    renderer = nextRenderer
                    nextRenderer.setSettings(renderSettings)
                    val input = nextRenderer.initialize(candidate.width, candidate.height, target)
                    val uvc = checkNotNull(camera)
                    val minFps = candidate.fps.takeIf { it > 0 } ?: 1
                    val maxFps = candidate.fps.takeIf { it > 0 } ?: 30
                    try {
                        uvc.setPreviewSize(candidate.width, candidate.height, minFps, maxFps,
                            candidate.format, UVCCamera.DEFAULT_BANDWIDTH)
                    } catch (error: IllegalArgumentException) {
                        if (candidate.fps != 0) throw error
                        // Some descriptor blobs omit frame intervals; permit a 60fps-only camera
                        // after the conservative range fails. The recorder still caps its input.
                        uvc.setPreviewSize(candidate.width, candidate.height, 1, 60,
                            candidate.format, UVCCamera.DEFAULT_BANDWIDTH)
                    }
                    uvc.setPreviewTexture(input)
                    uvc.startPreview()
                    withTimeout(FIRST_FRAME_TIMEOUT_MS) { nextRenderer.awaitFirstFrame() }
                    currentCoroutineContext().ensureActive()
                    mode = candidate
                    streamReady = true
                    metricsCollector.onStreamStart(target, orientationMatrix)
                    events.tryEmit(CameraEvent.PreviewReady(candidate.width, candidate.height, candidate.fps))
                    rememberMode(candidate)
                    startWatchdog()
                    Timber.i("USB preview ready: %dx%d, format=%d, fps=%d", candidate.width,
                        candidate.height, candidate.format, candidate.fps)
                    return@locked
                } catch (error: CancellationException) {
                    // A first-frame timeout is a failed mode; cancellation of the caller stops negotiation.
                    currentCoroutineContext().ensureActive()
                    lastError = error
                } catch (error: Throwable) {
                    lastError = error
                    Timber.w(error, "USB mode failed: %s", candidate)
                } finally {
                    if (!streamReady) withContext(NonCancellable) {
                        destroyCamera()
                        runCatching { candidateRenderer?.release() }
                        renderer = null
                    }
                }
            }
            throw IllegalStateException("The camera could not start a supported video mode. Check its USB connection and power.", lastError)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                streamReady = false
                destroyCamera()
                runCatching { renderer?.release() }
                renderer = null
            }
            throw error
        }
    }

    suspend fun updatePreview(target: PreviewTarget) = locked {
        checkNotNull(renderer) { "Camera preview is not running" }.updatePreview(target)
    }

    suspend fun captureFrame(): MediaResult.Photo = locked {
        check(streamReady) { "Camera preview is not ready" }
        check(mediaRepository.storagePreflight(50).meetsMinimum) { "Insufficient storage for a photo" }
        val bitmap = checkNotNull(renderer).capture()
        try {
            mediaRepository.saveImage(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun startRecording(params: RecordingParams): RecordingSession {
        try {
            return locked { startRecordingInternal(params) }
        } catch (cancelled: CancellationException) {
            // This catches prompt cancellation when returning from the IO dispatcher as well.
            withContext(NonCancellable) { locked { abortActiveRecording() } }
            throw cancelled
        }
    }

    private suspend fun startRecordingInternal(params: RecordingParams): RecordingSession {
        check(streamReady) { "Camera preview is not ready" }
        check(recording == null) { "A recording is already in progress" }
        require(!params.includeAudio) { "USB recordings do not include audio" }
        require(params.output is RecordingOutput.MediaStore) { "USB recordings are saved to the device gallery" }
        require(params.watermark == null) { "Recording watermarks are not supported" }
        check(mediaRepository.storagePreflight(1000).meetsMinimum) { "Insufficient storage to start recording" }
        val gl = checkNotNull(renderer)
        val size = gl.outputSize()
        val fps = minOf(params.profile.fps.coerceIn(1, 30), mode?.fps?.takeIf { it > 0 } ?: 30)
        val requested = params.profile.copy(width = size.first, height = size.second, fps = fps)
        val handle = mediaRepository.prepareVideoTarget(VideoSaveProfile("AirAngelVL",
            mediaRepository.generateVideoFilename(), "video/mp4"))
        val recorder = UsbVideoRecorder(handle, requested, ::onRecordingFailure)
        try {
            val ready = recorder.start()
            currentCoroutineContext().ensureActive()
            gl.attachEncoder(ready.surface, ready.profile.width, ready.profile.height, ready.profile.fps)
            currentCoroutineContext().ensureActive()
            val sessionId = UUID.randomUUID().toString()
            val session = UsbRecordingSession(sessionId, ready.profile) {
                scope.launch { locked { if (recording?.session?.id == sessionId) abortActiveRecording() } }
            }
            recording = ActiveRecording(session, handle, recorder)
            lastVideo = null
            lastRecordingError = null
            metricsCollector.onRecordStart(params.copy(profile = ready.profile))
            return session
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                runCatching { gl.detachEncoder() }
                runCatching { recorder.stop() }
                abortWhenClosed(recorder, handle)
            }
            throw error
        }
    }

    suspend fun stopRecording(): MediaResult.Video = locked {
        withContext(NonCancellable) { finishRecording() }
    }

    suspend fun stopStream() = locked {
        withContext(NonCancellable) { stopStreamInternal() }
    }

    suspend fun close() = locked {
        withContext(NonCancellable) {
            try {
                stopStreamInternal()
            } finally {
                runCatching { monitor?.destroy() }
                monitor = null
                device = null
                scope.cancel()
                metricsCollector.onShutdown()
            }
        }
    }

    fun setRenderSettings(value: RenderSettings) {
        renderSettings = value.normalized()
        renderer?.setSettings(renderSettings)
    }

    fun setPreferredAspectRatio(ratio: Double?) = setRenderSettings(renderSettings.copy(aspectRatio = ratio))

    fun setOrientationMatrix(matrix: OrientationMatrix) {
        val degrees = Math.toDegrees(atan2(matrix.values[3].toDouble(), matrix.values[0].toDouble())).toInt()
        setRenderSettings(renderSettings.copy(rotationDegrees = degrees))
    }

    private fun openCamera() {
        if (camera != null) return
        val current = checkNotNull(device) { "No USB camera is selected" }
        check(usbManager.hasPermission(current)) { "USB camera permission has been revoked" }
        val usbMonitor = monitor ?: USBMonitor(context, NO_DEVICE_CALLBACK).also { monitor = it }
        val block = usbMonitor.openDevice(current)
        control = block
        val opened = try { UVCCamera() } catch (error: LinkageError) {
            throw IllegalStateException("USB camera support could not load on this phone", error)
        }
        camera = opened
        opened.open(block)
    }

    private fun destroyCamera() {
        runCatching { camera?.stopPreview() }.onFailure { Timber.w(it, "Stopping USB preview failed") }
        runCatching { camera?.destroy() }.onFailure { Timber.w(it, "Closing USB camera failed") }
        camera = null
        runCatching { control?.close() }
        control = null
    }

    private suspend fun stopStreamInternal() {
        watchdog?.cancel()
        watchdog = null
        streamReady = false
        var recordingError: Throwable? = null
        try {
            if (recording != null) runCatching { finishRecording() }.onFailure { recordingError = it }
        } finally {
            destroyCamera()
            runCatching { renderer?.release() }.onFailure { Timber.w(it, "Releasing USB renderer failed") }
            renderer = null
            mode = null
            lastFrameAtNs = 0L
            metricsCollector.onStreamStop()
        }
        recordingError?.let { throw it }
    }

    private suspend fun finishRecording(): MediaResult.Video {
        val active = recording ?: run {
            lastRecordingError?.let { throw it }
            return lastVideo ?: error("No recording is in progress")
        }
        recording = null
        try {
            renderer?.detachEncoder()
            val stats = active.recorder.stop()
            val saved = mediaRepository.finalizeVideo(active.handle).copy(durationMs = stats.durationMs,
                averageBitrate = stats.averageBitrate, keyFrameIntervalS = stats.profile.gopSeconds)
            lastVideo = saved
            return saved
        } catch (error: Throwable) {
            active.recorder.requestStop()
            abortWhenClosed(active.recorder, active.handle)
            throw error
        } finally {
            metricsCollector.onRecordStop()
        }
    }

    private suspend fun abortActiveRecording() {
        val active = recording ?: return
        recording = null
        withContext(NonCancellable) {
            runCatching { renderer?.detachEncoder() }
            runCatching { active.recorder.stop() }
            abortWhenClosed(active.recorder, active.handle)
            metricsCollector.onRecordStop()
        }
    }

    private suspend fun abortWhenClosed(recorder: UsbVideoRecorder, handle: VideoSaveHandle) {
        if (recorder.isClosed) {
            mediaRepository.abortVideo(handle)
        } else {
            // A broken vendor codec must not keep the UI waiting or lose the owned descriptor
            // while its native writer is still alive. The failed target stays unpublished.
            cleanupScope.launch {
                recorder.awaitClosed()
                runCatching { mediaRepository.abortVideo(handle) }.onFailure { Timber.e(it, "Failed to remove unfinished USB video") }
            }
        }
    }

    private fun onRecordingFailure(error: Throwable) {
        scope.launch {
            locked {
                if (recording != null) {
                    lastRecordingError = error
                    abortActiveRecording()
                    events.tryEmit(CameraEvent.RecordingFailed(error.message ?: "Video recording failed", error))
                }
            }
        }
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive) {
                delay(1_000)
                if (streamReady && SystemClock.elapsedRealtimeNanos() - lastFrameAtNs > 5_000_000_000L) {
                    events.tryEmit(CameraEvent.Error("The USB camera stopped sending video. Reconnect the camera and retry."))
                    break
                }
            }
        }
    }

    private suspend fun rememberMode(value: UvcMode) {
        runCatching {
            val current = settingsRepository.getLastKnownGoodFormats()
            settingsRepository.updateLastKnownGoodFormats(current.copy(usb = current.usb + (deviceKey() to encodeMode(value))))
        }.onFailure { Timber.w(it, "Could not remember USB camera mode") }
    }

    private fun deviceKey(): String = checkNotNull(device).let { "${it.vendorId}:${it.productId}" }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private data class ActiveRecording(val session: UsbRecordingSession, val handle: VideoSaveHandle, val recorder: UsbVideoRecorder)

    companion object {
        private const val FIRST_FRAME_TIMEOUT_MS = 4_000L
        private const val NEGOTIATION_TIMEOUT_MS = 25_000L
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private fun encodeMode(mode: UvcMode) = "${mode.format}:${mode.width}:${mode.height}:${mode.fps}"
        private fun boundedCandidates(ranked: List<UvcMode>): List<UvcMode> {
            val result = mutableListOf<UvcMode>()
            val slower = mutableListOf<UvcMode>()
            for (format in listOf(UvcModeSelector.MJPEG, UvcModeSelector.YUYV)) {
                val sizes = ranked.filter { it.format == format }.groupBy { it.width to it.height }.values.take(3)
                sizes.forEach { result += it.first() }
                sizes.forEach { if (it.last() != it.first()) slower += it.last() }
            }
            return (result + slower).distinct()
        }
        private val NO_DEVICE_CALLBACK = object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice) = Unit
            override fun onDetach(device: UsbDevice) = Unit
            override fun onConnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock, createNew: Boolean) = Unit
            override fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) = Unit
            override fun onCancel(device: UsbDevice?) = Unit
        }
    }
}

private class UsbRecordingSession(override val id: String, override val profile: RecordingProfile, private val onCancel: () -> Unit) : RecordingSession {
    override val startedAtMs: Long = System.currentTimeMillis()
    override fun cancel() = onCancel()
}
