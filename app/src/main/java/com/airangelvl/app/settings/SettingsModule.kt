package com.airangelvl.app.settings

import com.airangelvl.core.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {
    @Binds
    abstract fun bindSettingsRepository(repository: InMemorySettingsRepository): SettingsRepository
}
