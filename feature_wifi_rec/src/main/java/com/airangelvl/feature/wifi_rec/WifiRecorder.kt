package com.airangelvl.feature.wifi_rec

import com.airangelvl.core.camera.RecordingParams
import com.airangelvl.core.media.MediaResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class WifiRecorder @Inject constructor(
    private val ffmpegBridge: FfmpegBridge
) {
    suspend fun start(params: RecordingParams) = withContext(Dispatchers.IO) {
        ffmpegBridge.start(params)
    }

    suspend fun stop(): MediaResult.Video = withContext(Dispatchers.IO) {
        ffmpegBridge.stop()
    }
}

@Singleton
class FfmpegBridge @Inject constructor() {
    suspend fun start(params: RecordingParams) {
        // TODO: load native bridge and launch session
    }

    suspend fun stop(): MediaResult.Video {
        return MediaResult.Video(uri = android.net.Uri.EMPTY, durationMs = 0, averageBitrate = 0, keyFrameIntervalS = 1)
    }
}
