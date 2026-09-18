package dev.fred.pacorun.ui.session

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fred.pacorun.ui.UiTags
import dev.fred.pacorun.ui.theme.PacoRunTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HoldToStopButtonTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun earlyReleaseDoesNotStop() {
        var stopped = false
        composeRule.setContent {
            PacoRunTheme(dynamicColor = false) {
                HoldToStopButton(holdMillis = 1_500L, onStop = { stopped = true })
            }
        }
        composeRule.onNodeWithTag(UiTags.stop).performTouchInput {
            down(center)
            advanceEventTime(700)
            up()
        }
        composeRule.waitForIdle()

        assertFalse(stopped)
    }

    @Test
    fun completedHoldStopsExactlyOnce() {
        var count = 0
        composeRule.setContent {
            PacoRunTheme(dynamicColor = false) {
                HoldToStopButton(holdMillis = 1_500L, onStop = { count++ })
            }
        }
        composeRule.onNodeWithTag(UiTags.stop).performTouchInput {
            down(center)
            advanceEventTime(1_600)
            up()
        }
        composeRule.waitForIdle()

        assertEquals(1, count)
    }
}
