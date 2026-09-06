package com.airangelvl.app.camera

import com.airangelvl.core.camera.CameraEvent
import com.airangelvl.core.camera.CameraSource
import com.airangelvl.core.camera.PreviewTarget
import com.airangelvl.core.camera.RecordingParams
import com.airangelvl.core.camera.RecordingSession
import com.airangelvl.core.camera.RenderSettings
import com.airangelvl.core.media.MediaResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class CameraPhase { Idle, Connecting, Previewing, StartingRecording, Recording, StoppingRecording, Closing, Error }

data class CameraUiState(
    val phase: CameraPhase = CameraPhase.Idle,
    val message: String = "Connect a USB camera to begin.",
    val captureInProgress: Boolean = false,
    val canRetry: Boolean = false,
    val permissionRequired: Boolean = false
) {
    val isInitializing get() = phase == CameraPhase.Connecting
    val isRecording get() = phase == CameraPhase.Recording
    val canCapture get() = !captureInProgress && phase in setOf(CameraPhase.Previewing, CameraPhase.Recording)
    val canRecord get() = phase in setOf(CameraPhase.Previewing, CameraPhase.StartingRecording, CameraPhase.Recording)
    val canChangeFraming get() = phase !in setOf(CameraPhase.StartingRecording, CameraPhase.Recording, CameraPhase.StoppingRecording, CameraPhase.Closing)
}

