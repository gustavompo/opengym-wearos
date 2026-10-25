package ch.duartesantos.opengym.wear.ui.rest

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.AmbientModeManager
import androidx.wear.compose.foundation.LocalAmbientModeManager
import ch.duartesantos.opengym.wear.data.WorkoutState
import ch.duartesantos.opengym.wear.theme.OpenGymWearTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ambient branch without a real ambient transition: a fake manager through the composition
 * local. It proves what the screen renders in each mode, not that the system enters ambient (see
 * note 43's open questions).
 */
@RunWith(AndroidJUnit4::class)
class RestTimerAmbientTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun ambientDropsTheCountdownAndInteractiveBringsItBack() {
    val manager = FakeAmbientModeManager(AmbientMode.Ambient(false, false))
    composeTestRule.setContent {
      OpenGymWearTheme {
        CompositionLocalProvider(LocalAmbientModeManager provides manager) {
          RestTimer(
            state = restState(),
            onAdjustRest = {},
            onSkipRest = {},
          )
        }
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag(REST_TIMER_COUNTDOWN_TAG).assertDoesNotExist()
    composeTestRule.onNodeWithTag(REST_TIMER_SKIP_TAG).assertDoesNotExist()
    composeTestRule.onNodeWithText("Rest").assertIsDisplayed()
    composeTestRule.onNodeWithText("Next: Lat Pulldown").assertIsDisplayed()

    composeTestRule.runOnIdle { manager.mode = AmbientMode.Interactive }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag(REST_TIMER_COUNTDOWN_TAG).assertIsDisplayed()
    composeTestRule.onNodeWithText("01:29").assertIsDisplayed()
    composeTestRule.onNodeWithTag(REST_TIMER_SKIP_TAG).assertIsDisplayed()
  }

  private fun restState() =
    WorkoutState(
      isWorkoutActive = true,
      exerciseName = "Lat Pulldown",
      exerciseIndex = 0,
      exerciseCount = 4,
      currentSetIndex = 2,
      totalSets = 4,
      weightKg = 50.0,
      reps = 10,
      previousWeightKg = 50.0,
      previousReps = 10,
      restSecondsRemaining = 89,
      restSecondsTotal = 120,
    )

  /** The local's value, backed by Compose state so a mode change recomposes the screen. */
  private class FakeAmbientModeManager(initial: AmbientMode) : AmbientModeManager {
    var mode: AmbientMode by mutableStateOf(initial)

    override val currentAmbientMode: AmbientMode get() = mode

    override suspend fun withAmbientTick(block: () -> Unit) = Unit
  }
}
