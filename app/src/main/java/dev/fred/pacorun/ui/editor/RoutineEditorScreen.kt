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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    state: RoutineEditorState,
    onDraftChange: (RunPreferences) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val draft = state.draft
    val isDirty = draft != state.original
    var showDiscardDialog by remember { mutableStateOf(false) }
    val requestBack = { if (isDirty) showDiscardDialog = true else onBack() }
    BackHandler(onBack = requestBack)

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
                    onClick = onSave,
                    enabled = state.canSave,
                    shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight).testTag(UiTags.saveRoutine),
                ) { Text("Salva routine", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
            },
            containerColor = Color.Transparent,
            contentColor = colors.onSurface,
        ) {
            TopAppBar(
                title = { Text("Modifica routine", style = MaterialTheme.typography.titleLargeEmphasized) },
                navigationIcon = {
                    IconButton(onClick = requestBack) {
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
                if (draft.mode == RoutineMode.CONSTANT) {
                    ConstantSection(draft) { onDraftChange(draft.copy(constantRange = it)) }
                } else {
                    InitialConstantSection(draft.initialConstantKm) { onDraftChange(draft.copy(initialConstantKm = it)) }
                    RunSection(draft, onDraftChange)
                    WalkSection(draft, onDraftChange)
                    RoutineEndSection(draft, onDraftChange)
                }

                VoiceGpsSection(draft, onDraftChange)
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Modifiche non salvate") },
            text = { Text("Vuoi continuare a modificare la routine o scartare le modifiche?") },
            confirmButton = {
                TextButton(
                    onClick = { showDiscardDialog = false },
                    modifier = Modifier.testTag(UiTags.discardDialogContinue),
                ) { Text("Continua a modificare") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDiscardDialog = false; onBack() },
                    modifier = Modifier.testTag(UiTags.discardDialogDiscard),
                ) { Text("Scarta") }
            },
        )
    }
}

@Composable
private fun ConstantSection(draft: RunPreferences, onRangeChange: (dev.fred.pacorun.settings.SpeedRange) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Costante", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = colors.primary)
        SpeedRangeControl(
            label = "Andatura",
            range = draft.constantRange,
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
            allowZero = true,
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
private fun RunSection(draft: RunPreferences, onDraftChange: (RunPreferences) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Corsa", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = colors.primary)
        DurationControl(
            label = "Durata",
            totalSeconds = draft.runSeconds,
            onTotalSecondsChange = { onDraftChange(draft.copy(runSeconds = it)) },
            roleColor = colors.primary,
            roleOnColor = colors.onPrimary,
            tag = UiTags.runDuration,
        )
        SpeedRangeControl(
            label = "Andatura",
            range = draft.runRange,
            onChange = { onDraftChange(draft.copy(runRange = it)) },
            roleColor = colors.primary,
            roleOnColor = colors.onPrimary,
            minTag = UiTags.runMinSpeed,
            maxTag = UiTags.runMaxSpeed,
        )
    }
}

@Composable
private fun WalkSection(draft: RunPreferences, onDraftChange: (RunPreferences) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Camminata", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = colors.tertiary)
        DurationControl(
            label = "Durata",
            totalSeconds = draft.walkSeconds,
            onTotalSecondsChange = { onDraftChange(draft.copy(walkSeconds = it)) },
            roleColor = colors.tertiary,
            roleOnColor = colors.onTertiary,
            tag = UiTags.walkDuration,
        )
        SpeedRangeControl(
            label = "Andatura",
            range = if (draft.walkUsesConstantRange) draft.constantRange else draft.walkRange,
            onChange = { onDraftChange(draft.copy(walkUsesConstantRange = false, walkRange = it)) },
            roleColor = colors.tertiary,
            roleOnColor = colors.onTertiary,
            minTag = UiTags.walkMinSpeed,
            maxTag = UiTags.walkMaxSpeed,
        )
    }
}

@Composable
private fun RoutineEndSection(draft: RunPreferences, onDraftChange: (RunPreferences) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Fine routine", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = colors.secondary)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToggleRow(
                label = "Senza fine",
                checked = draft.routineUnlimited,
                color = colors.secondary,
                tag = UiTags.routineUnlimited,
            ) { onDraftChange(draft.copy(routineUnlimited = it)) }
            Text(
                "Con una distanza impostata, superato il limite la corsa si ferma e si continua a camminare all'andatura scelta.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondary.copy(alpha = 0.75f),
            )
        }
        AnimatedVisibility(!draft.routineUnlimited) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DistanceControl(
                    label = "Distanza",
                    km = draft.routineKm,
                    tag = UiTags.routineDistance,
                    roleColor = colors.secondary,
                    roleOnColor = colors.onSecondary,
                    allowZero = false,
                    onChange = { onDraftChange(draft.copy(routineKm = it)) },
                )
                if (!draft.routineKm.isFinite() || draft.routineKm <= 0f) {
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
private fun VoiceGpsSection(draft: RunPreferences, onDraftChange: (RunPreferences) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Voce e GPS", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = colors.secondary)
        ToggleRow(
            label = "Avvisi GPS",
            checked = draft.gpsCuesEnabled,
            color = colors.secondary,
            tag = UiTags.gpsCues,
        ) { onDraftChange(draft.copy(gpsCuesEnabled = it)) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Volume avvisi · ${(draft.cueVolume * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondary,
            )
            Slider(
                value = draft.cueVolume.coerceIn(0f, 1f),
                onValueChange = { onDraftChange(draft.copy(cueVolume = it)) },
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
private fun ToggleRow(label: String, checked: Boolean, color: Color, tag: String, onCheckedChange: (Boolean) -> Unit) {
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

