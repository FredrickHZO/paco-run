package dev.fred.pacorun.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
        composeRule.onNodeWithTag("home-scene-background").assertExists()
        composeRule.onNodeWithTag("walk-run-scene").assertExists()
        composeRule.onNodeWithTag("run-phase-shape")
            .assert(hasAnyDescendant(hasTestTag("run-phase-content")))
        composeRule.onNodeWithTag("walk-phase-shape")
            .assert(hasAnyDescendant(hasTestTag("walk-phase-content")))
        composeRule.onNodeWithTag(UiTags.start).assertHasClickAction()
        composeRule.onNodeWithTag(UiTags.start).assertHeightIsEqualTo(56.dp)
        val startBounds = composeRule.onNodeWithTag(UiTags.start).getUnclippedBoundsInRoot()
        check(startBounds.top >= 500.dp)
        check(composeRule.onNodeWithTag(UiTags.editRoutine).getUnclippedBoundsInRoot().top >= startBounds.bottom)
    }

    @Test
    fun everyTechnicalGpsReadinessHasClearCopy() {
        val state = mutableStateOf(HomeUiState(RunPreferences(), LocationReadiness.READY))
        composeRule.setContent {
            PacoRunTheme(dynamicColor = false) { HomeScreen(state.value, {}, {}, {}) }
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
    fun constantHomeUsesItsOwnBackgroundStructure() {
        show(HomeUiState(RunPreferences(mode = RoutineMode.CONSTANT), LocationReadiness.READY))

        composeRule.onNodeWithTag("constant-scene").assertExists()
        val shape = composeRule.onNodeWithTag("constant-phase-shape")
            .assert(hasAnyDescendant(hasTestTag("constant-phase-content")))
            .getUnclippedBoundsInRoot()
        val range = composeRule.onNodeWithText("7,5–8,5 km/h").getUnclippedBoundsInRoot()
        check(range.left >= shape.left + 40.dp)
        check(range.right <= shape.right - 40.dp)
        composeRule.onNodeWithTag("walk-run-scene").assertDoesNotExist()
    }

    @Test
    fun homeActionsAndModeSelectionInvokeTheirCallbacks() {
        var started = false
        var edited = false
        var selectedMode: RoutineMode? = null
        composeRule.setContent {
            PacoRunTheme(dynamicColor = false) {
                HomeScreen(
                    state = HomeUiState(RunPreferences(), LocationReadiness.READY),
                    onStart = { started = true },
                    onEdit = { edited = true },
                    onModeChange = { selectedMode = it },
                )
            }
        }

        composeRule.onNodeWithText("Cammina–Corri").performClick()
        composeRule.onNodeWithTag(UiTags.start).performClick()
        composeRule.onNodeWithTag(UiTags.editRoutine).performClick()
        composeRule.runOnIdle {
            check(selectedMode == RoutineMode.WALK_RUN)
            check(started)
            check(edited)
        }
    }

    private fun show(state: HomeUiState) {
        composeRule.setContent {
            PacoRunTheme(dynamicColor = false) { HomeScreen(state, {}, {}, {}) }
        }
    }
}
