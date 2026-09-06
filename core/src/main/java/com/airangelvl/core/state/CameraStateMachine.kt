package com.airangelvl.core.state

import com.airangelvl.core.camera.CameraSource
import com.airangelvl.core.camera.RecordingProfile
import com.airangelvl.core.camera.RecordingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface CameraState {
    data object Idle : CameraState
    data object Discovering : CameraState
    data class PermissionRequired(val permissions: List<String>) : CameraState
    data class Opening(val generation: Int) : CameraState
    data class Previewing(val generation: Int) : CameraState
    data class Recording(val generation: Int, val session: RecordingSession) : CameraState
    data class CoolingDown(val resumeAtMs: Long) : CameraState
    data class Error(val message: String, val recoverable: Boolean) : CameraState
}

sealed interface CameraEvent {
    data object UsbAttached : CameraEvent
    data object UsbDetached : CameraEvent
    data object PermissionGranted : CameraEvent
    data object PermissionDenied : CameraEvent
    data class OpenOk(val source: CameraSource) : CameraEvent
    data class OpenFail(val reason: String) : CameraEvent
    data object StartRecord : CameraEvent
    data class StopRecord(val reason: StopReason = StopReason.User) : CameraEvent
    data object ThermalWarn : CameraEvent
    data object ThermalTrip : CameraEvent
    data object StorageLow : CameraEvent
    data object UserSearchWifi : CameraEvent
    data object WifiFound : CameraEvent
    data class WifiFail(val reason: String) : CameraEvent

    enum class StopReason { User, Thermal, Storage, Error }
}

class CameraStateMachine(
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow<CameraState>(CameraState.Idle)
    val state: StateFlow<CameraState> = _state

    private val stateMutex = Mutex()
    private var generationCounter: Int = 0
    private var ongoingJob: Job? = null

    suspend fun handle(event: CameraEvent) {
        stateMutex.withLock {
            when (val current = _state.value) {
                is CameraState.Idle -> handleFromIdle(event)
                is CameraState.Discovering -> handleFromDiscovering(event)
                is CameraState.PermissionRequired -> handleFromPermissionRequired(event)
                is CameraState.Opening -> handleFromOpening(current, event)
                is CameraState.Previewing -> handleFromPreviewing(current, event)
                is CameraState.Recording -> handleFromRecording(current, event)
                is CameraState.CoolingDown -> handleFromCoolingDown(event)
                is CameraState.Error -> handleFromError(event)
            }
        }
    }

    private fun handleFromIdle(event: CameraEvent) {
        when (event) {
            CameraEvent.UsbAttached -> transition(CameraState.PermissionRequired(listOf(USB_PERMISSION_TOKEN)))
            CameraEvent.UserSearchWifi -> transition(CameraState.Discovering)
            else -> Unit
        }
    }

    private fun handleFromDiscovering(event: CameraEvent) {
        when (event) {
            CameraEvent.PermissionGranted -> transition(CameraState.Opening(incrementGeneration()))
            CameraEvent.PermissionDenied -> transition(CameraState.Error("USB permission denied", recoverable = true))
            is CameraEvent.OpenFail -> transition(CameraState.Error("Discovery failure: ${event.reason}", recoverable = true))
            CameraEvent.UsbDetached -> transition(CameraState.Idle)
            else -> Unit
        }
    }

    private fun handleFromPermissionRequired(event: CameraEvent) {
        when (event) {
            CameraEvent.PermissionGranted -> transition(CameraState.Opening(incrementGeneration()))
            CameraEvent.PermissionDenied -> transition(CameraState.Error("Permission denied", recoverable = true))
            CameraEvent.UsbDetached -> transition(CameraState.Idle)
            else -> Unit
        }
    }

    private fun handleFromOpening(current: CameraState.Opening, event: CameraEvent) {
        when (event) {
            is CameraEvent.OpenOk -> transition(CameraState.Previewing(current.generation))
            is CameraEvent.OpenFail -> transition(CameraState.Error(event.reason, recoverable = true))
            CameraEvent.UsbDetached -> transition(CameraState.Idle)
            else -> Unit
        }
    }

    private fun handleFromPreviewing(current: CameraState.Previewing, event: CameraEvent) {
        when (event) {
            CameraEvent.StartRecord -> startRecording(current)
            is CameraEvent.StopRecord -> transition(CameraState.Previewing(current.generation))
            CameraEvent.UsbDetached -> transition(CameraState.Idle)
            CameraEvent.ThermalWarn -> transition(CameraState.CoolingDown(System.currentTimeMillis() + 120_000L))
            is CameraEvent.OpenFail -> transition(CameraState.Error(event.reason, recoverable = true))
            else -> Unit
        }
    }

    private fun handleFromRecording(current: CameraState.Recording, event: CameraEvent) {
        when (event) {
            is CameraEvent.StopRecord -> transition(CameraState.Previewing(current.generation))
            CameraEvent.UsbDetached -> transition(CameraState.Idle)
            CameraEvent.ThermalTrip -> transition(CameraState.CoolingDown(System.currentTimeMillis() + 120_000L))
            CameraEvent.StorageLow -> transition(CameraState.CoolingDown(System.currentTimeMillis() + 10_000L))
            else -> Unit
        }
    }

    private fun handleFromCoolingDown(event: CameraEvent) {
        when (event) {
            CameraEvent.ThermalTrip, CameraEvent.ThermalWarn -> Unit
            is CameraEvent.StopRecord -> Unit
            else -> transition(CameraState.Idle)
        }
    }

    private fun handleFromError(event: CameraEvent) {
        when (event) {
            CameraEvent.UsbAttached -> transition(CameraState.PermissionRequired(listOf(USB_PERMISSION_TOKEN)))
            CameraEvent.UserSearchWifi -> transition(CameraState.Discovering)
            else -> Unit
        }
    }

    private fun startRecording(current: CameraState.Previewing) {
        val job = scope.launch {
            // TODO: Bridge to backends once available.
        }
        ongoingJob?.cancel()
        ongoingJob = job
        transition(
            CameraState.Recording(
                generation = current.generation,
                session = DummyRecordingSession(current.generation)
            )
        )
    }

    private fun transition(next: CameraState) {
        _state.value = next
    }

    private fun incrementGeneration(): Int {
        generationCounter += 1
        return generationCounter
    }

    private data class DummyRecordingSession(
        override val id: String,
        override val profile: RecordingProfile,
        override val startedAtMs: Long
    ) : RecordingSession {
        constructor(generation: Int) : this(
            id = "dummy-$generation",
            profile = RecordingProfile(width = 0, height = 0, fps = 0, bitrate = 0, gopSeconds = 1),
            startedAtMs = System.currentTimeMillis()
        )

        override fun cancel() {
            // no-op placeholder
        }
    }

    companion object {
        const val USB_PERMISSION_TOKEN = "android.hardware.usb.permission.USB_DEVICE"
    }
}
