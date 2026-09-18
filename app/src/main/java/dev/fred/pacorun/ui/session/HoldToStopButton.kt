package dev.fred.pacorun.ui.session

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fred.pacorun.ui.UiTags
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun HoldToStopButton(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Long = 1_500L,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(colors.errorContainer)
            .pointerInput(holdMillis) {
                awaitEachGesture {
                    awaitFirstDown()
                    scope.launch { progress.snapTo(0f) }
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val fillJob = scope.launch {
                        progress.animateTo(1f, tween(holdMillis.toInt(), easing = LinearEasing))
                        if (isActive) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onStop()
                        }
                    }
                    waitForUpOrCancellation()
                    if (progress.value < 1f) {
                        fillJob.cancel()
                        scope.launch { progress.animateTo(0f, tween(200)) }
                    }
                }
            }
            .semantics {
                contentDescription = "Tieni premuto per fermare"
                progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(progress.value, 0f..1f)
            }
            .testTag(UiTags.stop),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = progress.value.coerceIn(0f, 1f))
                .heightIn(min = 64.dp)
                .background(colors.error),
        )
        Text(
            if (progress.value > 0f) "Tieni premuto..." else "Tieni premuto per fermare",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onErrorContainer,
        )
    }
}
