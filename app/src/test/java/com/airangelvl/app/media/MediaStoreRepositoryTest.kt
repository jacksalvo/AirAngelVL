package com.airangelvl.app.media

import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.airangelvl.core.media.VideoSaveProfile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29, 34])
class MediaStoreRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var provider: TestMediaProvider
    private lateinit var repository: MediaStoreRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        provider = TestMediaProvider(temporary.root)
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        repository = MediaStoreRepository(context.contentResolver, context)
    }

    @Test fun `photo is published only after its bytes have been written`() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val result = repository.saveImage(bytes)
        assertArrayEquals(bytes, provider.files.getValue(result.uri).readBytes())
        assertEquals(0, provider.rows.getValue(result.uri).getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(1, provider.publishCount)
        assertEquals(bytes.size.toLong(), provider.sizeWhenPublished)
    }

    @Test fun `failed photo write removes only its own new row and reports failure`() = runTest {
        val existing = provider.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues())!!
        provider.failOpen = true
        try {
            repository.saveImage(byteArrayOf(1))
            fail("Expected a write error")
        } catch (_: IOException) { }
        assertEquals(setOf(existing), provider.rows.keys)
        assertEquals(0, provider.publishCount)
    }

    @Test fun `null insert is a failure rather than an empty successful URI`() = runTest {
        provider.failInsert = true
        try {
            repository.saveImage(byteArrayOf(1))
            fail("Expected an insert error")
        } catch (_: IOException) { }
        assertTrue(provider.rows.isEmpty())
    }

    @Test fun `video commit closes its descriptor and is idempotent`() = runTest {
        val handle = repository.prepareVideoTarget(profile())
        assertNotNull(handle.fileDescriptor)
        assertNull(handle.legacyFile)
        assertEquals(1, provider.rows.getValue(handle.uri).getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals("Movies/AirAngelVL", provider.rows.getValue(handle.uri).getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        provider.files.getValue(handle.uri).writeBytes(byteArrayOf(1, 2, 3))
        val first = repository.finalizeVideo(handle)
        assertEquals(first, repository.finalizeVideo(handle))
        assertFalse(handle.fileDescriptor!!.valid())
        repository.abortVideo(handle)
        assertTrue(provider.rows.containsKey(first.uri))
        assertEquals(1, provider.publishCount)
    }

    @Test fun `video abort closes descriptor and deletes exactly once`() = runTest {
        val handle = repository.prepareVideoTarget(profile())
        repository.abortVideo(handle)
        repository.abortVideo(handle)
        assertFalse(handle.fileDescriptor!!.valid())
        assertFalse(provider.rows.containsKey(handle.uri))
        assertEquals(1, provider.deleteCount)
        try {
            repository.finalizeVideo(handle)
            fail("An aborted video must not be published")
        } catch (_: IllegalStateException) { }
    }

    @Test fun `descriptor open failure removes pending row`() = runTest {
        provider.failOpen = true
        try {
            repository.prepareVideoTarget(profile())
            fail("Expected an open error")
        } catch (_: IOException) { }
        assertTrue(provider.rows.isEmpty())
    }

    @Test fun `cancelling while opening the descriptor cleans the newly created target`() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        provider.beforeOpen = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val work = launch(Dispatchers.IO) { repository.prepareVideoTarget(profile()) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            work.cancel()
        } finally {
            release.countDown()
        }
        work.join()
        assertTrue(provider.rows.isEmpty())
        assertEquals(1, provider.deleteCount)
    }

    @Test fun `failed publication can be retried after descriptor closes`() = runTest {
        val handle = repository.prepareVideoTarget(profile())
        provider.files.getValue(handle.uri).writeBytes(byteArrayOf(1))
        provider.failPublish = true
        try {
            repository.finalizeVideo(handle)
            fail("Expected a publication error")
        } catch (_: IOException) { }
        assertFalse(handle.fileDescriptor!!.valid())
        provider.failPublish = false
        assertEquals(handle.uri, repository.finalizeVideo(handle).uri)
    }

    @Test
    @Config(sdk = [24, 28])
    fun `legacy video exposes a public file and publishes only at completion`() = runTest {
        val handle = repository.prepareVideoTarget(profile())
        assertNull(handle.fileDescriptor)
        val file = requireNotNull(handle.legacyFile)
        assertEquals(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "AirAngelVL"), file.parentFile)
        assertTrue(provider.rows.isEmpty())
        file.writeBytes(byteArrayOf(1, 2, 3))
        val result = repository.finalizeVideo(handle)
        assertEquals("content", result.uri.scheme)
        assertEquals(file.absolutePath, provider.rows.getValue(result.uri).getAsString(MediaStore.MediaColumns.DATA))
        assertFalse(provider.rows.getValue(result.uri).containsKey(MediaStore.MediaColumns.IS_PENDING))
        repository.abortVideo(handle)
        assertTrue(file.exists())
        assertEquals(result, repository.finalizeVideo(handle))
    }

    @Test
    @Config(sdk = [24, 28])
    fun `legacy photo is saved into shared Pictures and indexed`() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val result = repository.saveImage(bytes)
        val path = provider.rows.getValue(result.uri).getAsString(MediaStore.MediaColumns.DATA)
        assertTrue(path.replace('\\', '/').contains("/Pictures/AirAngelVL/"))
        assertArrayEquals(bytes, File(path).readBytes())
    }

    private fun profile() = VideoSaveProfile("AirAngelVL", "test-${System.nanoTime()}.mp4", "video/mp4")

    @Test
    @Config(sdk = [24, 28])
    fun `an existing filename is never overwritten or removed by a failed prepare`() = runTest {
        val profile = profile()
        val first = repository.prepareVideoTarget(profile)
        val file = requireNotNull(first.legacyFile)
        file.writeBytes(byteArrayOf(4, 5, 6))
        try {
            repository.prepareVideoTarget(profile)
            fail("Expected a filename collision")
        } catch (_: IOException) { }
        assertArrayEquals(byteArrayOf(4, 5, 6), file.readBytes())
        repository.abortVideo(first)
        assertFalse(file.exists())
    }

    @Test
    @Config(sdk = [24, 28])
    fun `failed legacy indexing preserves completed video bytes`() = runTest {
        val handle = repository.prepareVideoTarget(profile())
        val file = requireNotNull(handle.legacyFile)
        file.writeBytes(byteArrayOf(1, 2, 3))
        provider.failInsert = true
        try {
            repository.finalizeVideo(handle)
            fail("Expected a gallery indexing error")
        } catch (_: IOException) { }
        repository.abortVideo(handle)
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
    }
}

