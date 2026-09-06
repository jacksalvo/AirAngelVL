package com.airangelvl.camera.usb

import com.airangelvl.core.camera.CameraSource
import com.airangelvl.core.camera.CameraSourceFactory
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class UsbCameraSourceFactory @Inject constructor(
    private val negotiators: Provider<UsbNegotiator>
) : CameraSourceFactory {
    override suspend fun create(): CameraSource = UsbCameraSource(negotiators.get())
}
