package dev.fred.pacorun.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun NumericKeypadDialog(
    title: String,
    initialRaw: Long,
    format: (Long) -> String,
    isValid: (Long) -> Boolean,
    roleColor: Color,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var raw by remember { mutableLongStateOf(initialRaw) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Start) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Annulla") }
                }
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    format(raw),
                    style = MaterialTheme.typography.displayMedium,
                    color = roleColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 24.dp).testTag("keypad-preview"),
                )
                val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "00", "0", "⌫")
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    keys.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            row.forEach { key ->
                                KeypadKey(key, roleColor) {
                                    raw = when (key) {
                                        "⌫" -> raw / 10
                                        "00" -> raw * 100
                                        else -> raw * 10 + key.toLong()
                                    }
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = { onConfirm(raw); onDismiss() },
                    enabled = isValid(raw),
                    shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ButtonDefaults.MediumContainerHeight)
                        .padding(top = 24.dp, bottom = 16.dp)
                        .testTag("keypad-confirm"),
                ) { Text("OK", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
            }
        }
    }
}

@Composable
private fun KeypadKey(label: String, color: Color, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(contentColor = color),
        modifier = Modifier.size(76.dp).testTag("keypad-key-$label"),
    ) {
        if (label == "⌫") {
            Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Cancella")
        } else {
            Text(label, style = MaterialTheme.typography.headlineSmall)
        }
    }
}
