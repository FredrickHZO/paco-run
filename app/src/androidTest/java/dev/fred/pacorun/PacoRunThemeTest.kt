package dev.fred.pacorun

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PacoRunThemeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun disabledDynamicColorUsesFallbackMaterialScheme() {
        var primary by mutableStateOf(Color.Unspecified)
        var expected by mutableStateOf(Color.Unspecified)

        compose.setContent {
            expected = if (isSystemInDarkTheme()) darkColorScheme().primary else lightColorScheme().primary
            PacoRunTheme(dynamicColor = false) { primary = MaterialTheme.colorScheme.primary }
        }

        compose.runOnIdle { assertEquals(expected, primary) }
    }

    @Test
    fun enabledDynamicColorUsesTheAndroidDynamicScheme() {
        var primary by mutableStateOf(Color.Unspecified)
        var expected by mutableStateOf(Color.Unspecified)

        compose.setContent {
            val context = LocalContext.current
            expected = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary
            PacoRunTheme(dynamicColor = true) { primary = MaterialTheme.colorScheme.primary }
        }

        compose.runOnIdle { assertEquals(expected, primary) }
    }
}
