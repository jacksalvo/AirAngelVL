package com.airangelvl.camera.usb

import com.airangelvl.core.camera.CameraMetrics
import com.airangelvl.core.camera.OrientationMatrix
import com.airangelvl.core.camera.PreviewTarget
import com.airangelvl.core.camera.RecordingParams
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Each source has its own counters. Unknown measurements are never replaced by estimates. */
class UsbMetricsCollector @Inject constructor() {
    private val stream = MutableStateFlow(metrics(0f))
    private var windowStartNs = 0L
    private var count = 0L
    fun metrics() = stream.asStateFlow()

    @Synchronized fun onFrameTimestamp(timestampNs: Long) {
        if (windowStartNs == 0L) {
            windowStartNs = timestampNs
            count = 0
            return
        }
        count++
        val elapsed = timestampNs - windowStartNs
        if (elapsed >= 1_000_000_000L) {
            stream.value = metrics(count * 1_000_000_000f / elapsed)
            windowStartNs = timestampNs
            count = 0
        }
    }

    @Synchronized fun onStreamStart(target: PreviewTarget, orientation: OrientationMatrix) = reset()
    @Synchronized fun onStreamStop() = reset()
    @Synchronized fun onShutdown() = reset()
    fun onRecordStart(params: RecordingParams) = Unit
    fun onRecordStop() = Unit

    private fun reset() {
        windowStartNs = 0L
        count = 0L
        stream.value = metrics(0f)
    }

    companion object {
        private fun metrics(fps: Float) = CameraMetrics(fps, null, null, null, null, System.currentTimeMillis())
    }
}
