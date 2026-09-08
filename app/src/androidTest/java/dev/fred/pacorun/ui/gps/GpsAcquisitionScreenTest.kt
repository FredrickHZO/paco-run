package dev.fred.pacorun.ui.gps

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fred.pacorun.ui.UiTags
import dev.fred.pacorun.ui.theme.PacoRunTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpsAcquisitionScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun acquiringShowsExpressiveProgressAndCancelOnly() {
        composeRule.setContent {
            PacoRunTheme(dynamicColor = false) { GpsAcquisitionScreen(onCancel = {}) }
        }

        composeRule.onNodeWithText("Ricerca GPS").assertExists()
        composeRule.onNodeWithTag(UiTags.gpsProgress).assertExists()
        composeRule.onNodeWithText("Annulla").assertHasClickAction()
        composeRule.onNodeWithText("Parti").assertDoesNotExist()
    }

    @Test
    fun cancelInvokesCallback() {
        var cancelled = false
        composeRule.setContent {
            PacoRunTheme(dynamicColor = false) { GpsAcquisitionScreen(onCancel = { cancelled = true }) }
        }

        composeRule.onNodeWithText("Annulla").performClick()

        assertTrue(cancelled)
    }
}
