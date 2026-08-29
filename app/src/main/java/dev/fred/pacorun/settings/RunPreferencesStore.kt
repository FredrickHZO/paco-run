package dev.fred.pacorun.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.runPreferencesDataStore by preferencesDataStore(name = "run_preferences")

class RunPreferencesStore(private val context: Context) {
    val preferences: Flow<RunPreferences> = context.runPreferencesDataStore.data.map { values ->
        val defaults = RunPreferences()
        RunPreferences(
            minKmh = values[Keys.minKmh] ?: defaults.minKmh,
            maxKmh = values[Keys.maxKmh] ?: defaults.maxKmh,
            confirmationSamples = values[Keys.confirmationSamples] ?: defaults.confirmationSamples,
            repeatMillis = values[Keys.repeatMillis] ?: defaults.repeatMillis,
            gpsCuesEnabled = values[Keys.gpsCuesEnabled] ?: defaults.gpsCuesEnabled,
            cueVolume = values[Keys.cueVolume] ?: defaults.cueVolume,
            dynamicColor = values[Keys.dynamicColor] ?: defaults.dynamicColor,
        )
    }

    suspend fun setSpeedRange(minKmh: Float, maxKmh: Float) {
        context.runPreferencesDataStore.edit {
            it[Keys.minKmh] = minKmh
            it[Keys.maxKmh] = maxKmh
        }
    }

    suspend fun setConfirmationSamples(value: Int) {
        context.runPreferencesDataStore.edit { it[Keys.confirmationSamples] = value.coerceAtLeast(1) }
    }

    suspend fun setRepeatMillis(value: Long) {
        context.runPreferencesDataStore.edit { it[Keys.repeatMillis] = value.coerceAtLeast(1_000L) }
    }

    suspend fun setGpsCuesEnabled(value: Boolean) {
        context.runPreferencesDataStore.edit { it[Keys.gpsCuesEnabled] = value }
    }

    suspend fun setCueVolume(value: Float) {
        context.runPreferencesDataStore.edit { it[Keys.cueVolume] = value.coerceIn(0f, 1f) }
    }

    suspend fun setDynamicColor(value: Boolean) {
        context.runPreferencesDataStore.edit { it[Keys.dynamicColor] = value }
    }

    private object Keys {
        val minKmh = floatPreferencesKey("min_kmh")
        val maxKmh = floatPreferencesKey("max_kmh")
        val confirmationSamples = intPreferencesKey("confirmation_samples")
        val repeatMillis = longPreferencesKey("repeat_millis")
        val gpsCuesEnabled = booleanPreferencesKey("gps_cues_enabled")
        val cueVolume = floatPreferencesKey("cue_volume")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
    }
}
