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

internal fun durationTotal(minutes: Long, seconds: Long): Int? {
    if (minutes < 0 || seconds < 0) return null
    val total = minutes * 60L + seconds
    return total.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
}

@Composable
fun DurationControl(
    label: String,
    totalSeconds: Int,
    onTotalSecondsChange: (Int) -> Unit,
    roleColor: Color,
    roleOnColor: Color,
    tag: String,
    modifier: Modifier = Modifier,
) {
    var showKeypad by remember { mutableStateOf(false) }
    val minutes = totalSeconds / 60
    val seconds = totalSeconds.mod(60)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                durationLabel(minutes.toLong(), seconds.toLong()),
                style = MaterialTheme.typography.headlineMedium,
                color = roleOnColor,
                textAlign = TextAlign.Center,
            )
        }
        if (totalSeconds < 1) {
            Text(
                "La durata deve essere almeno un secondo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    if (showKeypad) {
        NumericKeypadDialog(
            title = label,
            initialRaw = minutes * 100L + seconds,
            format = { raw -> durationLabel(raw / 100, raw % 100) },
            isValid = { raw -> durationTotal(raw / 100, raw % 100) != null },
            roleColor = roleColor,
            onConfirm = { raw -> durationTotal(raw / 100, raw % 100)?.let(onTotalSecondsChange) },
            onDismiss = { showKeypad = false },
        )
    }
}

private fun durationLabel(minutes: Long, seconds: Long) = "%dm %02ds".format(minutes, seconds)
