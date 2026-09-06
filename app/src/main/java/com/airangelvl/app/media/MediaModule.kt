package com.airangelvl.app.media

import android.content.ContentResolver
import android.content.Context
import com.airangelvl.core.media.MediaRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaBindings {
    @Binds
    abstract fun bindMediaRepository(repository: MediaStoreRepository): MediaRepository
}

@Module
@InstallIn(SingletonComponent::class)
object MediaProviders {
    @Provides
    @Singleton
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver = context.contentResolver
}
