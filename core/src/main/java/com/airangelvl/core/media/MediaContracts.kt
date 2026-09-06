package com.airangelvl.core.media

import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicBoolean

sealed class MediaResult {
    data class Photo(
        val uri: Uri,
        val width: Int,
        val height: Int,
        val captureTimestampMs: Long
    ) : MediaResult()

    data class Video(
        val uri: Uri,
        val durationMs: Long,
        val averageBitrate: Int,
        val keyFrameIntervalS: Int
    ) : MediaResult()

    data object None : MediaResult()
}

interface MediaRepository {
    suspend fun storagePreflight(requiredFreeMb: Int): StorageCheck
    suspend fun generateImageFilename(): String
    suspend fun generateVideoFilename(): String
    suspend fun saveImage(bytes: ByteArray): MediaResult.Photo
    suspend fun saveImage(bitmap: android.graphics.Bitmap): MediaResult.Photo
    suspend fun prepareVideoTarget(profile: VideoSaveProfile): VideoSaveHandle
    suspend fun finalizeVideo(handle: VideoSaveHandle): MediaResult.Video
    suspend fun abortVideo(handle: VideoSaveHandle)
}

data class StorageCheck(
    val availableMb: Long,
    val meetsMinimum: Boolean,
    val shouldAutoStop: Boolean
)

data class VideoSaveProfile(
    /** Folder inside the public Movies collection, for example "AirAngelVL". */
    val relativePath: String,
    val displayName: String,
    val mimeType: String
)

/** Owns the output until the repository commits or aborts the save. */
class VideoSaveHandle(
    val uri: Uri,
    val parcelFileDescriptor: ParcelFileDescriptor?,
    val legacyFile: File?,
    val startedAtMs: Long
) : Closeable {
    private val closed = AtomicBoolean(false)
    val fileDescriptor: FileDescriptor?
        get() = parcelFileDescriptor?.fileDescriptor

    override fun close() {
        if (closed.compareAndSet(false, true)) parcelFileDescriptor?.close()
    }
}
