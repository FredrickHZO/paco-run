package dev.fred.pacorun.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.fred.pacorun.pace.PaceState
import dev.fred.pacorun.service.RunSessionState
import dev.fred.pacorun.ui.components.AmbientShapeField
import dev.fred.pacorun.ui.components.PacoRunScaffold
import dev.fred.pacorun.ui.session.HeroKind
import dev.fred.pacorun.ui.session.HoldToStopButton
import dev.fred.pacorun.ui.session.SessionHero
import dev.fred.pacorun.ui.session.heroKind
import dev.fred.pacorun.ui.session.paceLabel
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActiveRunScreen(state: RunSessionState.Running, onStop: () -> Unit) {
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
            primaryAction = { HoldToStopButton(onStop = onStop) },
            containerColor = Color.Transparent,
            contentColor = colors.onSurface,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    SessionHero(state)
                    PaceChip(state)
                    if (heroKind(state.phase) != HeroKind.SPEED) {
                        Text(
                            "${state.speedKmh?.decimal() ?: "—"} km/h · obiettivo ${state.activeRange.minKmh.decimal()}–${state.activeRange.maxKmh.decimal()} km/h",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                DistanceRow(state)
            }
        }
    }
}

@Composable
private fun PaceChip(state: RunSessionState.Running) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (state.pace.state) {
        PaceState.GOOD -> colors.primaryContainer to colors.onPrimaryContainer
        PaceState.TOO_SLOW, PaceState.TOO_FAST -> colors.errorContainer to colors.onErrorContainer
        PaceState.GPS_WEAK, PaceState.ACQUIRING_GPS -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    AssistChip(
        onClick = {},
        label = { Text(paceLabel(state.pace.state), modifier = Modifier.testTag(UiTags.activeGps)) },
        colors = AssistChipDefaults.assistChipColors(containerColor = container, labelColor = content),
        border = null,
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
private fun DistanceRow(state: RunSessionState.Running) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceContainerHighest)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        DistanceStat("Distanza totale", state.totalDistanceMeters, UiTags.activeTotalDistance)
        DistanceStat("Distanza routine", state.routineDistanceMeters, UiTags.activeRoutineDistance)
    }
}

@Composable
private fun DistanceStat(label: String, meters: Double, tag: String) {
    val colors = MaterialTheme.colorScheme
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Text(
            distanceText(meters),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            modifier = Modifier.testTag(tag),
        )
    }
}

private fun Float.decimal() = String.format(Locale.ITALY, "%.1f", this)

private fun distanceText(meters: Double): String = String.format(Locale.ITALY, "%.2f km", meters / 1_000.0)
