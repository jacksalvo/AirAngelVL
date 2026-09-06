package com.airangelvl.app

import android.graphics.SurfaceTexture
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airangelvl.app.camera.CameraCoordinator
import com.airangelvl.app.camera.PreviewAspectMode
import com.airangelvl.app.settings.ImageSettingsRepository
import com.airangelvl.core.camera.PreviewTarget
import com.airangelvl.core.camera.RenderSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val coordinator: CameraCoordinator,
    private val preferences: ImageSettingsRepository
) : ViewModel() {
    val uiState = coordinator.uiState
    val captureEvents = coordinator.captureEvents
    private val mutableAspectMode = MutableStateFlow(PreviewAspectMode.FULL_SCREEN)
    val aspectMode = mutableAspectMode.asStateFlow()
    private val mutableAdjustments = MutableStateFlow(ImageAdjustments())
    val imageAdjustments = mutableAdjustments.asStateFlow()
    private val mutableSettingsReady = MutableStateFlow(false)
    val settingsReady = mutableSettingsReady.asStateFlow()
    private val saves = Channel<RenderSettings>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            val restored = try { preferences.settings.first() } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                coordinator.showMessage("Image settings could not be loaded; using defaults.")
                RenderSettings()
            }
            mutableAdjustments.value = ImageAdjustments(restored.brightness, restored.contrast, restored.saturation)
            mutableAspectMode.value = PreviewAspectMode.entries.firstOrNull { it.ratio == restored.aspectRatio } ?: PreviewAspectMode.FULL_SCREEN
            coordinator.updateSettings(restored)
            mutableSettingsReady.value = true
            for (settings in saves) {
                try { preferences.update(settings) } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    coordinator.showMessage("Image settings apply now but could not be saved for next time.")
                }
            }
        }
    }

    fun onRecordTapped() = coordinator.toggleRecording()
    fun onCaptureTapped() = coordinator.requestCapture()
    fun onPreviewAvailable(target: PreviewTarget) = coordinator.onPreviewAttached(target)
    fun onPreviewReleased(texture: SurfaceTexture) = coordinator.onPreviewReleased(texture)
    fun onForegroundChanged(foreground: Boolean) = coordinator.setForeground(foreground)
    fun onCameraPermissionChanged() = coordinator.cameraPermissionChanged()
    fun retry() = coordinator.retry()
    fun showMessage(message: String) = coordinator.showMessage(message)

    fun onAspectModeChanged(mode: PreviewAspectMode) {
        if (!uiState.value.canChangeFraming) return
        mutableAspectMode.value = mode
        applySettings()
    }

    fun onImageAdjustmentsChanged(adjustments: ImageAdjustments) {
        mutableAdjustments.value = adjustments
        applySettings()
    }

    private fun applySettings() {
        val adjustments = mutableAdjustments.value
        val settings = RenderSettings(
            brightness = adjustments.brightness,
            contrast = adjustments.contrast,
            saturation = adjustments.saturation,
            aspectRatio = mutableAspectMode.value.ratio
        )
        coordinator.updateSettings(settings)
        saves.trySend(settings)
    }
}