/** All camera mutations run under one lock; lifecycle intent invalidates pending starts immediately. */
class CameraSessionController(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(CameraUiState())
    val state = mutableState.asStateFlow()
    private val mutableMedia = MutableSharedFlow<MediaResult>(extraBufferCapacity = 8)
    val media = mutableMedia.asSharedFlow()
    private var source: CameraSource? = null
    private var activePreview: PreviewTarget? = null
    private data class RetiredPreview(val matches: (PreviewTarget) -> Boolean, val release: () -> Unit)
    private val retiredPreviews = mutableListOf<RetiredPreview>()
    private var generation = 0L
    private var connectionJob: Job? = null
    private var startJob: Job? = null
    private var eventsJob: Job? = null
    private var recordingSession: RecordingSession? = null
    private val recording get() = recordingSession != null
    private var starting = false
    private var settings = RenderSettings()

    fun connect(next: CameraSource, target: PreviewTarget) {
        val token = ++generation
        connectionJob?.cancel()
        startJob?.cancel()
        mutableState.value = CameraUiState(CameraPhase.Connecting, "Connecting to USB camera…")
        connectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var adopted = false
            try {
            mutex.withLock {
                closeActive()
                if (token != generation) return@withLock
                source = next
                adopted = true
                try {
                    next.setRenderSettings(settings)
                    next.open()
                    activePreview = target
                    next.startPreview(target)
                    if (token != generation) return@withLock
                    mutableState.value = CameraUiState(CameraPhase.Previewing, "Camera ready.")
                    eventsJob = scope.launch {
                        next.observeEvents().collect { event ->
                            if (source !== next || token != generation) return@collect
                            when (event) {
                                is CameraEvent.Error -> disconnect("Camera disconnected: ${event.message}", error = true)
                                is CameraEvent.RecordingFailed -> stopRecording("Recording failed: ${event.message}")
                                else -> Unit
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { closeActive() }
                    throw cancelled
                } catch (failure: Exception) {
                    closeActive()
                    if (token == generation) {
                        mutableState.value = CameraUiState(CameraPhase.Error, failure.message ?: "Unable to open camera.", canRetry = true)
                    }
                }
            }
            } finally {
                if (!adopted) withContext(NonCancellable) { runCatching { next.close() } }
            }
        }
    }

    fun updatePreview(target: PreviewTarget) {
        val token = generation
        scope.launch {
            mutex.withLock {
                if (token != generation) return@withLock
                try {
                    val active = source ?: return@withLock
                    active.updatePreview(target)
                    activePreview = target
                    releaseUnusedPreviews()
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    disconnect(failure.message ?: "Preview surface unavailable.", error = true)
                }
            }
        }
    }

    fun updateSettings(value: RenderSettings) {
        settings = value
        source?.setRenderSettings(value)
    }

    fun startRecording(params: RecordingParams) {
        if (mutableState.value.phase != CameraPhase.Previewing || starting) return
        val token = generation
        starting = true
        mutableState.update { it.copy(phase = CameraPhase.StartingRecording, message = "Starting recording…") }
        startJob = scope.launch {
            mutex.withLock {
                try {
                    if (token != generation) return@withLock
                    val active = source ?: return@withLock
                    recordingSession = active.startRecording(params)
                    if (token != generation) {
                        finishRecording()
                    } else {
                        mutableState.update { it.copy(phase = CameraPhase.Recording, message = "Recording") }
                    }
                } catch (cancelled: CancellationException) {
                    // Backend start is transactional and aborts its provisional output on cancellation.
                    throw cancelled
                } catch (failure: Exception) {
                    if (token == generation) {
                        mutableState.update { it.copy(phase = CameraPhase.Previewing, message = failure.message ?: "Recording could not start.") }
                    }
                } finally {
                    starting = false
                }
            }
        }
    }

    fun stopRecording(reason: String = "Video saved.") {
        if (!recording && !starting) return
        val token = generation
        val cancelledBeforeRecording = !recording
        startJob?.takeIf { starting }?.cancel()
        mutableState.update { it.copy(phase = CameraPhase.StoppingRecording, message = "Saving video…") }
        scope.launch {
            mutex.withLock {
                if (token != generation) return@withLock
                val failure = finishRecording()
                starting = false
                mutableState.update {
                    it.copy(phase = if (source != null) CameraPhase.Previewing else CameraPhase.Idle,
                        message = failure ?: if (cancelledBeforeRecording && reason == "Video saved.") "Recording cancelled." else reason)
                }
            }
        }
    }

    fun capture() {
        if (!mutableState.value.canCapture) return
        val token = generation
        mutableState.update { it.copy(captureInProgress = true) }
        scope.launch {
            mutex.withLock {
                try {
                    if (token != generation) return@withLock
                    val result = source?.capturePhoto() ?: error("Camera is unavailable.")
                    mutableMedia.emit(result)
                    if (token == generation) mutableState.update { it.copy(message = if (recording) "Recording • Photo saved." else "Photo saved.") }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (token == generation) mutableState.update { it.copy(message = failure.message ?: "Photo could not be saved.") }
                } finally {
                    mutableState.update { it.copy(captureInProgress = false) }
                }
            }
        }
    }

    /** Completion runs after encoder finalization and UVC/EGL release, including on cancellation. */
    fun disconnect(message: String, error: Boolean = false, permissionRequired: Boolean = false, afterClose: (() -> Unit)? = null) {
        val token = ++generation
        connectionJob?.cancel()
        startJob?.cancel()
        mutableState.update { it.copy(phase = CameraPhase.Closing, message = if (recording) "Saving video…" else message) }
        scope.launch {
            try {
                mutex.withLock {
                    val failure = closeActive()
                    if (token == generation) {
                        mutableState.value = CameraUiState(
                            phase = if (error) CameraPhase.Error else CameraPhase.Idle,
                            message = failure ?: message,
                            canRetry = error || permissionRequired,
                            permissionRequired = permissionRequired
                        )
                    }
                }
            } finally { afterClose?.invoke() }
        }
    }

    fun showMessage(message: String) { mutableState.update { it.copy(message = message) } }

    /** A queued surface replacement may be invalidated; retain its old texture until actually detached. */
    fun releaseRetiredPreview(matches: (PreviewTarget) -> Boolean, release: () -> Unit) {
        scope.launch {
            mutex.withLock {
                retiredPreviews.add(RetiredPreview(matches, release))
                releaseUnusedPreviews()
            }
        }
    }

    private fun releaseUnusedPreviews() {
        val iterator = retiredPreviews.iterator()
        while (iterator.hasNext()) {
            val retired = iterator.next()
            if (activePreview?.let(retired.matches) != true) {
                iterator.remove()
                retired.release()
            }
        }
    }

    private suspend fun finishRecording(): String? {
        if (!recording) return null
        recordingSession = null
        return try {
            val result = source?.stopRecording() ?: error("Camera is unavailable.")
            mutableMedia.emit(result)
            null
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            failure.message ?: "Video could not be finalized."
        }
    }

    private suspend fun closeActive(): String? = withContext(NonCancellable) {
        eventsJob?.cancel()
        eventsJob = null
        val saveFailure = finishRecording()
        val closing = source
        source = null
        starting = false
        var closeFailure: String? = null
        try { closing?.close() } catch (failure: Exception) { closeFailure = failure.message }
        finally {
            activePreview = null
            releaseUnusedPreviews()
        }
        saveFailure ?: closeFailure
    }
}
