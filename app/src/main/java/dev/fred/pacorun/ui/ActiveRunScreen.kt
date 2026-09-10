package dev.fred.pacorun.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
            // Three peer blocks (distance, phase+hero, speed), evenly spaced -- not a pinned
            // header plus a separately-centered group, which produced whatever gap happened
            // to be left over rather than three deliberate, equal ones.
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                DistanceHeader(state)
                SessionHero(state)
                ActiveStats(state, showSpeed = heroKind(state.phase) != HeroKind.SPEED)
            }
        }
    }
}

@Composable
private fun DistanceHeader(state: RunSessionState.Running) {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Distanza percorsa",
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant,
        )
        Text(
            distanceText(state.totalDistanceMeters),
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            color = colors.onSurface,
            modifier = Modifier.testTag(UiTags.activeTotalDistance),
        )
    }
}

// A single column, never a row: a side-by-side layout breaks the moment any value runs
// long (a double-digit speed, a long routine's distance, "Acquisizione GPS" instead of
// "Buon passo") since sibling columns fight over fixed width. Stacked, every line can be
// as long as it needs to be without touching anything else.
//
// The target range sits directly under the current speed -- they're the same unit and the
// reader compares them at a glance, so nothing (a card, a shape) may separate them. Three
// attempts at giving the stats their own container shape (a MaterialShapes blob, a rounded
// card, the phase's own polygon squeezed flat) all landed wrong, so this stays plain text.
@Composable
private fun ActiveStats(state: RunSessionState.Running, showSpeed: Boolean) {
    val colors = MaterialTheme.colorScheme
    val phaseColor = phaseTextColor(heroKind(state.phase), colors)
    val paceColor = paceTextColor(state.pace.state, colors)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (showSpeed) {
            Text(
                "${state.speedKmh?.decimal() ?: "—"} km/h",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = phaseColor,
                modifier = Modifier.testTag(UiTags.activeSpeed),
            )
        }
        Text(
            "obiettivo ${state.activeRange.minKmh.decimal()}–${state.activeRange.maxKmh.decimal()} km/h",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = if (showSpeed) 8.dp else 0.dp),
        )
        Text(
            paceLabel(state.pace.state),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = paceColor,
            modifier = Modifier.padding(top = 8.dp).testTag(UiTags.activeGps),
        )
    }
}

private fun paceTextColor(state: PaceState, colors: androidx.compose.material3.ColorScheme): Color = when (state) {
    PaceState.GOOD -> colors.primary
    PaceState.TOO_SLOW, PaceState.TOO_FAST -> colors.error
    PaceState.GPS_WEAK, PaceState.ACQUIRING_GPS -> colors.onSurfaceVariant
}

// Matches the hero shape's own fill color for the current phase, so the speed number reads
// as belonging to the same shape above it rather than switching with pace status.
private fun phaseTextColor(kind: HeroKind, colors: androidx.compose.material3.ColorScheme): Color = when (kind) {
    HeroKind.RUN_COUNTDOWN -> colors.primary
    HeroKind.WALK_COUNTDOWN -> colors.tertiary
    HeroKind.SPEED -> colors.secondary
}

private fun Float.decimal() = String.format(Locale.ITALY, "%.1f", this)

private fun distanceText(meters: Double): String = String.format(Locale.ITALY, "%.2f km", meters / 1_000.0)
