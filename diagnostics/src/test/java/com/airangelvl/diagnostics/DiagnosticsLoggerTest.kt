package com.airangelvl.diagnostics

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsLoggerTest {
    @Test fun `concurrent logging retains at most 1000 complete entries`() = runTest {
        val storage = InMemoryDiagnosticsStorage()
        val logger = FileDiagnosticsLogger(storage)
        coroutineScope {
            repeat(2000) { index -> launch(Dispatchers.Default) { logger.log("event-$index") } }
        }
        val entries = storage.dump()
        assertEquals(1000, entries.size)
        assertEquals(1000, entries.toSet().size)
        assertTrue(entries.all { it.startsWith("event-") })
    }

    @Test fun `diagnostic storage failure does not escape to camera caller`() = runTest {
        val logger = FileDiagnosticsLogger(object : DiagnosticsStorage {
            override suspend fun appendLine(line: String) { throw IOException("full") }
        })
        logger.log("capture-start")
    }

    @Test fun `logger serializes a suspending storage implementation`() = runTest {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val logger = FileDiagnosticsLogger(object : DiagnosticsStorage {
            override suspend fun appendLine(line: String) {
                val count = active.incrementAndGet()
                maxActive.updateAndGet { maxOf(it, count) }
                delay(1)
                active.decrementAndGet()
            }
        })
        coroutineScope { repeat(20) { launch(Dispatchers.Default) { logger.log("entry") } } }
        assertEquals(1, maxActive.get())
    }
}
