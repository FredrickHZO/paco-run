package dev.fred.pacorun.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.fred.pacorun.settings.RoutineMode
import dev.fred.pacorun.settings.RunPreferences
import dev.fred.pacorun.ui.UiTags
import dev.fred.pacorun.ui.components.AmbientShapeField
import dev.fred.pacorun.ui.components.DistanceControl
import dev.fred.pacorun.ui.components.DurationControl
import dev.fred.pacorun.ui.components.PacoRunScaffold
import dev.fred.pacorun.ui.components.SpeedRangeControl

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RoutineEditorScreen(
    preferences: RunPreferences,
    onPreferencesChange: (RunPreferences) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val colors = MaterialTheme.colorScheme
    val light = colors.surface.luminance() > 0.5f

    Box(
        Modifier
            .fillMaxSize()
            .background(if (light) colors.surfaceContainerHigh else colors.surfaceContainer),
    ) {
        AmbientShapeField(light)
        PacoRunScaffold(
            title = null,
            status = null,
            primaryAction = {
                Button(
                    onClick = onBack,
                    shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight).testTag(UiTags.saveRoutine),
                ) { Text("Fatto", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
            },
            containerColor = Color.Transparent,
            contentColor = colors.onSurface,
        ) {
            TopAppBar(
                title = { Text("Modifica routine", style = MaterialTheme.typography.titleLargeEmphasized) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                windowInsets = WindowInsets(0.dp),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = colors.onSurface,
                    navigationIconContentColor = colors.onSurface,
                ),
            )
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(36.dp),
            ) {
                if (preferences.mode == RoutineMode.CONSTANT) {
                    ConstantSection(preferences) { onPreferencesChange(preferences.copy(constantRange = it)) }
                } else {
                    InitialConstantSection(preferences.initialConstantKm) { onPreferencesChange(preferences.copy(initialConstantKm = it)) }
                    RunSection(preferences, onPreferencesChange)
                    WalkSection(preferences, onPreferencesChange)
                    RoutineEndSection(preferences, onPreferencesChange)
                }

                VoiceGpsSection(preferences, onPreferencesChange)
            }
        }
    }
}

@Composable
private fun ConstantSection(preferences: RunPreferences, onRangeChange: (dev.fred.pacorun.settings.SpeedRange) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Costante", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = colors.primary)
        SpeedRangeControl(
            label = "Andatura",
            range = preferences.constantRange,
            onChange = onRangeChange,
            roleColor = colors.primary,
            roleOnColor = colors.onPrimary,
            minTag = UiTags.constantMinSpeed,
            maxTag = UiTags.constantMaxSpeed,
        )
    }
}

@Composable
private fun InitialConstantSection(initialConstantKm: Float, onChange: (Float) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Costante iniziale", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = colors.secondary)
        DistanceControl(
            label = "Distanza",
            km = initialConstantKm,
            tag = UiTags.initialDistance,
            roleColor = colors.secondary,
            roleOnColor = colors.onSecondary,
            onChange = onChange,
        )
        if (!initialConstantKm.isFinite() || initialConstantKm < 0f) {
            Text(
                "La distanza iniziale non può essere negativa.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier.testTag(UiTags.initialDistanceError),
            )
        }
    }
}

@Composable
private fun RunSection(preferences: RunPreferences, onPreferencesChange: (RunPreferences) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Corsa", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = colors.primary)
        DurationControl(
            label = "Durata",
            totalSeconds = preferences.runSeconds,
            onTotalSecondsChange = { onPreferencesChange(preferences.copy(runSeconds = it)) },
            roleColor = colors.primary,
            roleOnColor = colors.onPrimary,
            tag = UiTags.runDuration,
        )
        SpeedRangeControl(
            label = "Andatura",
            range = preferences.runRange,
            onChange = { onPreferencesChange(preferences.copy(runRange = it)) },
            roleColor = colors.primary,
            roleOnColor = colors.onPrimary,
            minTag = UiTags.runMinSpeed,
            maxTag = UiTags.runMaxSpeed,
        )
    }
}

@Composable
private fun WalkSection(preferences: RunPreferences, onPreferencesChange: (RunPreferences) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Camminata", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = colors.tertiary)
        DurationControl(
            label = "Durata",
            totalSeconds = preferences.walkSeconds,
            onTotalSecondsChange = { onPreferencesChange(preferences.copy(walkSeconds = it)) },
            roleColor = colors.tertiary,
            roleOnColor = colors.onTertiary,
            tag = UiTags.walkDuration,
        )
        SpeedRangeControl(
            label = "Andatura",
            range = preferences.walkRange,
            onChange = { onPreferencesChange(preferences.copy(walkRange = it)) },
            roleColor = colors.tertiary,
            roleOnColor = colors.onTertiary,
            minTag = UiTags.walkMinSpeed,
            maxTag = UiTags.walkMaxSpeed,
        )
    }
}

@Composable
private fun RoutineEndSection(preferences: RunPreferences, onPreferencesChange: (RunPreferences) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Fine routine", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = colors.secondary)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToggleRow(
                label = "Senza fine",
                checked = preferences.routineUnlimited,
                color = colors.secondary,
                tag = UiTags.routineUnlimited,
            ) { onPreferencesChange(preferences.copy(routineUnlimited = it)) }
            Text(
                "Con una distanza impostata, superato il limite la corsa si ferma e si continua a camminare all'andatura scelta.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondary.copy(alpha = 0.75f),
            )
        }
        AnimatedVisibility(!preferences.routineUnlimited) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DistanceControl(
                    label = "Distanza",
                    km = preferences.routineKm,
                    tag = UiTags.routineDistance,
                    roleColor = colors.secondary,
                    roleOnColor = colors.onSecondary,
                    onChange = { onPreferencesChange(preferences.copy(routineKm = it)) },
                )
                if (!preferences.routineKm.isFinite() || preferences.routineKm <= 0f) {
                    Text(
                        "La distanza routine deve essere positiva.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                        modifier = Modifier.testTag(UiTags.routineDistanceError),
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceGpsSection(preferences: RunPreferences, onPreferencesChange: (RunPreferences) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Voce e GPS", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = colors.secondary)
        ToggleRow(
            label = "Avvisi GPS",
            checked = preferences.gpsCuesEnabled,
            color = colors.secondary,
            tag = UiTags.gpsCues,
            enabled = preferences.cuesEnabled,
        ) { onPreferencesChange(preferences.copy(gpsCuesEnabled = it)) }
        ToggleRow(
            label = "Avvisi vocali",
            checked = preferences.cuesEnabled,
            color = colors.secondary,
            tag = UiTags.cuesEnabled,
        ) { onPreferencesChange(preferences.copy(cuesEnabled = it)) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Volume avvisi · ${(preferences.cueVolume * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondary,
            )
            Slider(
                value = preferences.cueVolume.coerceIn(0f, 1f),
                onValueChange = { onPreferencesChange(preferences.copy(cueVolume = it)) },
                enabled = preferences.cuesEnabled,
                colors = SliderDefaults.colors(
                    thumbColor = colors.secondary,
                    activeTrackColor = colors.secondary,
                    inactiveTrackColor = colors.secondary.copy(alpha = 0.28f),
                ),
                modifier = Modifier.testTag(UiTags.cueVolume),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    color: Color,
    tag: String,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                stateDescription = if (checked) "Attivo" else "Disattivo"
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = color, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

