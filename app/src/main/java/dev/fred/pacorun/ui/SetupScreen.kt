package dev.fred.pacorun.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.fred.pacorun.settings.RoutineMode
import dev.fred.pacorun.settings.RunPreferences
import dev.fred.pacorun.settings.SpeedRange
import java.util.Locale

object UiTags {
    const val constantMode = "mode-constant"
    const val walkRunMode = "mode-walk-run"
    const val constantMinSpeed = "constant-min-speed"
    const val constantMaxSpeed = "constant-max-speed"
    const val constantRangeError = "constant-range-error"
    const val runRangeError = "run-range-error"
    const val walkRangeError = "walk-range-error"
    const val initialDistance = "initial-distance"
    const val initialDistanceError = "initial-distance-error"
    const val runMinutes = "run-minutes"
    const val runSeconds = "run-seconds"
    const val runDurationError = "run-duration-error"
    const val runMinSpeed = "run-min-speed"
    const val runMaxSpeed = "run-max-speed"
    const val walkMinutes = "walk-minutes"
    const val walkSeconds = "walk-seconds"
    const val walkDurationError = "walk-duration-error"
    const val walkUsesConstant = "walk-uses-constant"
    const val walkMinSpeed = "walk-min-speed"
    const val walkMaxSpeed = "walk-max-speed"
    const val routineUnlimited = "routine-unlimited"
    const val routineDistance = "routine-distance"
    const val routineDistanceError = "routine-distance-error"
    const val gpsCues = "gps-cues"
    const val cueVolume = "cue-volume"
    const val cueVolumeError = "cue-volume-error"
    const val repeatSeconds = "repeat-seconds"
    const val confirmationSamples = "confirmation-samples"
    const val dynamicColor = "dynamic-color"
    const val start = "start"
    const val editRoutine = "edit-routine"
    const val activePhase = "active-phase"
    const val activeSpeed = "active-speed"
    const val activeRemaining = "active-remaining"
    const val activeTotalDistance = "active-total-distance"
    const val activeRoutineDistance = "active-routine-distance"
    const val activeGps = "active-gps"
    const val stop = "stop"
}

