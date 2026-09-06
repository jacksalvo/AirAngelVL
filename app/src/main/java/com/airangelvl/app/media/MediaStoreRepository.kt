package com.airangelvl.app.media

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.airangelvl.core.media.MediaRepository
import com.airangelvl.core.media.MediaResult
import com.airangelvl.core.media.StorageCheck
import com.airangelvl.core.media.VideoSaveHandle
import com.airangelvl.core.media.VideoSaveProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class MediaStoreRepository @Inject constructor(
    private val contentResolver: ContentResolver,
    @ApplicationContext private val context: Context
) : MediaRepository {
    private val transactions = WeakHashMap<VideoSaveHandle, Transaction>()
    private val transactionMutex = Mutex()

    override suspend fun storagePreflight(requiredFreeMb: Int): StorageCheck = withContext(Dispatchers.IO) {
        require(requiredFreeMb >= 0)
        // Count writable bytes rather than space that the OS might reclaim later.
        val availableMb = StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes / (1024L * 1024L)
        StorageCheck(availableMb, availableMb >= requiredFreeMb, availableMb <= 100)
    }

    override suspend fun generateImageFilename(): String = filename("IMG", "jpg")
    override suspend fun generateVideoFilename(): String = filename("VID", "mp4")

    override suspend fun saveImage(bytes: ByteArray): MediaResult.Photo = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "The captured image is empty" }
        val name = generateImageFilename()
        val timestamp = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = insertPending(collection, name, "image/jpeg", "Pictures/AirAngelVL")
            try {
                val stream = contentResolver.openOutputStream(uri, "w")
                    ?: throw IOException("Cannot open the photo for writing")
                stream.use { it.write(bytes) }
                publish(uri)
                MediaResult.Photo(uri, 0, 0, timestamp)
            } catch (error: Throwable) {
                withContext(NonCancellable) { deleteOwnRow(uri, error) }
                throw error
            }
        } else {
            val file = createLegacyFile(Environment.DIRECTORY_PICTURES, "AirAngelVL", name)
            var written = false
            try {
                file.outputStream().use { it.write(bytes) }
                written = true
                val uri = publishLegacy(file, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/jpeg")
                MediaResult.Photo(uri, 0, 0, timestamp)
            } catch (error: Throwable) {
                if (!written && !file.delete() && file.exists()) error.addSuppressed(IOException("Cannot remove incomplete photo"))
                throw error
            }
        }
    }

    override suspend fun saveImage(bitmap: Bitmap): MediaResult.Photo = withContext(Dispatchers.IO) {
        val bytes = ByteArrayOutputStream().use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) throw IOException("Cannot encode the captured image")
            stream.toByteArray()
        }
        saveImage(bytes).copy(width = bitmap.width, height = bitmap.height)
    }

    override suspend fun prepareVideoTarget(profile: VideoSaveProfile): VideoSaveHandle {
        var prepared: VideoSaveHandle? = null
        try {
            return withContext(Dispatchers.IO) {
                validateName(profile.displayName)
                validateDirectory(profile.relativePath)
                val handle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val uri = insertPending(collection, profile.displayName, profile.mimeType, "Movies/${profile.relativePath}")
                    try {
                        val descriptor = contentResolver.openFileDescriptor(uri, "w")
                            ?: throw IOException("Cannot open the video for writing")
                        VideoSaveHandle(uri, descriptor, null, System.currentTimeMillis())
                    } catch (error: Throwable) {
                        deleteOwnRow(uri, error)
                        throw error
                    }
                } else {
                    val file = createLegacyFile(Environment.DIRECTORY_MOVIES, profile.relativePath, profile.displayName)
                    VideoSaveHandle(Uri.fromFile(file), null, file, System.currentTimeMillis())
                }
                withContext(NonCancellable) {
                    transactionMutex.withLock { transactions[handle] = Transaction(profile.mimeType) }
                    prepared = handle
                }
                currentCoroutineContext().ensureActive()
                handle
            }
        } catch (cancelled: CancellationException) {
            prepared?.let { handle ->
                try { abortVideo(handle) } catch (cleanup: Exception) { cancelled.addSuppressed(cleanup) }
            }
            throw cancelled
        }
    }

    override suspend fun finalizeVideo(handle: VideoSaveHandle): MediaResult.Video = withContext(Dispatchers.IO + NonCancellable) {
        transactionMutex.withLock {
            val transaction = transactions[handle] ?: throw IllegalArgumentException("Unknown video save target")
            transaction.result?.let { return@withLock it }
            check(!transaction.aborted) { "The video save was cancelled" }
            val legacyFile = handle.legacyFile
            // Caller awaits successful encoder drain and muxer release before committing.
            val size = transaction.recordedSize ?: (legacyFile?.length() ?: handle.parcelFileDescriptor?.statSize ?: 0L)
                .also { transaction.recordedSize = it }
            handle.close()
            if (size == 0L) throw IOException("The recorded video is empty")
            val uri = if (legacyFile != null) {
                transaction.legacyPublicationStarted = true
                publishLegacy(legacyFile, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, transaction.mimeType)
            } else {
                publish(handle.uri)
                handle.uri
            }
            MediaResult.Video(uri, 0, 0, 1).also { transaction.result = it }
        }
    }

    override suspend fun abortVideo(handle: VideoSaveHandle): Unit = withContext(Dispatchers.IO + NonCancellable) {
        transactionMutex.withLock {
            val transaction = transactions[handle] ?: throw IllegalArgumentException("Unknown video save target")
            if (transaction.result != null || transaction.aborted) return@withLock
            val legacyFile = handle.legacyFile
            var closeFailure: Exception? = null
            try { handle.close() } catch (error: Exception) { closeFailure = error }
            if (legacyFile != null) {
                // Preserve completed bytes when gallery indexing failed after a successful encode.
                if (!transaction.legacyPublicationStarted && !legacyFile.delete() && legacyFile.exists()) throw IOException("Cannot remove incomplete video")
            } else {
                deletePendingRow(handle.uri)
            }
            transaction.aborted = true
            closeFailure?.let { throw it }
        }
    }

    private fun insertPending(collection: Uri, name: String, mimeType: String, relativePath: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return contentResolver.insert(collection, values) ?: throw IOException("Media storage did not create a save target")
    }

    private fun publish(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        if (contentResolver.update(uri, values, null, null) != 1) throw IOException("Media storage could not publish the saved file")
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(file: File, collection: Uri, mimeType: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.DATA, file.absolutePath)
            put(MediaStore.MediaColumns.SIZE, file.length())
        }
        return contentResolver.insert(collection, values) ?: throw IOException("Media storage could not publish the saved file")
    }

    @Suppress("DEPRECATION")
    private fun createLegacyFile(collection: String, relativePath: String, name: String): File {
        validateName(name)
        validateDirectory(relativePath)
        val directory = File(Environment.getExternalStoragePublicDirectory(collection), relativePath)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create the media directory")
        val file = File(directory, name)
        if (!file.createNewFile()) throw IOException("A media file with this name already exists")
        return file
    }

    private fun deleteOwnRow(uri: Uri, original: Throwable) {
        try { deletePendingRow(uri) } catch (cleanup: Exception) { original.addSuppressed(cleanup) }
    }

    private fun deletePendingRow(uri: Uri) {
        // If publication succeeded before a provider error, preserve the completed item.
        contentResolver.delete(uri, "${MediaStore.MediaColumns.IS_PENDING} = ?", arrayOf("1"))
    }

    private fun validateName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) { "Invalid media filename" }
    }

    private fun validateDirectory(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && path.split('/').all { it.isNotBlank() && it != "." && it != ".." }) { "Invalid media directory" }
    }

    private fun filename(prefix: String, extension: String): String = "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$extension"
    private class Transaction(
        val mimeType: String,
        var result: MediaResult.Video? = null,
        var aborted: Boolean = false,
        var recordedSize: Long? = null,
        var legacyPublicationStarted: Boolean = false
    )
}

