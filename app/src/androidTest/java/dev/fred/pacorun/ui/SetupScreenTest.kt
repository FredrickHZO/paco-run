package dev.fred.pacorun.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fred.pacorun.pace.PaceResult
import dev.fred.pacorun.pace.PaceState
import dev.fred.pacorun.routine.RoutinePhase
import dev.fred.pacorun.service.RunSessionState
import dev.fred.pacorun.settings.RunPreferences
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetupScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun walkRunShowsItsFieldsAndConditionalFieldsFollowToggles() {
        compose.setContent { EditableSetup() }

        compose.onNodeWithTag(UiTags.constantMode).assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.initialDistance).assertCountEquals(0)
        compose.onNodeWithTag(UiTags.walkRunMode).performClick()
        compose.onNodeWithTag(UiTags.initialDistance).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.runMinutes).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.runSeconds).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.runMinSpeed).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.runMaxSpeed).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.walkMinutes).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.walkSeconds).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.walkUsesConstant).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.walkMinSpeed).assertCountEquals(0)
        compose.onAllNodesWithTag(UiTags.routineDistance).assertCountEquals(0)

        compose.onNodeWithTag(UiTags.walkUsesConstant).performClick()
        compose.onNodeWithTag(UiTags.walkMinSpeed).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.walkMaxSpeed).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.routineUnlimited).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.routineDistance).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.gpsCues).assertCountEquals(1)
        compose.onAllNodesWithTag(UiTags.cueVolume).assertCountEquals(1)
        compose.onAllNodesWithTag(UiTags.repeatSeconds).assertCountEquals(1)
        compose.onAllNodesWithTag(UiTags.confirmationSamples).assertCountEquals(1)
        compose.onAllNodesWithTag(UiTags.dynamicColor).assertCountEquals(1)
        compose.onNodeWithTag(UiTags.gpsCues).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
        )
    }

    @Test
    fun invalidSpeedRangeDisablesStartAndShowsInlineError() {
        compose.setContent { EditableSetup() }

        compose.onNodeWithTag(UiTags.start).assertIsEnabled()
        compose.onNodeWithTag(UiTags.constantMinSpeed).performTextReplacement("9")
        compose.onNodeWithTag(UiTags.start).assertIsNotEnabled()
        compose.onNodeWithTag(UiTags.constantRangeError).assertIsDisplayed()
    }

    @Test
    fun invalidWalkRunFieldsDisableStartAndShowInlineErrors() {
        compose.setContent { EditableSetup() }
        compose.onNodeWithTag(UiTags.walkRunMode).performClick()

        compose.onNodeWithTag(UiTags.runMinutes).performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag(UiTags.runSeconds).performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag(UiTags.initialDistance).performScrollTo().performTextReplacement("-1")
        compose.onNodeWithTag(UiTags.routineUnlimited).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.routineDistance).performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag(UiTags.start).assertIsNotEnabled()
        compose.onAllNodesWithTag(UiTags.runDurationError).assertCountEquals(1)
        compose.onAllNodesWithTag(UiTags.initialDistanceError).assertCountEquals(1)
        compose.onAllNodesWithTag(UiTags.routineDistanceError).assertCountEquals(1)

        compose.onNodeWithTag(UiTags.runMinSpeed).performScrollTo().performTextReplacement("9")
        compose.onAllNodesWithTag(UiTags.runRangeError).assertCountEquals(1)
    }

    @Test
    fun overflowInitialDistanceDisablesStart() {
        compose.setContent {
            EditableSetup(RunPreferences(mode = dev.fred.pacorun.settings.RoutineMode.WALK_RUN, initialConstantKm = Float.POSITIVE_INFINITY))
        }
        compose.onNodeWithTag(UiTags.start).assertIsNotEnabled()
        compose.onAllNodesWithTag(UiTags.initialDistanceError).assertCountEquals(1)
    }

    @Test
    fun invalidVolumeDoesNotPersistAndDisablesStart() {
        var latest = RunPreferences()
        compose.setContent {
            SetupScreen(
                preferences = latest,
                onPreferencesChange = { latest = it },
                onStart = {},
            )
        }
        compose.onNodeWithTag(UiTags.cueVolume).performScrollTo().performTextReplacement("")
        compose.onNodeWithTag(UiTags.start).assertIsNotEnabled()
        compose.onAllNodesWithTag(UiTags.cueVolumeError).assertCountEquals(1)
        check(latest.cueVolume == .6f)

        compose.onNodeWithTag(UiTags.cueVolume).performTextReplacement("999999999999999999999999999999")
        compose.onNodeWithTag(UiTags.start).assertIsNotEnabled()
        check(latest.cueVolume == .6f)
    }

    @Test
    fun activeRunShowsLiveEssentialsAndStops() {
        var stopped = false
        compose.setContent {
            ActiveRunScreen(
                state = RunSessionState.Running(
                    phase = RoutinePhase.RUN,
                    pace = PaceResult(PaceState.GOOD, 8f, null),
                    speedKmh = 8f,
                    gpsReliable = true,
                    startedAtElapsedMillis = 0L,
                    elapsedMillis = 0L,
                    intervalRemainingMillis = 42_000L,
                    totalDistanceMeters = 1_234.0,
                    routineDistanceMeters = 234.0,
                ),
                onStop = { stopped = true },
            )
        }

        compose.onNodeWithTag(UiTags.activePhase).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.activeSpeed).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.activeRemaining).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.activeTotalDistance).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.activeRoutineDistance).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.activeGps).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.stop).performClick()
        check(stopped)
    }
}

@androidx.compose.runtime.Composable
private fun EditableSetup(initial: RunPreferences = RunPreferences()) {
    var preferences by androidx.compose.runtime.remember { mutableStateOf(initial) }
    SetupScreen(
        preferences = preferences,
        onPreferencesChange = { preferences = it },
        onStart = {},
    )
}
