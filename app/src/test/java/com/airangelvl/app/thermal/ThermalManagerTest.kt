package com.airangelvl.app.thermal

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class ThermalManagerTest {
    @Test fun `listener reports actual status and is registered and removed once`() {
        val power = mockk<PowerManager>(relaxed = true)
        val callback = slot<PowerManager.OnThermalStatusChangedListener>()
        every { power.addThermalStatusListener(any(), capture(callback)) } returns Unit
        every { power.currentThermalStatus } returns PowerManager.THERMAL_STATUS_MODERATE
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getSystemService(name: String): Any? = if (name == Context.POWER_SERVICE) power else super.getSystemService(name)
        }
        val manager = ThermalManager(context)
        manager.start()
        manager.start()
        assertTrue(manager.thermalState.value.monitoringAvailable)
        assertEquals(ThermalLevel.WARM, manager.thermalState.value.level)
        callback.captured.onThermalStatusChanged(PowerManager.THERMAL_STATUS_SEVERE)
        assertTrue(manager.thermalState.value.shouldThrottle)
        manager.stop()
        manager.stop()
        callback.captured.onThermalStatusChanged(PowerManager.THERMAL_STATUS_SEVERE)
        assertFalse(manager.thermalState.value.monitoringAvailable)
        assertFalse(manager.thermalState.value.shouldThrottle)
        verify(exactly = 1) { power.addThermalStatusListener(any(), any()) }
        verify(exactly = 1) { power.removeThermalStatusListener(callback.captured) }
    }

    @Test
    @Config(sdk = [24, 28])
    fun `older Android reports monitoring unavailable without fabricated temperature`() {
        val manager = ThermalManager(ApplicationProvider.getApplicationContext())
        manager.start()
        assertFalse(manager.thermalState.value.monitoringAvailable)
        assertFalse(manager.thermalState.value.shouldThrottle)
        manager.stop()
    }
}
