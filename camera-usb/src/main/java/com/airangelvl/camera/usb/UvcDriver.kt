package com.airangelvl.camera.usb

import android.graphics.Bitmap
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * USB Video Class (UVC) driver implementation for endoscope cameras.
 * Supports MJPEG and YUY2 formats as specified in project requirements.
 */
@Singleton
class UvcDriver @Inject constructor(
    private val usbManager: UsbManager,
    private val deviceFilter: UvcDeviceFilter
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var videoInterface: UsbInterface? = null
    private var streamingEndpoint: UsbEndpoint? = null
    private var streamingJob: Job? = null

    private val _frameStream = MutableSharedFlow<UvcFrame>()
    val frameStream: SharedFlow<UvcFrame> = _frameStream.asSharedFlow()

    private var currentFormat: UvcFormat? = null

    suspend fun openDevice(usbDevice: UsbDevice): Boolean {
        return try {
            // Check device compatibility first
            if (!deviceFilter.isCompatibleUvcDevice(usbDevice)) {
                Timber.e("Device is not compatible with UVC: ${usbDevice.deviceName}")
                return false
            }

            device = usbDevice
            connection = usbManager.openDevice(usbDevice)

            if (connection == null) {
                Timber.e("Failed to open USB device connection")
                return false
            }

            // Find UVC video streaming interface
            val videoInterface = findVideoStreamingInterface(usbDevice)
            if (videoInterface == null) {
                Timber.e("No UVC video streaming interface found")
                return false
            }

            // Claim the interface
            val claimed = connection!!.claimInterface(videoInterface, true)
            if (!claimed) {
                Timber.e("Failed to claim UVC interface")
                return false
            }

            this.videoInterface = videoInterface

            // Find streaming endpoint
            streamingEndpoint = findStreamingEndpoint(videoInterface)
            if (streamingEndpoint == null) {
                Timber.e("No UVC streaming endpoint found")
                return false
            }

            Timber.i("UVC device opened successfully: ${usbDevice.deviceName}")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to open UVC device")
            closeDevice()
            false
        }
    }

    fun closeDevice() {
        stopStreaming()

        videoInterface?.let { usbInterface ->
            connection?.releaseInterface(usbInterface)
        }

        connection?.close()
        connection = null
        device = null
        videoInterface = null
        streamingEndpoint = null
        currentFormat = null
    }

    suspend fun startStreaming(format: UvcFormat): Boolean {
        val endpoint = streamingEndpoint ?: return false
        val conn = connection ?: return false

        return try {
            // Set the format (simplified - real implementation would negotiate with device)
            currentFormat = format

            // Start streaming job
            streamingJob = scope.launch {
                streamUvcData(conn, endpoint, format)
            }

            Timber.i("UVC streaming started: ${format.width}x${format.height} ${format.type}")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to start UVC streaming")
            false
        }
    }

    fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        currentFormat = null
        Timber.i("UVC streaming stopped")
    }

    private suspend fun streamUvcData(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        format: UvcFormat
    ) {
        val bufferSize = endpoint.maxPacketSize * 8 // Multiple packets per transfer
        val buffer = ByteArray(bufferSize)
        val frameBuilder = UvcFrameBuilder(format)

        while (scope.isActive) {
            try {
                val bytesRead = connection.bulkTransfer(endpoint, buffer, bufferSize, 1000)

                if (bytesRead > 0) {
                    frameBuilder.addData(buffer, bytesRead)

                    frameBuilder.getCompleteFrame()?.let { frame ->
                        _frameStream.tryEmit(frame)
                    }
                } else if (bytesRead < 0) {
                    Timber.w("USB bulk transfer error: $bytesRead")
                    kotlinx.coroutines.delay(10) // Brief pause before retry
                }
            } catch (e: Exception) {
                Timber.e(e, "Error in UVC streaming loop")
                kotlinx.coroutines.delay(100) // Longer pause on exception
            }
        }
    }

    private fun findVideoStreamingInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)

            // UVC Video Streaming Interface Class
            if (usbInterface.interfaceClass == USB_CLASS_VIDEO &&
                usbInterface.interfaceSubclass == USB_SUBCLASS_VIDEOSTREAMING) {
                return usbInterface
            }
        }
        return null
    }

    private fun findStreamingEndpoint(usbInterface: UsbInterface): UsbEndpoint? {
        for (i in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(i)

            // Look for bulk IN endpoint (video data from device to host)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                endpoint.direction == UsbConstants.USB_DIR_IN) {
                return endpoint
            }
        }
        return null
    }

    fun getSupportedFormats(): List<UvcFormat> {
        val currentDevice = device
        return if (currentDevice != null) {
            // Get device-specific capabilities
            val capabilities = deviceFilter.getDeviceCapabilities(currentDevice)
            capabilities.supportedFormats
        } else {
            // Fallback to common formats
            listOf(
                UvcFormat(640, 480, 30, UvcFormatType.MJPEG),
                UvcFormat(1280, 720, 30, UvcFormatType.MJPEG),
                UvcFormat(640, 480, 30, UvcFormatType.YUY2)
            )
        }
    }

    companion object {
        private const val USB_CLASS_VIDEO = 14
        private const val USB_SUBCLASS_VIDEOSTREAMING = 2

        const val TAG = "UvcDriver"
    }
}

data class UvcFormat(
    val width: Int,
    val height: Int,
    val fps: Int,
    val type: UvcFormatType
)

enum class UvcFormatType {
    MJPEG,
    YUY2
}

data class UvcFrame(
    val data: ByteArray,
    val format: UvcFormat,
    val timestampMs: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UvcFrame
        return data.contentEquals(other.data) && format == other.format
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + format.hashCode()
        return result
    }
}

/**
 * Helper class to build complete frames from USB packet data
 */
private class UvcFrameBuilder(private val format: UvcFormat) {
    private val frameBuffer = mutableListOf<Byte>()
    private var expectingFrame = false

    fun addData(buffer: ByteArray, length: Int) {
        for (i in 0 until length) {
            val byte = buffer[i]
            frameBuffer.add(byte)

            // Simple frame detection for MJPEG (0xFF, 0xD8 start, 0xFF, 0xD9 end)
            if (format.type == UvcFormatType.MJPEG) {
                if (frameBuffer.size >= 2) {
                    val lastTwo = frameBuffer.takeLast(2)
                    if (lastTwo[0] == 0xFF.toByte() && lastTwo[1] == 0xD8.toByte()) {
                        // Start of JPEG frame
                        expectingFrame = true
                        frameBuffer.clear()
                        frameBuffer.add(0xFF.toByte())
                        frameBuffer.add(0xD8.toByte())
                    } else if (expectingFrame && lastTwo[0] == 0xFF.toByte() && lastTwo[1] == 0xD9.toByte()) {
                        // End of JPEG frame - we have a complete frame
                        expectingFrame = false
                        return
                    }
                }
            }
        }
    }

    fun getCompleteFrame(): UvcFrame? {
        if (format.type == UvcFormatType.MJPEG && frameBuffer.size > 100) { // Reasonable minimum size
            val frameData = frameBuffer.toByteArray()
            frameBuffer.clear()
            return UvcFrame(frameData, format)
        }

        if (format.type == UvcFormatType.YUY2) {
            val expectedSize = format.width * format.height * 2 // YUY2 is 2 bytes per pixel
            if (frameBuffer.size >= expectedSize) {
                val frameData = frameBuffer.take(expectedSize).toByteArray()
                frameBuffer.clear()
                return UvcFrame(frameData, format)
            }
        }

        return null
    }
}