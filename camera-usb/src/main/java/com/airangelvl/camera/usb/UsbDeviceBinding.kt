package com.airangelvl.camera.usb

import android.hardware.usb.UsbDevice

interface UsbDeviceBinding {
    suspend fun bind(device: UsbDevice)
    suspend fun unbind()
}
