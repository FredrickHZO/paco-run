package dev.fred.pacorun.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.fred.pacorun.service.RunSessionState
import java.util.Locale

@Composable
fun ActiveRunScreen(state: RunSessionState.Running, onStop: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Fase: ${state.phase.name.replace('_', ' ')}", Modifier.testTag(UiTags.activePhase))
        Text(
            "Velocità: ${state.speedKmh?.let { "%.1f km/h".format(Locale.ROOT, it) } ?: "--"}",
            Modifier.testTag(UiTags.activeSpeed),
        )
        Text("Intervallo: ${state.intervalRemainingMillis?.let(::durationText) ?: "--"}", Modifier.testTag(UiTags.activeRemaining))
        Text("Distanza totale: ${distanceText(state.totalDistanceMeters)}", Modifier.testTag(UiTags.activeTotalDistance))
        Text("Distanza routine: ${distanceText(state.routineDistanceMeters)}", Modifier.testTag(UiTags.activeRoutineDistance))
        Text("GPS: ${if (state.gpsReliable) "buono" else "debole"}", Modifier.testTag(UiTags.activeGps))
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth().testTag(UiTags.stop)) { Text("Termina") }
    }
}

@Composable
fun AcquiringGpsScreen(onStop: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Acquisizione GPS")
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth().testTag(UiTags.stop)) { Text("Termina") }
    }
}

private fun durationText(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) + 999L) / 1_000L
    return "%d:%02d".format(Locale.ROOT, seconds / 60L, seconds % 60L)
}

private fun distanceText(meters: Double): String = "%.2f km".format(Locale.ROOT, meters / 1_000.0)
