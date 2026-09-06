package com.airangelvl.core.camera

fun interface CameraSourceFactory {
    suspend fun create(): CameraSource
}
