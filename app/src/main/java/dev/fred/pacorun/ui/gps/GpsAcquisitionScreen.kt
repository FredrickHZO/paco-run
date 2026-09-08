package dev.fred.pacorun.ui.gps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.fred.pacorun.ui.UiTags
import dev.fred.pacorun.ui.components.AmbientShapeField
import dev.fred.pacorun.ui.components.PacoRunScaffold

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GpsAcquisitionScreen(onCancel: () -> Unit) {
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
                    onClick = onCancel,
                    shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight).testTag(UiTags.stop),
                ) { Text("Annulla", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
            },
            containerColor = Color.Transparent,
            contentColor = colors.onSurface,
        ) {
            Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    LoadingIndicator(
                        color = colors.primary,
                        modifier = Modifier.size(120.dp).testTag(UiTags.gpsProgress),
                    )
                    Text(
                        "Ricerca GPS",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = colors.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Resta all'aperto per un aggancio più rapido.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
