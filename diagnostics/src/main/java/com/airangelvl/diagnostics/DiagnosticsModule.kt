package com.airangelvl.diagnostics

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {
    @Binds
    abstract fun bindDiagnosticsLogger(impl: FileDiagnosticsLogger): DiagnosticsLogger

    @Binds
    abstract fun bindDiagnosticsStorage(impl: InMemoryDiagnosticsStorage): DiagnosticsStorage
}
