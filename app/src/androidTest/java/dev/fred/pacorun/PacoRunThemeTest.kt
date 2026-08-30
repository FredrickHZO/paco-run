package dev.fred.pacorun

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fred.pacorun.ui.theme.FixedPalette
import dev.fred.pacorun.ui.theme.PacoRunTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PacoRunThemeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun eachFixedPaletteUsesExpectedLightAndDarkRoles() {
        val expected = mapOf(
            FixedPalette.ONE to ("ff2f6a43:fff6fbf3" to "ff96d5a6:ff101510"),
            FixedPalette.TWO to ("ff455e91:fffaf9ff" to "ffaec6ff:ff121318"),
            FixedPalette.THREE to ("ff8d4a5c:fffff8f7" to "ffffb1c3:ff191113"),
        )
        compose.setContent {
            expected.forEach { (palette, roles) ->
                listOf(false to roles.first, true to roles.second).forEach { (dark, _) ->
                    PacoRunTheme(dynamicColor = false, fixedPalette = palette, darkTheme = dark) {
                        val colors = MaterialTheme.colorScheme
                        Text(
                            "${colors.primary.hex()}:${colors.surface.hex()}",
                            Modifier.testTag("${palette.name}-$dark"),
                        )
                    }
                }
            }
        }

        expected.forEach { (palette, roles) ->
            compose.onNodeWithTag("${palette.name}-false").assertTextEquals(roles.first)
            compose.onNodeWithTag("${palette.name}-true").assertTextEquals(roles.second)
        }
    }

    @Test
    fun enabledDynamicColorUsesTheAndroidDynamicScheme() {
        var primary by mutableStateOf(Color.Unspecified)
        var expected by mutableStateOf(Color.Unspecified)

        compose.setContent {
            val context = LocalContext.current
            expected = dynamicLightColorScheme(context).primary
            PacoRunTheme(dynamicColor = true, darkTheme = false) { primary = MaterialTheme.colorScheme.primary }
        }

        compose.runOnIdle { assertEquals(expected, primary) }
    }
}

private fun Color.hex(): String = toArgb().toUInt().toString(16).padStart(8, '0')
