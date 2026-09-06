package com.airangelvl.camera.usb

import com.airangelvl.core.camera.CameraSourceFactory
import com.airangelvl.core.di.UsbCamera
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class UsbModule {
    @Binds
    @UsbCamera
    abstract fun bindUsbCameraSourceFactory(factory: UsbCameraSourceFactory): CameraSourceFactory
}
