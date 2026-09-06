package com.airangelvl.diagnostics

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

interface DiagnosticsLogger {
    suspend fun log(event: String)
}

@Singleton
class FileDiagnosticsLogger @Inject constructor(
    private val storage: DiagnosticsStorage
) : DiagnosticsLogger {
    private val mutex = Mutex()

    override suspend fun log(event: String) {
        try {
            withContext(Dispatchers.IO) {
                mutex.withLock { storage.appendLine(event) }
                runCatching { Timber.tag("Diagnostics").d(event) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Diagnostics must never break camera operation when storage is unavailable.
            runCatching { Timber.tag("Diagnostics").w(error, "Cannot retain diagnostic event") }
        }
    }
}

interface DiagnosticsStorage {
    suspend fun appendLine(line: String)
}

@Singleton
class InMemoryDiagnosticsStorage @Inject constructor() : DiagnosticsStorage {
    private val buffer = ArrayDeque<String>()
    private val lock = Any()

    override suspend fun appendLine(line: String) {
        synchronized(lock) {
            if (buffer.size >= MAX_BUFFER) buffer.removeFirst()
            buffer.add(line)
        }
    }

    fun dump(): List<String> = synchronized(lock) { buffer.toList() }

    private companion object {
        const val MAX_BUFFER = 1_000
    }
}
