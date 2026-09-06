package com.airangelvl.camera.usb

/** Drops surplus input frames without compressing elapsed time in the saved video. */
internal class FramePacer(fps: Int) {
    private val intervalNs = 1_000_000_000L / fps.coerceIn(1, 60)
    private var originNs = -1L
    private var lastPtsNs = -1L
    private var nextDueNs = 0L
    fun presentationTimeNs(nowNs: Long): Long? {
        if (originNs < 0L) originNs = nowNs
        val pts = nowNs - originNs
        // Keep a timeline rather than measuring from the previous accepted frame. A camera
        // whose nominal 30fps arrives with small jitter must not accidentally become 15fps.
        val toleranceNs = minOf(2_000_000L, intervalNs / 10)
        if (lastPtsNs >= 0 && (pts <= lastPtsNs || pts + toleranceNs < nextDueNs)) return null
        lastPtsNs = pts.coerceAtLeast(0)
        nextDueNs = maxOf(nextDueNs + intervalNs, (lastPtsNs / intervalNs + 1) * intervalNs)
        return lastPtsNs
    }
}