@Composable
fun SetupScreen(
    preferences: RunPreferences,
    onPreferencesChange: (RunPreferences) -> Unit,
) {
    var volumeInputValid by remember(preferences.cueVolume) {
        mutableStateOf(preferences.cueVolume.isFinite() && preferences.cueVolume in 0f..1f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Modalità")
        ModeChoice("Andamento costante", preferences.mode == RoutineMode.CONSTANT, UiTags.constantMode) {
            onPreferencesChange(preferences.copy(mode = RoutineMode.CONSTANT))
        }
        ModeChoice("Walk–Run", preferences.mode == RoutineMode.WALK_RUN, UiTags.walkRunMode) {
            onPreferencesChange(preferences.copy(mode = RoutineMode.WALK_RUN))
        }

        Text("Soglie costanti (km/h)")
        SpeedFields(
            range = preferences.constantRange,
            minTag = UiTags.constantMinSpeed,
            maxTag = UiTags.constantMaxSpeed,
            onChange = { onPreferencesChange(preferences.copy(constantRange = it)) },
        )
        if (!preferences.constantRange.isValid) {
            Text("Il minimo deve essere minore del massimo.", Modifier.testTag(UiTags.constantRangeError))
        }

        if (preferences.mode == RoutineMode.WALK_RUN) {
            WalkRunFields(preferences, onPreferencesChange)
        }

        HorizontalDivider()
        Text("Audio e GPS")
        Toggle("Avvisi GPS", preferences.gpsCuesEnabled, UiTags.gpsCues) {
            onPreferencesChange(preferences.copy(gpsCuesEnabled = it))
        }
        DecimalField(
            label = "Volume avvisi (0–1)",
            value = preferences.cueVolume,
            tag = UiTags.cueVolume,
            isError = !volumeInputValid,
        ) {
            volumeInputValid = it.isFinite() && it in 0f..1f
            if (volumeInputValid) onPreferencesChange(preferences.copy(cueVolume = it))
        }
        if (!volumeInputValid) Text("Il volume deve essere un numero tra 0 e 1.", Modifier.testTag(UiTags.cueVolumeError))
        IntegerField("Ripeti avviso ogni (secondi)", (preferences.repeatMillis / 1_000L).toInt(), UiTags.repeatSeconds) {
            onPreferencesChange(preferences.copy(repeatMillis = it.coerceAtLeast(1) * 1_000L))
        }
        IntegerField("Letture di conferma", preferences.confirmationSamples, UiTags.confirmationSamples) {
            onPreferencesChange(preferences.copy(confirmationSamples = it.coerceAtLeast(1)))
        }
        Toggle("Colori dinamici", preferences.dynamicColor, UiTags.dynamicColor) {
            onPreferencesChange(preferences.copy(dynamicColor = it))
        }

        if (!preferences.isValid || !volumeInputValid) Text("Correggi i campi evidenziati.")
    }
}

@Composable
private fun WalkRunFields(preferences: RunPreferences, onPreferencesChange: (RunPreferences) -> Unit) {
    Text("Walk–Run")
    DecimalField(
        label = "Distanza costante iniziale (km)",
        value = preferences.initialConstantKm,
        tag = UiTags.initialDistance,
        isError = !preferences.initialConstantKm.isFinite() || preferences.initialConstantKm < 0f,
    ) {
        onPreferencesChange(preferences.copy(initialConstantKm = it))
    }
    if (!preferences.initialConstantKm.isFinite() || preferences.initialConstantKm < 0f) {
        Text("La distanza iniziale non può essere negativa.", Modifier.testTag(UiTags.initialDistanceError))
    }
    DurationFields("Run", preferences.runSeconds, UiTags.runMinutes, UiTags.runSeconds, UiTags.runDurationError) {
        onPreferencesChange(preferences.copy(runSeconds = it))
    }
    Text("Soglie Run (km/h)")
    SpeedFields(preferences.runRange, UiTags.runMinSpeed, UiTags.runMaxSpeed, UiTags.runRangeError) {
        onPreferencesChange(preferences.copy(runRange = it))
    }
    DurationFields("Walk", preferences.walkSeconds, UiTags.walkMinutes, UiTags.walkSeconds, UiTags.walkDurationError) {
        onPreferencesChange(preferences.copy(walkSeconds = it))
    }
    Toggle("Usa le soglie costanti per Walk", preferences.walkUsesConstantRange, UiTags.walkUsesConstant) {
        onPreferencesChange(preferences.copy(walkUsesConstantRange = it))
    }
    if (!preferences.walkUsesConstantRange) {
        Text("Soglie Walk (km/h)")
        SpeedFields(preferences.walkRange, UiTags.walkMinSpeed, UiTags.walkMaxSpeed, UiTags.walkRangeError) {
            onPreferencesChange(preferences.copy(walkRange = it))
        }
    }
    Toggle("Routine senza fine", preferences.routineUnlimited, UiTags.routineUnlimited) {
        onPreferencesChange(preferences.copy(routineUnlimited = it))
    }
    if (!preferences.routineUnlimited) {
        DecimalField(
            label = "Distanza routine (km)",
            value = preferences.routineKm,
            tag = UiTags.routineDistance,
            isError = !preferences.routineKm.isFinite() || preferences.routineKm <= 0f,
        ) {
            onPreferencesChange(preferences.copy(routineKm = it))
        }
        if (!preferences.routineKm.isFinite() || preferences.routineKm <= 0f) {
            Text("La distanza routine deve essere positiva.", Modifier.testTag(UiTags.routineDistanceError))
        }
    }
}

@Composable
private fun ModeChoice(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick, modifier = Modifier.testTag(tag))
        Text(label)
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, tag: String, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable { onCheckedChange(!checked) }
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                stateDescription = if (checked) "Attivo" else "Disattivo"
            },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SpeedFields(
    range: SpeedRange,
    minTag: String,
    maxTag: String,
    errorTag: String? = null,
    onChange: (SpeedRange) -> Unit,
) {
    DecimalField("Minimo", range.minKmh, minTag, isError = !range.isValid) { onChange(range.copy(minKmh = it)) }
    DecimalField("Massimo", range.maxKmh, maxTag, isError = !range.isValid) { onChange(range.copy(maxKmh = it)) }
    if (errorTag != null && !range.isValid) {
        Text("Il minimo deve essere minore del massimo.", Modifier.testTag(errorTag))
    }
}

@Composable
private fun DurationFields(
    label: String,
    totalSeconds: Int,
    minutesTag: String,
    secondsTag: String,
    errorTag: String,
    onChange: (Int) -> Unit,
) {
    IntegerField("$label minuti", totalSeconds / 60, minutesTag, isError = totalSeconds < 1) { minutes ->
        onChange(minutes * 60 + totalSeconds.mod(60))
    }
    IntegerField("$label secondi", totalSeconds.mod(60), secondsTag, isError = totalSeconds < 1) { seconds ->
        onChange((totalSeconds / 60) * 60 + seconds)
    }
    if (totalSeconds < 1) Text("La durata deve essere almeno un secondo.", Modifier.testTag(errorTag))
}

@Composable
private fun DecimalField(
    label: String,
    value: Float,
    tag: String,
    isError: Boolean = false,
    onValueChange: (Float) -> Unit,
) {
    var text by remember(value) { mutableStateOf(numberText(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            onValueChange(input.toFloatOrNull() ?: Float.NaN)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag(tag),
        singleLine = true,
        isError = isError,
    )
}

@Composable
private fun IntegerField(
    label: String,
    value: Int,
    tag: String,
    isError: Boolean = false,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            onValueChange(input.toIntOrNull() ?: 0)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag),
        singleLine = true,
        isError = isError,
    )
}

private fun numberText(value: Float): String = if (value.isFinite()) {
    String.format(Locale.ROOT, "%s", value)
} else {
    ""
}
