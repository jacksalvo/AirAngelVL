package com.airangelvl.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.airangelvl.core.camera.RenderSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.imageSettingsStore by preferencesDataStore(name = "image_settings")

@Singleton
class ImageSettingsRepository internal constructor(
    private val store: DataStore<Preferences>
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.imageSettingsStore)

    val settings: Flow<RenderSettings> = store.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            RenderSettings(
                brightness = prefs[BRIGHTNESS] ?: 0,
                contrast = prefs[CONTRAST] ?: 100,
                saturation = prefs[SATURATION] ?: 100,
                aspectRatio = prefs[ASPECT_RATIO]
            ).normalized()
        }

    suspend fun update(settings: RenderSettings) {
        val normalized = settings.normalized()
        val aspectRatio = normalized.aspectRatio
        store.edit { prefs ->
            prefs[BRIGHTNESS] = normalized.brightness
            prefs[CONTRAST] = normalized.contrast
            prefs[SATURATION] = normalized.saturation
            if (aspectRatio == null) prefs.remove(ASPECT_RATIO)
            else prefs[ASPECT_RATIO] = aspectRatio
        }
    }

    private companion object {
        val BRIGHTNESS = intPreferencesKey("brightness")
        val CONTRAST = intPreferencesKey("contrast")
        val SATURATION = intPreferencesKey("saturation")
        val ASPECT_RATIO = doublePreferencesKey("aspect_ratio")
    }
}

