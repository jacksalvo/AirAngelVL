package com.airangelvl.app.camera

import android.graphics.SurfaceTexture
import com.airangelvl.app.thermal.ThermalManager
import com.airangelvl.app.usb.UsbEvent
import com.airangelvl.app.usb.UsbPermissionManager
import com.airangelvl.camera.usb.UsbDeviceBinding
import com.airangelvl.core.camera.CameraSourceFactory
import com.airangelvl.core.camera.MediaStoreCollection
import com.airangelvl.core.camera.PreviewTarget
import com.airangelvl.core.camera.RecordingOutput
import com.airangelvl.core.camera.RecordingParams
import com.airangelvl.core.camera.RecordingProfile
import com.airangelvl.core.camera.RenderSettings
import com.airangelvl.core.di.UsbCamera
import com.airangelvl.core.media.MediaRepository
import com.airangelvl.core.media.MediaResult
import com.airangelvl.diagnostics.DiagnosticsLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class CameraCoordinator @Inject constructor(
    @UsbCamera private val usbFactory: CameraSourceFactory,
    private val usb: UsbPermissionManager,
    private val mediaRepository: MediaRepository,
    private val diagnostics: DiagnosticsLogger,
    private val thermal: ThermalManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = CameraSessionController(scope)
    val uiState = session.state
    private val mutableCaptures = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val captureEvents = mutableCaptures.asSharedFlow()
    private var foreground = false
    private var target: PreviewTarget? = null
    private var selectedDeviceId: Int? = null
    private var selectionGeneration = 0L
    private var selectionJob: Job? = null
    private var settingsReady = false

    init {
        scope.launch {
            usb.events.collect { event ->
                if (!foreground) return@collect
                when (event) {
                    is UsbEvent.Attached -> tryConnect()
                    is UsbEvent.Detached -> if (event.device.deviceId == selectedDeviceId) {
                        invalidateSelection()
                        session.disconnect("USB camera disconnected. Any completed video has been saved.")
                    }
                    is UsbEvent.Permission -> if (event.granted) tryConnect() else {
                        session.disconnect("USB access was denied. Tap Retry to connect.", permissionRequired = true)
                    }
                    UsbEvent.ScreenOff -> setForeground(false)
                }
            }
        }
        scope.launch {
            session.media.collect { result ->
                if (result is MediaResult.Photo) mutableCaptures.tryEmit(Unit)
                diagnostics.log("media.saved:${result.javaClass.simpleName}")
            }
        }
        scope.launch {
            thermal.thermalState.collect { state ->
                if (!foreground) return@collect
                if (state.shouldThrottle) {
                    session.stopRecording("Recording stopped because the phone is too warm. Restart when it has cooled.")
                } else if (state.shouldWarn) {
                    session.showMessage("The phone is getting warm.")
                }
            }
        }
        scope.launch {
            session.state.map { it.phase }.distinctUntilChanged().collectLatest { phase ->
                if (phase == CameraPhase.Error) selectedDeviceId = null
                if (phase != CameraPhase.Recording) return@collectLatest
                while (isActive) {
                    delay(1_000)
                    try {
                        if (mediaRepository.storagePreflight(100).shouldAutoStop) {
                            session.stopRecording("Recording stopped because storage is low.")
                            break
                        }
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        session.stopRecording("Recording stopped because available storage could not be checked.")
                        break
                    }
                }
            }
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) {
            usb.start()
            thermal.start()
            tryConnect()
        } else {
            usb.stop()
            thermal.stop()
            invalidateSelection()
            session.disconnect("Recording stopped and camera released while the app was in the background.")
        }
    }

    fun onPreviewAttached(value: PreviewTarget) {
        val previous = target
        target = value
        if (selectedDeviceId != null && previous != null) session.updatePreview(value) else tryConnect()
    }

    fun onPreviewReleased(texture: SurfaceTexture? = null) {
        if (texture != null && (target as? PreviewTarget.Texture)?.texture !== texture) {
            session.releaseRetiredPreview(
                matches = { preview -> (preview as? PreviewTarget.Texture)?.texture === texture },
                release = { texture.release() }
            )
            return
        }
        target = null
        invalidateSelection()
        session.disconnect("Preview closed. Any completed video has been saved.", afterClose = { texture?.release() })
    }

    fun updateSettings(value: RenderSettings) {
        val firstSettings = !settingsReady
        session.updateSettings(value)
        settingsReady = true
        if (firstSettings) tryConnect()
    }

    fun retry() {
        usb.allowRetry()
        invalidateSelection()
        tryConnect()
    }

    fun cameraPermissionChanged() { tryConnect() }
    fun showMessage(value: String) { session.showMessage(value) }

    fun toggleRecording() {
        if (uiState.value.phase in setOf(CameraPhase.Recording, CameraPhase.StartingRecording)) {
            session.stopRecording()
            return
        }
        if (thermal.thermalState.value.shouldThrottle) {
            session.showMessage("The phone needs to cool before recording can start.")
            return
        }
        session.startRecording(RecordingParams(
            profile = RecordingProfile(1280, 720, 30, 8_000_000, 1),
            output = RecordingOutput.MediaStore(MediaStoreCollection("AirAngelVL", "")),
            includeAudio = false,
            watermark = null
        ))
    }

    fun requestCapture() {
        if (thermal.thermalState.value.shouldThrottle) {
            session.showMessage("The phone needs to cool before a photo can be saved.")
            return
        }
        session.capture()
    }

    private fun invalidateSelection() {
        ++selectionGeneration
        selectionJob?.cancel()
        selectionJob = null
        selectedDeviceId = null
    }

    private fun tryConnect() {
        if (!foreground || !settingsReady) return
        if (!usb.cameraPermissionGranted) {
            session.disconnect("Camera permission is required to connect a USB camera. Tap Retry.", permissionRequired = true)
            return
        }
        if (!usb.supportsUsbHost) {
            session.disconnect("This phone does not advertise USB host support.")
            return
        }
        val preview = target ?: return
        val devices = usb.devices()
        val device = devices.firstOrNull { it.deviceId == selectedDeviceId } ?: devices.firstOrNull()
        if (device == null) {
            if (selectedDeviceId != null) invalidateSelection()
            session.disconnect("Connect a USB camera to begin.")
            return
        }
        if (selectedDeviceId == device.deviceId) return
        if (!usb.hasPermission(device)) {
            if (usb.wasDenied(device)) {
                session.disconnect("USB access was denied. Tap Retry to connect.", permissionRequired = true)
            } else {
                session.showMessage("Allow USB access to connect the camera.")
                try { usb.requestPermission(device) } catch (failure: Exception) {
                    session.disconnect(failure.message ?: "USB permission could not be requested.", error = true)
                }
            }
            return
        }
        selectedDeviceId = device.deviceId
        val token = ++selectionGeneration
        selectionJob = scope.launch {
            var candidate: com.airangelvl.core.camera.CameraSource? = null
            var handedOff = false
            try {
                val created = usbFactory.create()
                candidate = created
                (created as? UsbDeviceBinding)?.bind(device) ?: error("USB camera backend cannot bind a device.")
                if (token != selectionGeneration || !foreground || target == null) return@launch
                session.connect(created, target ?: preview)
                handedOff = true
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (token == selectionGeneration) {
                    selectedDeviceId = null
                    session.disconnect(failure.message ?: "USB camera could not be opened.", error = true)
                }
            } finally {
                if (!handedOff) withContext(NonCancellable) { runCatching { candidate?.close() } }
            }
        }
    }
}
