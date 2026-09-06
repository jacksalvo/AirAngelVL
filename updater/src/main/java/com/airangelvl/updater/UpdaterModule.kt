package com.airangelvl.updater

import com.airangelvl.core.updater.UpdateRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdaterModule {
    @Binds
    abstract fun bindUpdateRepository(impl: DefaultUpdateRepository): UpdateRepository
}
