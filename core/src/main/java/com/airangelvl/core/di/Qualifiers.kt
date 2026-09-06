package com.airangelvl.core.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UsbCamera

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WifiCamera
