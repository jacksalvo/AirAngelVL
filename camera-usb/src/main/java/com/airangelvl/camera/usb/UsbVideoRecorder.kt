package com.airangelvl.camera.usb

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import com.airangelvl.core.camera.RecordingProfile
import com.airangelvl.core.media.VideoSaveHandle
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** The codec/muxer have exactly one owner. EGL is detached before requesting EOS. */
internal class UsbVideoRecorder(
    private val handle: VideoSaveHandle,
    private val requested: RecordingProfile,
    private val onFailure: (Throwable) -> Unit
) {
    data class Ready(val surface: Surface, val profile: RecordingProfile)
    data class Stats(val durationMs: Long, val averageBitrate: Int, val profile: RecordingProfile)
    private val started = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val ready = CompletableDeferred<Ready>()
    private val finished = CompletableDeferred<Result<Stats>>()
    val isClosed: Boolean get() = finished.isCompleted

    suspend fun start(): Ready {
        check(started.compareAndSet(false, true)) { "Recorder already started" }
        Thread(::run, "UsbEncoder").apply { isDaemon = true; start() }
        return withTimeout(START_TIMEOUT_MS) { ready.await() }
    }

    fun requestStop() { stopping.set(true) }

    suspend fun stop(): Stats {
        requestStop()
        return withTimeout(STOP_TIMEOUT_MS) { finished.await().getOrThrow() }
    }

    suspend fun awaitClosed() { finished.await() }

    private fun run() {
        var codec: MediaCodec? = null
        var surface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var profile = requested
        var failure: Throwable? = null
        var sampleCount = 0L
        var byteCount = 0L
        var firstPtsUs = -1L
        var lastPtsUs = -1L
        var completedEos = false
        var wasReady = false
        try {
            val selected = createEncoder(requested)
            codec = selected.codec
            profile = selected.profile
            surface = selected.surface
            muxer = when {
                handle.fileDescriptor != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                    MediaMuxer(requireNotNull(handle.fileDescriptor), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                handle.legacyFile != null ->
                    MediaMuxer(requireNotNull(handle.legacyFile).absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                else -> error("No supported recording destination")
            }
            wasReady = true
            ready.complete(Ready(surface, profile))
            val info = MediaCodec.BufferInfo()
            var track = -1
            var eosAt = 0L
            var lastOutputAt = SystemClock.elapsedRealtime()
            while (!completedEos) {
                if (stopping.get() && eosAt == 0L) {
                    codec.signalEndOfInputStream()
                    eosAt = SystemClock.elapsedRealtime()
                }
                val index = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Encoder changed format during recording" }
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    index >= 0 -> {
                        try {
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (info.size > 0 && !isConfig) {
                                check(muxerStarted) { "Encoder supplied video before its format" }
                                val buffer = checkNotNull(codec.getOutputBuffer(index)) { "Missing encoded video buffer" }
                                check(info.presentationTimeUs >= 0 && info.presentationTimeUs > lastPtsUs) {
                                    "Encoder produced non-monotonic video timestamps"
                                }
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buffer, info)
                                if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                                lastPtsUs = info.presentationTimeUs
                                byteCount += info.size
                                sampleCount++
                                lastOutputAt = SystemClock.elapsedRealtime()
                            }
                            completedEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
                val now = SystemClock.elapsedRealtime()
                check(eosAt == 0L || now - eosAt < EOS_TIMEOUT_MS) { "Encoder did not finish the video in time" }
                check(eosAt != 0L || now - lastOutputAt < NO_OUTPUT_TIMEOUT_MS) { "Encoder stopped producing video" }
            }
            check(sampleCount > 0) { "No video frames were recorded" }
        } catch (error: Throwable) {
            failure = error
            ready.completeExceptionally(error)
        } finally {
            // Completion means all native/file writers have released the destination.
            if (muxerStarted) runCatching { muxer?.stop() }.onFailure { if (failure == null) failure = it }
            runCatching { muxer?.release() }.onFailure { if (failure == null) failure = it }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { surface?.release() }
            val error = failure
            if (error != null) {
                finished.complete(Result.failure(error))
                if (wasReady && !stopping.get()) onFailure(error)
            } else {
                val lastFrameUs = 1_000_000L / profile.fps
                val durationMs = ((lastPtsUs - firstPtsUs + lastFrameUs) / 1000L).coerceAtLeast(1L)
                val bitrate = (byteCount * 8_000L / durationMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                finished.complete(Result.success(Stats(durationMs, bitrate, profile)))
            }
        }
    }

    private data class Encoder(val codec: MediaCodec, val profile: RecordingProfile, val surface: Surface)

    private fun createEncoder(requested: RecordingProfile): Encoder {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MIME, ignoreCase = true) } }
            .sortedBy { if (Build.VERSION.SDK_INT >= 29) it.isSoftwareOnly else
                (it.name.startsWith("OMX.google.", true) || it.name.startsWith("c2.android.", true)) }
        var lastError: Throwable? = null
        for (scale in listOf(1.0, 0.75, 0.5, 0.25)) {
            for (info in infos) {
                if (stopping.get()) error("Recording initialization was canceled")
                val caps = runCatching { info.getCapabilitiesForType(MIME) }.getOrNull() ?: continue
                if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) continue
                val video = caps.videoCapabilities ?: continue
                val w = ((requested.width * scale).toInt() / video.widthAlignment * video.widthAlignment)
                val h = ((requested.height * scale).toInt() / video.heightAlignment * video.heightAlignment)
                if (w < 2 || h < 2 || !video.isSizeSupported(w, h)) continue
                val rates = video.getSupportedFrameRatesFor(w, h)
                val fps = minOf(requested.fps.coerceIn(1, 30).toDouble(), rates.upper).toInt().coerceAtLeast(1)
                if (!video.areSizeAndRateSupported(w, h, fps.toDouble())) continue
                val bitrate = (requested.bitrate * scale * scale).toInt().coerceIn(video.bitrateRange.lower, video.bitrateRange.upper)
                val profile = requested.copy(width = w, height = h, fps = fps, bitrate = bitrate,
                    gopSeconds = requested.gopSeconds.coerceIn(1, 10))
                val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, profile.gopSeconds)
                    if (caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline }) {
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    }
                    if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                if (!caps.isFormatSupported(format)) continue
                var codec: MediaCodec? = null
                var input: Surface? = null
                try {
                    codec = MediaCodec.createByCodecName(info.name)
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    input = codec.createInputSurface()
                    codec.start()
                    return Encoder(codec, profile, input)
                } catch (error: Throwable) {
                    lastError = error
                    runCatching { codec?.stop() }
                    runCatching { codec?.release() }
                    runCatching { input?.release() }
                }
            }
        }
        throw IllegalStateException("This phone has no usable H.264 encoder for the camera image", lastError)
    }

    companion object {
        private const val MIME = "video/avc"
        private const val START_TIMEOUT_MS = 15_000L
        private const val STOP_TIMEOUT_MS = 10_000L
        private const val EOS_TIMEOUT_MS = 8_000L
        private const val NO_OUTPUT_TIMEOUT_MS = 10_000L
    }
}
