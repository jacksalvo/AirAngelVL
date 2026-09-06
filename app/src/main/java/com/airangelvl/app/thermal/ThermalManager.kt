package com.airangelvl.app.thermal

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/** Thermal status is device-specific; it is not a temperature measurement. */
@Singleton
class ThermalManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _thermalState = MutableStateFlow(ThermalState())
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()
    @Volatile private var monitoring = false
    @Volatile private var listener: Any? = null

    @Synchronized
    fun start() {
        if (monitoring) return
        monitoring = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startModern()
    }

    @Synchronized
    fun stop() {
        monitoring = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) stopModern()
        _thermalState.value = ThermalState()
    }

    fun onDestroy() = stop()

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startModern() {
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val callback = object : PowerManager.OnThermalStatusChangedListener {
            override fun onThermalStatusChanged(status: Int) {
                if (monitoring && listener === this) _thermalState.value = stateForStatus(status)
            }
        }
        try {
            manager.addThermalStatusListener(ContextCompat.getMainExecutor(context), callback)
            listener = callback
            _thermalState.value = stateForStatus(manager.currentThermalStatus)
        } catch (error: RuntimeException) {
            listener = callback
            stopModern()
            _thermalState.value = ThermalState()
            Timber.w(error, "Thermal monitoring is unavailable on this device")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun stopModern() {
        val callback = listener as? PowerManager.OnThermalStatusChangedListener ?: return
        listener = null
        try {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.removeThermalStatusListener(callback)
        } catch (error: RuntimeException) {
            Timber.w(error, "Cannot unregister thermal monitoring")
        }
    }

    internal fun stateForStatus(status: Int): ThermalState = when (status) {
        PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT ->
            ThermalState(level = ThermalLevel.NORMAL, monitoringAvailable = true)
        PowerManager.THERMAL_STATUS_MODERATE ->
            ThermalState(level = ThermalLevel.WARM, shouldWarn = true, monitoringAvailable = true)
        PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN ->
            ThermalState(level = ThermalLevel.HOT, shouldWarn = true, shouldThrottle = true, monitoringAvailable = true)
        else -> ThermalState()
    }
}

data class ThermalState(
    val level: ThermalLevel = ThermalLevel.NORMAL,
    val shouldWarn: Boolean = false,
    val shouldThrottle: Boolean = false,
    val monitoringAvailable: Boolean = false,
    val lastUpdatedMs: Long = System.currentTimeMillis()
)

enum class ThermalLevel { NORMAL, WARM, HOT, COOLING }

