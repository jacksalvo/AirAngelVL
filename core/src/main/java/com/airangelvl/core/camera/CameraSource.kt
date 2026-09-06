package com.airangelvl.core.camera

import android.graphics.SurfaceTexture
import com.airangelvl.core.media.MediaResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface CameraSource {
    suspend fun open()
    suspend fun close()
    suspend fun startPreview(target: PreviewTarget)
    suspend fun updatePreview(target: PreviewTarget) = startPreview(target)
    suspend fun stopPreview()
    suspend fun capturePhoto(): MediaResult.Photo
    suspend fun startRecording(params: RecordingParams): RecordingSession
    suspend fun stopRecording(): MediaResult.Video
    fun setOrientationMatrix(matrix: OrientationMatrix)
    fun setRenderSettings(settings: RenderSettings) = Unit
    fun observeEvents(): Flow<CameraEvent> = emptyFlow()
    fun observeMetrics(): Flow<CameraMetrics>
}

/** The same adjustments are applied to preview pixels, photos and video. */
data class RenderSettings(
    val brightness: Int = 0,
    val contrast: Int = 100,
    val saturation: Int = 100,
    val rotationDegrees: Int = 0,
    val mirrorHorizontal: Boolean = false,
    /** Null preserves the camera's native aspect ratio; an explicit ratio center-crops. */
    val aspectRatio: Double? = null
) {
    fun normalized() = copy(
        brightness = brightness.coerceIn(-100, 100),
        contrast = contrast.coerceIn(0, 200),
        saturation = saturation.coerceIn(0, 200),
        rotationDegrees = ((rotationDegrees / 90 * 90) % 360 + 360) % 360,
        aspectRatio = aspectRatio?.takeIf { it.isFinite() && it in 0.25..4.0 }
    )
}

sealed interface CameraEvent {
    data class PreviewReady(val width: Int, val height: Int, val framesPerSecond: Int) : CameraEvent
    data class Error(val message: String, val cause: Throwable? = null) : CameraEvent
    data class RecordingFailed(val message: String, val cause: Throwable? = null) : CameraEvent
}

sealed interface PreviewTarget {
    data class Surface(val surface: android.view.Surface, val width: Int, val height: Int) : PreviewTarget
    data class Texture(val texture: SurfaceTexture, val width: Int, val height: Int) : PreviewTarget
}

data class RecordingParams(
    val profile: RecordingProfile,
    val output: RecordingOutput,
    val includeAudio: Boolean,
    val watermark: WatermarkConfig?
)

data class RecordingProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val gopSeconds: Int
)

sealed interface RecordingOutput {
    data class MediaStore(val collection: MediaStoreCollection) : RecordingOutput
    data class FileDescriptor(val fd: java.io.FileDescriptor) : RecordingOutput
}

data class MediaStoreCollection(
    val relativePath: String,
    val displayName: String
)

data class WatermarkConfig(
    val overlayAsset: String,
    val opacity: Float
)

data class OrientationMatrix(val values: FloatArray) {
    init {
        require(values.size == 9) { "Orientation matrix must have 9 floats" }
    }
}

data class CameraMetrics(
    val framesPerSecond: Float,
    val averageLatencyMs: Float?,
    val droppedFramePercent: Float?,
    val thermalThrottled: Boolean?,
    val frameQueueDepth: Int?,
    val timestampMs: Long
)

interface RecordingSession {
    val id: String
    val profile: RecordingProfile
    val startedAtMs: Long
    fun cancel()
}
