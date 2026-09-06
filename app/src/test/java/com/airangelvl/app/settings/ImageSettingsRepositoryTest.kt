package com.airangelvl.app.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.airangelvl.core.camera.RenderSettings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageSettingsRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `tuning survives a new datastore instance without persisting display rotation`() = runTest {
        val file = File(temporary.root, "settings.preferences_pb")
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob + Dispatchers.IO)
        val first = ImageSettingsRepository(PreferenceDataStoreFactory.create(scope = firstScope) { file })
        val requested = RenderSettings(brightness = 25, contrast = 130, saturation = 80, aspectRatio = 4.0 / 3.0, rotationDegrees = 90)
        first.update(requested)
        firstJob.cancelAndJoin()
        assertTrue(file.length() > 0)

        val secondJob = SupervisorJob()
        val secondScope = CoroutineScope(secondJob + Dispatchers.IO)
        try {
            val restored = ImageSettingsRepository(PreferenceDataStoreFactory.create(scope = secondScope) { file })
            assertEquals(requested.copy(rotationDegrees = 0), restored.settings.first())
            restored.update(RenderSettings())
            assertEquals(RenderSettings(), restored.settings.first())
        } finally {
            secondJob.cancelAndJoin()
        }
    }
}
