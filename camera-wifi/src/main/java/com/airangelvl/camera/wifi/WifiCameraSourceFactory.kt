package com.airangelvl.camera.wifi

import com.airangelvl.core.camera.CameraSource
import com.airangelvl.core.camera.CameraSourceFactory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiCameraSourceFactory @Inject constructor(
    private val jitterBuffer: WifiJitterBuffer
) : CameraSourceFactory {
    override suspend fun create(): CameraSource = WifiCameraSource(jitterBuffer)
}
