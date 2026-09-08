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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.fred.pacorun.settings.SpeedRange
import java.util.Locale

@Composable
fun SpeedRangeControl(
    label: String,
    range: SpeedRange,
    onChange: (SpeedRange) -> Unit,
    roleColor: Color,
    roleOnColor: Color,
    minTag: String,
    maxTag: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = roleColor)
        SpeedValueCard("Minimo", range.minKmh, minTag, roleColor, roleOnColor) {
            onChange(range.copy(minKmh = it))
        }
        SpeedValueCard("Massimo", range.maxKmh, maxTag, roleColor, roleOnColor) {
            onChange(range.copy(maxKmh = it))
        }
        if (!range.isValid) {
            Text(
                "Il minimo deve essere minore del massimo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SpeedValueCard(hint: String, value: Float, tag: String, roleColor: Color, roleOnColor: Color, onChange: (Float) -> Unit) {
    var showKeypad by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(roleColor)
            .clickable { showKeypad = true }
            .padding(vertical = 14.dp)
            .testTag(tag),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Text(hint, style = MaterialTheme.typography.labelMedium, color = roleOnColor.copy(alpha = 0.8f))
        Text("${value.decimal()} km/h", style = MaterialTheme.typography.headlineMedium, color = roleOnColor, textAlign = TextAlign.Center)
    }
    if (showKeypad) {
        NumericKeypadDialog(
            title = "$hint (km/h)",
            initialRaw = Math.round(value * 10f).toLong().coerceAtLeast(0L),
            format = { raw -> "${(raw / 10)},${raw % 10} km/h" },
            isValid = { raw -> raw > 0 },
            roleColor = roleColor,
            onConfirm = { raw -> onChange(raw / 10f) },
            onDismiss = { showKeypad = false },
        )
    }
}

private fun Float.decimal() = String.format(Locale.ITALY, "%.1f", this)
