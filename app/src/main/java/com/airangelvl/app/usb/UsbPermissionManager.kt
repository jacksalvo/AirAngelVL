package com.airangelvl.app.usb

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface UsbEvent {
    data class Attached(val device: UsbDevice) : UsbEvent
    data class Detached(val device: UsbDevice) : UsbEvent
    data class Permission(val device: UsbDevice, val granted: Boolean) : UsbEvent
    data object ScreenOff : UsbEvent
}

/** Sole owner of USB discovery and permission broadcasts, active while the UI is foreground. */
@Singleton
class UsbPermissionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usbManager: UsbManager
) {
    private val actionPermission = "${context.packageName}.USB_PERMISSION"
    private val mutableEvents = MutableSharedFlow<UsbEvent>(extraBufferCapacity = 16)
    val events = mutableEvents.asSharedFlow()
    private var registered = false
    private val pending = mutableSetOf<Int>()
    private val denied = mutableSetOf<Int>()
    val supportsUsbHost: Boolean get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
    val cameraPermissionGranted: Boolean get() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                mutableEvents.tryEmit(UsbEvent.ScreenOff)
                return
            }
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (isVideoDevice(device)) mutableEvents.tryEmit(UsbEvent.Attached(device))
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    pending.remove(device.deviceId)
                    denied.remove(device.deviceId)
                    mutableEvents.tryEmit(UsbEvent.Detached(device))
                }
                actionPermission -> {
                    if (!pending.remove(device.deviceId)) return
                    val granted = usbManager.hasPermission(device) && cameraPermissionGranted
                    if (!granted) denied.add(device.deviceId)
                    mutableEvents.tryEmit(UsbEvent.Permission(device, granted))
                }
            }
        }
    }

    fun start() {
        if (registered) return
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(actionPermission)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    fun stop() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
        pending.clear()
    }

    fun devices(): List<UsbDevice> = usbManager.deviceList.values.filter(::isVideoDevice).sortedBy { it.deviceName }
    fun hasPermission(device: UsbDevice): Boolean = cameraPermissionGranted && usbManager.hasPermission(device)
    fun wasDenied(device: UsbDevice): Boolean = device.deviceId in denied
    fun allowRetry() { denied.clear(); pending.clear() }

    fun requestPermission(device: UsbDevice) {
        check(registered && cameraPermissionGranted) { "Camera permission is required before connecting USB video." }
        if (device.deviceId in denied || !pending.add(device.deviceId)) return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val callback = PendingIntent.getBroadcast(context, device.deviceId, Intent(actionPermission).setPackage(context.packageName), flags)
        try { usbManager.requestPermission(device, callback) } catch (failure: Exception) {
            pending.remove(device.deviceId)
            throw failure
        }
    }

    private fun isVideoDevice(device: UsbDevice): Boolean =
        device.deviceClass == UsbConstants.USB_CLASS_VIDEO || (0 until device.interfaceCount).any {
            device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO
        }
}
