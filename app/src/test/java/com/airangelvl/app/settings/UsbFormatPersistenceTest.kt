package com.airangelvl.app.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.airangelvl.core.settings.LastKnownFormats
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class UsbFormatPersistenceTest {
    @Test fun `a new repository reads the previously successful USB format`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val formats = LastKnownFormats(usb = mapOf("1234:5678" to "1:640:480:30"), wifi = emptyMap())
        InMemorySettingsRepository(context).updateLastKnownGoodFormats(formats)
        assertEquals(formats, InMemorySettingsRepository(context).getLastKnownGoodFormats())
    }
}
