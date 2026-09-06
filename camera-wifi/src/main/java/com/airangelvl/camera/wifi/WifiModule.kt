package com.airangelvl.camera.wifi

import com.airangelvl.core.camera.CameraSourceFactory
import com.airangelvl.core.di.WifiCamera
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class WifiModule {
    @Binds
    @WifiCamera
    abstract fun bindWifiCameraSourceFactory(factory: WifiCameraSourceFactory): CameraSourceFactory
}
