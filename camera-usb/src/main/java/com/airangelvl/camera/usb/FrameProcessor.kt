package com.airangelvl.camera.usb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Processes UVC frames and converts them to Bitmaps for display and capture.
 * Handles both MJPEG and YUY2 formats as specified in requirements.
 */
@Singleton
class FrameProcessor @Inject constructor() {

    /**
     * Converts a UVC frame to a Bitmap for display or capture
     */
    suspend fun processToBitmap(frame: UvcFrame): Bitmap? = withContext(Dispatchers.Default) {
        return@withContext try {
            when (frame.format.type) {
                UvcFormatType.MJPEG -> decodeMjpegFrame(frame)
                UvcFormatType.YUY2 -> decodeYuy2Frame(frame)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to process frame: ${frame.format}")
            null
        }
    }

    /**
     * Decodes MJPEG (Motion JPEG) frame data to Bitmap
     */
    private fun decodeMjpegFrame(frame: UvcFrame): Bitmap? {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size)

            if (bitmap == null) {
                Timber.w("Failed to decode MJPEG frame")
                return null
            }

            // Verify dimensions match expected format
            if (bitmap.width != frame.format.width || bitmap.height != frame.format.height) {
                Timber.d("Frame dimension mismatch: expected ${frame.format.width}x${frame.format.height}, got ${bitmap.width}x${bitmap.height}")
            }

            bitmap
        } catch (e: Exception) {
            Timber.e(e, "Error decoding MJPEG frame")
            null
        }
    }

    /**
     * Decodes YUY2 (YUYV 4:2:2) frame data to Bitmap
     * YUY2 format: Y0 U0 Y1 V0 Y2 U1 Y3 V1 ...
     */
    private fun decodeYuy2Frame(frame: UvcFrame): Bitmap? {
        return try {
            val width = frame.format.width
            val height = frame.format.height
            val yuy2Data = frame.data

            // Expected data size for YUY2 is width * height * 2
            val expectedSize = width * height * 2
            if (yuy2Data.size < expectedSize) {
                Timber.w("YUY2 frame too small: ${yuy2Data.size} < $expectedSize")
                return null
            }

            // Convert YUY2 to RGB
            val rgbData = convertYuy2ToRgb(yuy2Data, width, height)

            // Create bitmap from RGB data
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            bitmap.setPixels(rgbData, 0, width, 0, 0, width, height)

            bitmap
        } catch (e: Exception) {
            Timber.e(e, "Error decoding YUY2 frame")
            null
        }
    }

    /**
     * Converts YUY2 data to RGB pixels
     */
    private fun convertYuy2ToRgb(yuy2Data: ByteArray, width: Int, height: Int): IntArray {
        val rgbData = IntArray(width * height)
        var rgbIndex = 0

        for (i in yuy2Data.indices step 4) {
            if (i + 3 >= yuy2Data.size) break

            // Extract YUY2 components (signed bytes to unsigned ints)
            val y0 = yuy2Data[i].toInt() and 0xFF
            val u = yuy2Data[i + 1].toInt() and 0xFF
            val y1 = yuy2Data[i + 2].toInt() and 0xFF
            val v = yuy2Data[i + 3].toInt() and 0xFF

            // Convert two pixels from YUV to RGB
            rgbData[rgbIndex] = yuvToRgb(y0, u, v)
            if (rgbIndex + 1 < rgbData.size) {
                rgbData[rgbIndex + 1] = yuvToRgb(y1, u, v)
            }

            rgbIndex += 2
        }

        return rgbData
    }

    /**
     * Converts a single YUV pixel to RGB
     * Using ITU-R BT.601 conversion matrix
     */
    private fun yuvToRgb(y: Int, u: Int, v: Int): Int {
        // Normalize values
        val yNorm = y - 16
        val uNorm = u - 128
        val vNorm = v - 128

        // Apply conversion matrix (ITU-R BT.601)
        val r = (1.164 * yNorm + 1.596 * vNorm).toInt()
        val g = (1.164 * yNorm - 0.391 * uNorm - 0.813 * vNorm).toInt()
        val b = (1.164 * yNorm + 2.018 * uNorm).toInt()

        // Clamp to valid RGB range
        val rClamped = r.coerceIn(0, 255)
        val gClamped = g.coerceIn(0, 255)
        val bClamped = b.coerceIn(0, 255)

        // Pack into ARGB integer
        return (0xFF shl 24) or (rClamped shl 16) or (gClamped shl 8) or bClamped
    }

    /**
     * Alternative YUV conversion using Android's YuvImage (may be more efficient)
     */
    private fun convertYuy2ToRgbAlternative(yuy2Data: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            // Convert YUY2 to NV21 format for YuvImage
            val nv21Data = convertYuy2ToNv21(yuy2Data, width, height)

            val yuvImage = YuvImage(nv21Data, ImageFormat.NV21, width, height, null)
            val outputStream = ByteArrayOutputStream()

            yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, outputStream)
            val jpegData = outputStream.toByteArray()

            BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
        } catch (e: Exception) {
            Timber.e(e, "Error in alternative YUV conversion")
            null
        }
    }

    /**
     * Converts YUY2 to NV21 format for Android's YuvImage processing
     */
    private fun convertYuy2ToNv21(yuy2Data: ByteArray, width: Int, height: Int): ByteArray {
        val nv21Size = width * height * 3 / 2
        val nv21Data = ByteArray(nv21Size)

        var yIndex = 0
        var uvIndex = width * height

        for (i in yuy2Data.indices step 4) {
            if (i + 3 >= yuy2Data.size) break
            if (yIndex + 1 >= width * height) break
            if (uvIndex + 1 >= nv21Size) break

            // Extract Y, U, V components
            nv21Data[yIndex] = yuy2Data[i]         // Y0
            nv21Data[yIndex + 1] = yuy2Data[i + 2] // Y1

            // NV21 format interleaves V and U
            nv21Data[uvIndex] = yuy2Data[i + 3]     // V
            nv21Data[uvIndex + 1] = yuy2Data[i + 1] // U

            yIndex += 2
            uvIndex += 2
        }

        return nv21Data
    }

    /**
     * Validates if frame data appears to be valid MJPEG
     */
    fun isValidMjpegFrame(data: ByteArray): Boolean {
        return data.size >= 4 &&
                data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && // JPEG start marker
                data[data.size - 2] == 0xFF.toByte() && data[data.size - 1] == 0xD9.toByte() // JPEG end marker
    }

    /**
     * Validates if frame data size matches expected YUY2 format
     */
    fun isValidYuy2Frame(data: ByteArray, width: Int, height: Int): Boolean {
        val expectedSize = width * height * 2
        return data.size >= expectedSize
    }
}