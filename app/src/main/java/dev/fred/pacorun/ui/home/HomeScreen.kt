package dev.fred.pacorun.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.fred.pacorun.location.LocationReadiness
import dev.fred.pacorun.settings.RoutineMode
import dev.fred.pacorun.settings.RunPreferences
import dev.fred.pacorun.ui.UiTags
import dev.fred.pacorun.ui.components.PacoRunScaffold
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onModeChange: (RoutineMode) -> Unit,
) {
    val buttonHeight = ButtonDefaults.MediumContainerHeight
    PacoRunScaffold(
        title = null,
        status = null,
        primaryAction = null,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            HomeHeader(state.locationReadiness)
            RoutineSummary(
                preferences = state.preferences,
                onModeChange = onModeChange,
                modifier = Modifier.weight(1f),
            )
            HomeActions(
                buttonHeight = buttonHeight,
                enabled = state.preferences.isValid,
                onStart = onStart,
                onEdit = onEdit,
            )
        }
    }
}

@Composable
private fun HomeHeader(readiness: LocationReadiness) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Paco Run", style = MaterialTheme.typography.titleLargeEmphasized)
        AssistChip(
            onClick = {},
            label = { Text(readiness.label) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            border = null,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeActions(
    buttonHeight: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    onStart: () -> Unit,
    onEdit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Button(
            onClick = onStart,
            shapes = ButtonDefaults.shapesFor(buttonHeight),
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = buttonHeight)
                .testTag(UiTags.start),
            contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight),
        ) {
            Text("Avvia", style = ButtonDefaults.textStyleFor(buttonHeight))
        }
        TextButton(
            onClick = onEdit,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.editRoutine),
        ) { Text("Modifica routine") }
    }
}

@Composable
private fun RoutineSummary(
    preferences: RunPreferences,
    onModeChange: (RoutineMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionScheme = MaterialTheme.motionScheme
    Column(modifier = modifier.fillMaxWidth()) {
        ModeSelector(preferences.mode, onModeChange)
        Spacer(Modifier.weight(1f))
        AnimatedContent(
            targetState = preferences.mode,
            transitionSpec = {
                fadeIn(animationSpec = motionScheme.defaultEffectsSpec()) togetherWith
                    fadeOut(animationSpec = motionScheme.fastEffectsSpec())
            },
            label = "routine-object",
        ) { mode ->
            if (mode == RoutineMode.CONSTANT) ConstantRoutineObject(preferences)
            else WalkRunRoutineObject(preferences)
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun WalkRunRoutineObject(preferences: RunPreferences) {
    val runDiameter = phaseDiameter(preferences.runSeconds)
    val walkDiameter = phaseDiameter(preferences.walkSeconds)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .testTag("walk-run-background"),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-52).dp, y = 8.dp)
                .size(runDiameter)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .testTag("walk-run-run-background"),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 52.dp, y = 4.dp)
                .size(walkDiameter)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .testTag("walk-run-walk-background"),
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${preferences.initialConstantKm.number()} km iniziali · " +
                    if (preferences.routineUnlimited) "Senza fine" else "Fino a ${preferences.routineKm.number()} km",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))
            PhaseTypography(
                label = "Corri",
                seconds = preferences.runSeconds,
                pace = preferences.runRange.label,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.align(Alignment.Start),
            )
            Spacer(Modifier.weight(1f))
            PhaseTypography(
                label = "Cammina",
                seconds = preferences.walkSeconds,
                pace = (if (preferences.walkUsesConstantRange) preferences.constantRange else preferences.walkRange).label,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun ModeSelector(mode: RoutineMode, onModeChange: (RoutineMode) -> Unit) {
    ButtonGroup(
        overflowIndicator = {},
        modifier = Modifier.fillMaxWidth(),
    ) {
        toggleableItem(
            checked = mode == RoutineMode.CONSTANT,
            label = "Costante",
            onCheckedChange = { if (it) onModeChange(RoutineMode.CONSTANT) },
            weight = 1f,
        )
        toggleableItem(
            checked = mode == RoutineMode.WALK_RUN,
            label = "Cammina–Corri",
            onCheckedChange = { if (it) onModeChange(RoutineMode.WALK_RUN) },
            weight = 1f,
        )
    }
}

@Composable
private fun ConstantRoutineObject(preferences: RunPreferences) {
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .testTag("constant-background"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(320.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Andatura costante", style = MaterialTheme.typography.titleMedium, color = contentColor)
            Text(
                preferences.constantRange.label,
                style = MaterialTheme.typography.displayMedium,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun PhaseTypography(
    label: String,
    seconds: Int,
    pace: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = color)
        Text(seconds.duration(), style = MaterialTheme.typography.displaySmall, color = color)
        Text(pace, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

private fun phaseDiameter(seconds: Int) =
    (180f + seconds * 0.8f).coerceIn(216f, 292f).dp

private val LocationReadiness.label: String
    get() = when (this) {
        LocationReadiness.READY -> "GPS pronto"
        LocationReadiness.LOCATION_OFF -> "Posizione disattivata"
        LocationReadiness.PERMISSION_NEEDED -> "Autorizzazione necessaria"
        LocationReadiness.APPROXIMATE_ONLY -> "Posizione approssimativa"
    }

private val dev.fred.pacorun.settings.SpeedRange.label: String
    get() = "${minKmh.number()}–${maxKmh.number()} km/h"

private fun Float.number() = String.format(Locale.ITALY, "%.1f", this).removeSuffix(",0")

private fun Int.duration() = String.format(Locale.US, "%d:%02d", this / 60, this.mod(60))
