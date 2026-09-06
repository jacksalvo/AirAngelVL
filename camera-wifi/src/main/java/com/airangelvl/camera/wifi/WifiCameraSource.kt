package com.airangelvl.camera.wifi

import com.airangelvl.core.camera.CameraMetrics
import com.airangelvl.core.camera.CameraSource
import com.airangelvl.core.camera.OrientationMatrix
import com.airangelvl.core.camera.PreviewTarget
import com.airangelvl.core.camera.RecordingParams
import com.airangelvl.core.camera.RecordingProfile
import com.airangelvl.core.camera.RecordingSession
import com.airangelvl.core.media.MediaResult
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class WifiCameraSource @Inject constructor(
    private val jitterBuffer: WifiJitterBuffer,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : CameraSource {

    private val metricsFlow = MutableSharedFlow<CameraMetrics>(replay = 1)

    override suspend fun open() = withContext(dispatcher) {
        jitterBuffer.start()
    }

    override suspend fun close() = withContext(dispatcher) {
        jitterBuffer.stop()
    }

    override suspend fun startPreview(target: PreviewTarget) = withContext(dispatcher) {
        jitterBuffer.attachTarget(target)
    }

    override suspend fun stopPreview() = withContext(dispatcher) {
        jitterBuffer.detachTarget()
    }

    override suspend fun capturePhoto(): MediaResult.Photo = withContext(dispatcher) {
        jitterBuffer.captureSnapshot()
    }

    override suspend fun startRecording(params: RecordingParams): RecordingSession = withContext(dispatcher) {
        jitterBuffer.startRecording(params)
    }

    override suspend fun stopRecording(): MediaResult.Video = withContext(dispatcher) {
        jitterBuffer.stopRecording()
    }

    override fun setOrientationMatrix(matrix: OrientationMatrix) {
        jitterBuffer.setOrientation(matrix)
    }

    override fun observeMetrics(): Flow<CameraMetrics> = metricsFlow.asSharedFlow()
}
