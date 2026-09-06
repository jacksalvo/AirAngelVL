package com.airangelvl.core.settings

data class ConnectionProfile(
    val name: String,
    val transport: Transport,
    val host: String,
    val port: Int,
    val pathStream: String?,
    val pathSnapshot: String?,
    val auth: AuthCredentials?,
    val orientationMatrix: FloatArray
)

enum class Transport { MJPEG, RTSP }

data class AuthCredentials(
    val username: String,
    val password: String
)

data class CompatFlags(
    val safeMjpegLowRes: Boolean,
    val preferLowResOnThermals: Boolean
)

data class ThermalPolicy(
    val warnCelsius: Int,
    val tripCelsius: Int,
    val reduceBrightnessTo: Float,
    val fpsCap: Int,
    val preferLowRes: Boolean,
    val autoPauseSeconds: Int
)

data class UiPreferences(
    val watermarkEnabled: Boolean,
    val brightnessLocked: Boolean
)

interface SettingsRepository {
    suspend fun getConnectionProfiles(): List<ConnectionProfile>
    suspend fun saveConnectionProfile(profile: ConnectionProfile)
    suspend fun removeConnectionProfile(name: String)
    suspend fun setActiveProfile(name: String?)
    suspend fun getActiveProfile(): ConnectionProfile?
    suspend fun getLastKnownGoodFormats(): LastKnownFormats
    suspend fun updateLastKnownGoodFormats(formats: LastKnownFormats)
    suspend fun getCompatFlags(): CompatFlags
    suspend fun updateCompatFlags(flags: CompatFlags)
    suspend fun getThermalPolicy(): ThermalPolicy
    suspend fun updateThermalPolicy(policy: ThermalPolicy)
    suspend fun getUiPreferences(): UiPreferences
    suspend fun updateUiPreferences(prefs: UiPreferences)
}

data class LastKnownFormats(
    val usb: Map<String, String>,
    val wifi: Map<String, String>
)
