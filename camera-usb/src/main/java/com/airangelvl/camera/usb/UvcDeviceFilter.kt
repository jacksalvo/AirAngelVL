package com.airangelvl.camera.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Filters and validates USB devices for UVC compatibility.
 * Supports endoscopes with various VID:PID combinations as specified in README.
 */
@Singleton
class UvcDeviceFilter @Inject constructor() {

    /**
     * Checks if a USB device is a compatible UVC video device
     */
    fun isCompatibleUvcDevice(device: UsbDevice): Boolean {
        // Check for UVC Video Control Interface
        val hasVideoControlInterface = hasUvcVideoControlInterface(device)
        val hasVideoStreamingInterface = hasUvcVideoStreamingInterface(device)

        if (!hasVideoControlInterface || !hasVideoStreamingInterface) {
            Timber.d("Device ${device.deviceName} missing required UVC interfaces")
            return false
        }

        // Check if device is in known compatible list or meets UVC criteria
        val isKnownCompatible = isKnownCompatibleDevice(device)
        val meetsUvcCriteria = meetsBasicUvcCriteria(device)

        val compatible = isKnownCompatible || meetsUvcCriteria

        if (compatible) {
            Timber.i("Compatible UVC device found: ${device.deviceName} (VID:${device.vendorId.toString(16)}, PID:${device.productId.toString(16)})")
        } else {
            Timber.w("Incompatible device: ${device.deviceName} (VID:${device.vendorId.toString(16)}, PID:${device.productId.toString(16)})")
        }

        return compatible
    }

    /**
     * Gets device capabilities and supported formats
     */
    fun getDeviceCapabilities(device: UsbDevice): UvcDeviceCapabilities {
        val supportedFormats = mutableListOf<UvcFormat>()

        // Add common endoscope formats - in real implementation, this would probe the device
        if (isHighResolutionCapable(device)) {
            supportedFormats.addAll(listOf(
                UvcFormat(1920, 1080, 15, UvcFormatType.MJPEG),
                UvcFormat(1280, 720, 30, UvcFormatType.MJPEG),
                UvcFormat(1280, 720, 15, UvcFormatType.YUY2)
            ))
        }

        // Standard resolution formats
        supportedFormats.addAll(listOf(
            UvcFormat(640, 480, 30, UvcFormatType.MJPEG),
            UvcFormat(640, 480, 15, UvcFormatType.YUY2),
            UvcFormat(320, 240, 30, UvcFormatType.MJPEG)
        ))

        return UvcDeviceCapabilities(
            device = device,
            supportedFormats = supportedFormats,
            supportsAudio = false, // Most endoscopes don't have audio
            maxResolution = if (isHighResolutionCapable(device)) "1920x1080" else "640x480",
            preferredFormat = getPreferredFormat(supportedFormats)
        )
    }

    private fun hasUvcVideoControlInterface(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (usbInterface.interfaceClass == USB_CLASS_VIDEO &&
                usbInterface.interfaceSubclass == USB_SUBCLASS_VIDEOCONTROL) {
                return true
            }
        }
        return false
    }

    private fun hasUvcVideoStreamingInterface(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (usbInterface.interfaceClass == USB_CLASS_VIDEO &&
                usbInterface.interfaceSubclass == USB_SUBCLASS_VIDEOSTREAMING) {
                return true
            }
        }
        return false
    }

    private fun isKnownCompatibleDevice(device: UsbDevice): Boolean {
        // Known compatible endoscope VID:PID combinations
        val knownDevices = mapOf(
            // Common endoscope manufacturers
            0x0c45 to setOf(0x6366, 0x6367, 0x636b), // Microdia
            0x1e4e to setOf(0x0110, 0x0101), // Cubeternet
            0x05e3 to setOf(0x0510, 0x0505), // Genesys Logic
            0x1908 to setOf(0x2311, 0x2310), // GEMBIRD
            0x0bda to setOf(0x58c2, 0x5830), // Realtek
            // Add more as needed based on testing
        )

        val vendorId = device.vendorId
        val productId = device.productId

        return knownDevices[vendorId]?.contains(productId) == true
    }

    private fun meetsBasicUvcCriteria(device: UsbDevice): Boolean {
        // Check for basic UVC compliance indicators
        var hasBulkEndpoint = false
        var hasProperInterface = false

        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)

            if (usbInterface.interfaceClass == USB_CLASS_VIDEO) {
                hasProperInterface = true

                // Check for bulk transfer endpoints
                for (j in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(j)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        hasBulkEndpoint = true
                    }
                }
            }
        }

        return hasProperInterface && hasBulkEndpoint
    }

    private fun isHighResolutionCapable(device: UsbDevice): Boolean {
        // Heuristic: devices with multiple interfaces are more likely to support high resolution
        // Also check for high-speed USB characteristics
        return device.interfaceCount >= 2 || hasHighSpeedEndpoints(device)
    }

    private fun hasHighSpeedEndpoints(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            for (j in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(j)
                // High-speed USB endpoints typically have larger max packet sizes
                if (endpoint.maxPacketSize >= 512) {
                    return true
                }
            }
        }
        return false
    }

    private fun getPreferredFormat(formats: List<UvcFormat>): UvcFormat? {
        // Prefer MJPEG for efficiency, 720p for good quality/performance balance
        return formats
            .filter { it.type == UvcFormatType.MJPEG }
            .find { it.width == 1280 && it.height == 720 }
            ?: formats.firstOrNull { it.type == UvcFormatType.MJPEG }
            ?: formats.firstOrNull()
    }

    companion object {
        private const val USB_CLASS_VIDEO = 14
        private const val USB_SUBCLASS_VIDEOCONTROL = 1
        private const val USB_SUBCLASS_VIDEOSTREAMING = 2
    }
}

data class UvcDeviceCapabilities(
    val device: UsbDevice,
    val supportedFormats: List<UvcFormat>,
    val supportsAudio: Boolean,
    val maxResolution: String,
    val preferredFormat: UvcFormat?
)