private class TestMediaProvider(private val root: File) : ContentProvider() {
    val rows = linkedMapOf<Uri, ContentValues>()
    val files = linkedMapOf<Uri, File>()
    var failInsert = false
    var failOpen = false
    var failPublish = false
    var beforeOpen: (() -> Unit)? = null
    var publishCount = 0
    var deleteCount = 0
    var sizeWhenPublished = -1L
    private var nextId = 1L
    override fun onCreate() = true
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (failInsert) return null
        val result = ContentUris.withAppendedId(uri, nextId++)
        val copied = ContentValues(values ?: ContentValues())
        rows[result] = copied
        files[result] = copied.getAsString(MediaStore.MediaColumns.DATA)?.let(::File)
            ?: File(root, "${nextId}.media").apply { createNewFile() }
        return result
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        beforeOpen?.invoke()
        if (failOpen) throw FileNotFoundException("Injected write failure")
        return ParcelFileDescriptor.open(files.getValue(uri), ParcelFileDescriptor.MODE_READ_WRITE)
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        if (failPublish) return 0
        val row = rows[uri] ?: return 0
        row.putAll(values)
        publishCount++
        sizeWhenPublished = files.getValue(uri).length()
        return 1
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        deleteCount++
        if (selection?.contains(MediaStore.MediaColumns.IS_PENDING) == true &&
            rows[uri]?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) != 1) return 0
        files.remove(uri)?.delete()
        return if (rows.remove(uri) != null) 1 else 0
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String = "application/octet-stream"
}
