package dev.fred.pacorun.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.runPreferencesDataStore by preferencesDataStore(name = "run_preferences")

class RunPreferencesStore(private val context: Context) {
    val preferences: Flow<RunPreferences> = context.runPreferencesDataStore.data.map { values ->
        decode(values)
    }

    suspend fun setSpeedRange(minKmh: Float, maxKmh: Float) {
        update {
            it.copy(constantRange = SpeedRange(minKmh, maxKmh))
        }
    }

    suspend fun setConfirmationSamples(value: Int) {
        update { it.copy(confirmationSamples = value.coerceAtLeast(1)) }
    }

    suspend fun setRepeatMillis(value: Long) {
        update { it.copy(repeatMillis = value.coerceAtLeast(1_000L)) }
    }

    suspend fun setGpsCuesEnabled(value: Boolean) {
        update { it.copy(gpsCuesEnabled = value) }
    }

    suspend fun setCueVolume(value: Float) {
        update { it.copy(cueVolume = value.coerceIn(0f, 1f)) }
    }

    suspend fun setDynamicColor(value: Boolean) {
        update { it.copy(dynamicColor = value) }
    }

    suspend fun update(transform: (RunPreferences) -> RunPreferences) {
        context.runPreferencesDataStore.edit { values ->
            write(values, transform(decode(values)))
        }
    }

    private fun decode(values: androidx.datastore.preferences.core.Preferences): RunPreferences {
        val defaults = RunPreferences()
        val constantMin = values[Keys.constantMinKmh] ?: values[Keys.minKmh] ?: defaults.constantRange.minKmh
        val constantMax = values[Keys.constantMaxKmh] ?: values[Keys.maxKmh] ?: defaults.constantRange.maxKmh
        return RunPreferences(
            mode = values[Keys.mode]?.let { value ->
                runCatching { RoutineMode.valueOf(value) }.getOrDefault(defaults.mode)
            } ?: defaults.mode,
            constantRange = SpeedRange(constantMin, constantMax),
            runRange = SpeedRange(
                values[Keys.runMinKmh] ?: defaults.runRange.minKmh,
                values[Keys.runMaxKmh] ?: defaults.runRange.maxKmh,
            ),
            walkUsesConstantRange = values[Keys.walkUsesConstantRange] ?: defaults.walkUsesConstantRange,
            walkRange = SpeedRange(
                values[Keys.walkMinKmh] ?: defaults.walkRange.minKmh,
                values[Keys.walkMaxKmh] ?: defaults.walkRange.maxKmh,
            ),
            initialConstantKm = values[Keys.initialConstantKm] ?: defaults.initialConstantKm,
            runSeconds = values[Keys.runSeconds] ?: defaults.runSeconds,
            walkSeconds = values[Keys.walkSeconds] ?: defaults.walkSeconds,
            confirmationSamples = values[Keys.confirmationSamples] ?: defaults.confirmationSamples,
            repeatMillis = values[Keys.repeatMillis] ?: defaults.repeatMillis,
            gpsCuesEnabled = values[Keys.gpsCuesEnabled] ?: defaults.gpsCuesEnabled,
            cueVolume = values[Keys.cueVolume] ?: defaults.cueVolume,
            dynamicColor = values[Keys.dynamicColor] ?: defaults.dynamicColor,
        )
    }

    private fun write(values: androidx.datastore.preferences.core.MutablePreferences, preferences: RunPreferences) {
        values[Keys.mode] = preferences.mode.name
        values[Keys.constantMinKmh] = preferences.constantRange.minKmh
        values[Keys.constantMaxKmh] = preferences.constantRange.maxKmh
        values[Keys.runMinKmh] = preferences.runRange.minKmh
        values[Keys.runMaxKmh] = preferences.runRange.maxKmh
        values[Keys.walkUsesConstantRange] = preferences.walkUsesConstantRange
        values[Keys.walkMinKmh] = preferences.walkRange.minKmh
        values[Keys.walkMaxKmh] = preferences.walkRange.maxKmh
        values[Keys.initialConstantKm] = preferences.initialConstantKm
        values[Keys.runSeconds] = preferences.runSeconds
        values[Keys.walkSeconds] = preferences.walkSeconds
        values[Keys.minKmh] = preferences.constantRange.minKmh
        values[Keys.maxKmh] = preferences.constantRange.maxKmh
        values[Keys.confirmationSamples] = preferences.confirmationSamples
        values[Keys.repeatMillis] = preferences.repeatMillis
        values[Keys.gpsCuesEnabled] = preferences.gpsCuesEnabled
        values[Keys.cueVolume] = preferences.cueVolume
        values[Keys.dynamicColor] = preferences.dynamicColor
    }

    private object Keys {
        val mode = stringPreferencesKey("routine_mode")
        val constantMinKmh = floatPreferencesKey("constant_min_kmh")
        val constantMaxKmh = floatPreferencesKey("constant_max_kmh")
        val runMinKmh = floatPreferencesKey("run_min_kmh")
        val runMaxKmh = floatPreferencesKey("run_max_kmh")
        val walkUsesConstantRange = booleanPreferencesKey("walk_uses_constant_range")
        val walkMinKmh = floatPreferencesKey("walk_min_kmh")
        val walkMaxKmh = floatPreferencesKey("walk_max_kmh")
        val initialConstantKm = floatPreferencesKey("initial_constant_km")
        val runSeconds = intPreferencesKey("run_seconds")
        val walkSeconds = intPreferencesKey("walk_seconds")
        val minKmh = floatPreferencesKey("min_kmh")
        val maxKmh = floatPreferencesKey("max_kmh")
        val confirmationSamples = intPreferencesKey("confirmation_samples")
        val repeatMillis = longPreferencesKey("repeat_millis")
        val gpsCuesEnabled = booleanPreferencesKey("gps_cues_enabled")
        val cueVolume = floatPreferencesKey("cue_volume")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
    }
}
