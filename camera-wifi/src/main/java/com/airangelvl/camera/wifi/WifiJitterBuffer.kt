package com.airangelvl.camera.wifi

import android.net.Uri
import com.airangelvl.core.camera.OrientationMatrix
import com.airangelvl.core.camera.PreviewTarget
import com.airangelvl.core.camera.RecordingParams
import com.airangelvl.core.camera.RecordingProfile
import com.airangelvl.core.camera.RecordingSession
import com.airangelvl.core.media.MediaResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiJitterBuffer @Inject constructor(
    private val metricsSink: WifiMetricsSink
) {
    suspend fun start() {
        metricsSink.onPreviewStart()
    }

    suspend fun stop() {
        metricsSink.onPreviewStop()
    }

    suspend fun attachTarget(target: PreviewTarget) {
        // TODO: Connect to decoder pipeline
    }

    suspend fun detachTarget() {
        // TODO: Detach preview surface
    }

    suspend fun captureSnapshot(): MediaResult.Photo {
        metricsSink.onSnapshot()
        return MediaResult.Photo(Uri.EMPTY, 0, 0, System.currentTimeMillis())
    }

    suspend fun startRecording(params: RecordingParams): RecordingSession {
        metricsSink.onRecordStart(params)
        return WifiRecordingSession(params)
    }

    suspend fun stopRecording(): MediaResult.Video {
        metricsSink.onRecordStop()
        return MediaResult.Video(Uri.EMPTY, 0, 0, 1)
    }

    fun setOrientation(matrix: OrientationMatrix) {
        // TODO: apply orientation to render pipeline
    }
}

class WifiRecordingSession(private val params: RecordingParams) : RecordingSession {
    override val id: String = "wifi-${params.profile.bitrate}-${params.profile.fps}"
    override val profile: RecordingProfile = params.profile
    override val startedAtMs: Long = System.currentTimeMillis()

    override fun cancel() {
        // TODO cancel network recording pipeline
    }
}

@Singleton
class WifiMetricsSink @Inject constructor() {
    fun onPreviewStart() = Unit
    fun onPreviewStop() = Unit
    fun onSnapshot() = Unit
    fun onRecordStart(params: RecordingParams) = Unit
    fun onRecordStop() = Unit
}

