package dev.fred.pacorun.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fred.pacorun.location.LocationReadiness
import dev.fred.pacorun.settings.RoutineMode
import dev.fred.pacorun.settings.RunPreferences
import dev.fred.pacorun.ui.UiTags
import dev.fred.pacorun.ui.theme.PacoRunTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun constantHomeShowsItalianDecisionSummary() {
        show(HomeUiState(RunPreferences(), LocationReadiness.READY))

        composeRule.onNodeWithText("Paco Run").assertExists()
        composeRule.onNodeWithText("Costante").assertExists()
        composeRule.onNodeWithText("7,5–8,5 km/h").assertExists()
        composeRule.onNodeWithText("Run").assertDoesNotExist()
        composeRule.onNodeWithText("Volume", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Confirmation", substring = true).assertDoesNotExist()
    }

    @Test
    fun walkRunHomeShowsDecisionSummaryNotTechnicalSettings() {
        show(HomeUiState(RunPreferences(mode = RoutineMode.WALK_RUN), LocationReadiness.READY))

        composeRule.onNodeWithText("Cammina–Corri").assertExists()
        composeRule.onNodeWithText("1:00").assertExists()
        composeRule.onNodeWithText("2:00").assertExists()
        composeRule.onNodeWithText("0 km iniziali · Senza fine").assertExists()
        composeRule.onNodeWithText("Volume", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("walk-run-plan-container").assertDoesNotExist()
        composeRule.onNodeWithTag(UiTags.start).assertHasClickAction()
        check(composeRule.onNodeWithTag(UiTags.start).getUnclippedBoundsInRoot().width <= 160.dp)
    }

    @Test
    fun everyTechnicalGpsReadinessHasClearCopy() {
        val state = mutableStateOf(HomeUiState(RunPreferences(), LocationReadiness.READY))
        composeRule.setContent {
            PacoRunTheme(dynamicColor = false) { HomeScreen(state.value, {}, {}) }
        }
        listOf(
            LocationReadiness.READY to "GPS pronto",
            LocationReadiness.LOCATION_OFF to "Posizione disattivata",
            LocationReadiness.PERMISSION_NEEDED to "Autorizzazione necessaria",
            LocationReadiness.APPROXIMATE_ONLY to "Posizione approssimativa",
        ).forEach { (readiness, copy) ->
            composeRule.runOnIdle {
                state.value = HomeUiState(RunPreferences(), readiness)
            }
            composeRule.onNodeWithText(copy).assertExists()
        }
    }

    @Test
    fun startAndEditActionsInvokeTheirCallbacks() {
        var started = false
        var edited = false
        composeRule.setContent {
            PacoRunTheme(dynamicColor = false) {
                HomeScreen(
                    state = HomeUiState(RunPreferences(), LocationReadiness.READY),
                    onStart = { started = true },
                    onEdit = { edited = true },
                )
            }
        }

        composeRule.onNodeWithTag(UiTags.start).performClick()
        composeRule.onNodeWithTag(UiTags.editRoutine).performClick()
        composeRule.runOnIdle {
            check(started)
            check(edited)
        }
    }

    private fun show(state: HomeUiState) {
        composeRule.setContent {
            PacoRunTheme(dynamicColor = false) { HomeScreen(state, {}, {}) }
        }
    }
}
