package dev.fred.pacorun.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun DistanceControl(
    label: String,
    km: Float,
    tag: String,
    roleColor: Color,
    roleOnColor: Color,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showKeypad by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = roleColor)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(roleColor)
                .clickable { showKeypad = true }
                .padding(vertical = 14.dp)
                .testTag(tag),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${km.decimal()} km",
                style = MaterialTheme.typography.headlineMedium,
                color = roleOnColor,
                textAlign = TextAlign.Center,
            )
        }
    }
    if (showKeypad) {
        NumericKeypadDialog(
            title = "$label (km)",
            initialRaw = Math.round(km * 10f).toLong().coerceAtLeast(0L),
            format = { raw -> "${raw / 10},${raw % 10} km" },
            roleColor = roleColor,
            onValueChange = { raw -> onChange(raw / 10f) },
            onDismiss = { showKeypad = false },
        )
    }
}

private fun Float.decimal() = String.format(Locale.ITALY, "%.1f", this)
