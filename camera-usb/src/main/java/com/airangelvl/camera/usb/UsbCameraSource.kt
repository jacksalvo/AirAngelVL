package com.airangelvl.camera.usb

import android.hardware.usb.UsbDevice
import com.airangelvl.core.camera.CameraMetrics
import com.airangelvl.core.camera.CameraEvent
import com.airangelvl.core.camera.CameraSource
import com.airangelvl.core.camera.OrientationMatrix
import com.airangelvl.core.camera.PreviewTarget
import com.airangelvl.core.camera.RecordingParams
import com.airangelvl.core.camera.RecordingSession
import com.airangelvl.core.camera.RenderSettings
import com.airangelvl.core.media.MediaResult
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

class UsbCameraSource @Inject constructor(
    private val negotiator: UsbNegotiator
) : CameraSource, UsbDeviceBinding {

    private var orientationMatrix: OrientationMatrix = OrientationMatrix(FloatArray(9) { if (it % 4 == 0) 1f else 0f })

    override suspend fun bind(device: UsbDevice) {
        negotiator.setActiveDevice(device)
    }

    override suspend fun unbind() {
        negotiator.clearActiveDevice()
    }

    override suspend fun open() {
        negotiator.enumerateAndSelect()
    }

    override suspend fun close() {
        negotiator.close()
    }

    override suspend fun startPreview(target: PreviewTarget) {
        negotiator.startStream(target, orientationMatrix)
    }

    override suspend fun updatePreview(target: PreviewTarget) = negotiator.updatePreview(target)

    override suspend fun stopPreview() {
        negotiator.stopStream()
    }

    override suspend fun capturePhoto(): MediaResult.Photo = negotiator.captureFrame()

    override suspend fun startRecording(params: RecordingParams): RecordingSession = negotiator.startRecording(params)

    override suspend fun stopRecording(): MediaResult.Video = negotiator.stopRecording()

    override fun setOrientationMatrix(matrix: OrientationMatrix) {
        orientationMatrix = matrix
        negotiator.setOrientationMatrix(matrix)
    }

    override fun setRenderSettings(settings: RenderSettings) = negotiator.setRenderSettings(settings)

    override fun observeEvents(): Flow<CameraEvent> = negotiator.observeEvents()

    override fun observeMetrics(): Flow<CameraMetrics> = negotiator.observeMetrics()
}




