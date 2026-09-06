package com.airangelvl.app.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import org.json.JSONObject
import com.airangelvl.core.settings.CompatFlags
import com.airangelvl.core.settings.ConnectionProfile
import com.airangelvl.core.settings.LastKnownFormats
import com.airangelvl.core.settings.SettingsRepository
import com.airangelvl.core.settings.ThermalPolicy
import com.airangelvl.core.settings.UiPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Context.cameraFormatsStore by preferencesDataStore(name = "camera_formats")

@Singleton
class InMemorySettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private val mutex = Mutex()
    private val profiles = LinkedHashMap<String, ConnectionProfile>()
    private var activeProfileName: String? = null
    private var formats: LastKnownFormats = LastKnownFormats(emptyMap(), emptyMap())
    private var compatFlags: CompatFlags = CompatFlags(safeMjpegLowRes = false, preferLowResOnThermals = true)
    private var thermalPolicy: ThermalPolicy = ThermalPolicy(
        warnCelsius = 44,
        tripCelsius = 48,
        reduceBrightnessTo = 0.7f,
        fpsCap = 24,
        preferLowRes = true,
        autoPauseSeconds = 120
    )
    private var uiPreferences: UiPreferences = UiPreferences(watermarkEnabled = true, brightnessLocked = true)

    override suspend fun getConnectionProfiles(): List<ConnectionProfile> = withLock { profiles.values.toList() }

    override suspend fun saveConnectionProfile(profile: ConnectionProfile) = withLock {
        profiles[profile.name] = profile
    }

    override suspend fun removeConnectionProfile(name: String) = withLock {
        profiles.remove(name)
        if (activeProfileName == name) activeProfileName = null
    }

    override suspend fun setActiveProfile(name: String?) = withLock {
        activeProfileName = name
    }

    override suspend fun getActiveProfile(): ConnectionProfile? = withLock {
        activeProfileName?.let { profiles[it] }
    }

    override suspend fun getLastKnownGoodFormats(): LastKnownFormats = withLock {
        val prefs = context.cameraFormatsStore.data.catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }.first()
        val encoded = prefs[USB_FORMATS]
        val usb = if (encoded == null) emptyMap() else runCatching {
            val json = JSONObject(encoded)
            json.keys().asSequence().associateWith { key -> json.getString(key) }
        }.getOrDefault(emptyMap())
        formats.copy(usb = usb)
    }

    override suspend fun updateLastKnownGoodFormats(formats: LastKnownFormats) = withLock {
        context.cameraFormatsStore.edit { prefs -> prefs[USB_FORMATS] = JSONObject(formats.usb).toString() }
        this.formats = formats
    }

    override suspend fun getCompatFlags(): CompatFlags = withLock { compatFlags }

    override suspend fun updateCompatFlags(flags: CompatFlags) = withLock {
        compatFlags = flags
    }

    override suspend fun getThermalPolicy(): ThermalPolicy = withLock { thermalPolicy }

    override suspend fun updateThermalPolicy(policy: ThermalPolicy) = withLock {
        thermalPolicy = policy
    }

    override suspend fun getUiPreferences(): UiPreferences = withLock { uiPreferences }

    override suspend fun updateUiPreferences(prefs: UiPreferences) = withLock {
        uiPreferences = prefs
    }

    private suspend fun <T> withLock(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private companion object {
        val USB_FORMATS = stringPreferencesKey("usb_formats")
    }
}

