package dev.fred.pacorun.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
) {
    val buttonHeight = ButtonDefaults.MediumContainerHeight
    PacoRunScaffold(
        title = null,
        status = null,
        primaryAction = null,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            HomeHeader(state.locationReadiness)
            RoutineSummary(state.preferences)
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
private fun RoutineSummary(preferences: RunPreferences) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            if (preferences.mode == RoutineMode.CONSTANT) "Costante" else "Cammina–Corri",
            style = MaterialTheme.typography.headlineLargeEmphasized,
        )
        if (preferences.mode == RoutineMode.CONSTANT) {
            Text(preferences.constantRange.label, style = MaterialTheme.typography.displaySmall)
        } else {
            Text(
                "${preferences.initialConstantKm.number()} km iniziali · ${preferences.constantRange.label}",
                style = MaterialTheme.typography.titleLarge,
            )
            PhasePlan(preferences)
            Text(
                if (preferences.routineUnlimited) "Senza fine" else "Fino a ${preferences.routineKm.number()} km",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun PhasePlan(preferences: RunPreferences) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PhaseSummary("Corri", preferences.runSeconds, preferences.runRange.label)
            HorizontalDivider()
            PhaseSummary(
                "Cammina",
                preferences.walkSeconds,
                (if (preferences.walkUsesConstantRange) preferences.constantRange else preferences.walkRange).label,
            )
        }
    }
}

@Composable
private fun PhaseSummary(label: String, seconds: Int, pace: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Column(horizontalAlignment = Alignment.End) {
            Text(seconds.duration(), style = MaterialTheme.typography.headlineSmall)
            Text(pace, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

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
