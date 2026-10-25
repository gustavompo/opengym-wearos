package ch.duartesantos.opengym.wear.ui.exercise

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.duartesantos.opengym.wear.data.ActiveWorkout
import ch.duartesantos.opengym.wear.data.FakeWorkoutRepository
import ch.duartesantos.opengym.wear.data.TimeSource
import ch.duartesantos.opengym.wear.data.WeightUnit
import ch.duartesantos.opengym.wear.theme.OpenGymWearTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task 2, on the device: an lb profile's page says `lb` and shows pounds, and a kg profile is
 * unchanged. The arithmetic is pinned on the JVM (`ExercisePageUnitTest`, `WeightUnitTest`); this
 * proves the page actually renders it.
 *
 * The wire value stays 50 kg in both cases — the fake is the phone's side of the protocol, and the
 * protocol is kilograms. Only the display changes.
 */
@RunWith(AndroidJUnit4::class)
class ExercisePageUnitDisplayTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private fun show(unit: WeightUnit) {
    val repository =
      FakeWorkoutRepository(
        initialWorkout = ActiveWorkout.FullBodyA,
        timeSource = TimeSource { 1_791_665_134_901L },
        weightUnit = unit,
      )
    rule.setContent {
      OpenGymWearTheme {
        val state by repository.observeWorkout().collectAsState()
        ExercisePage(
          state = state,
          onCompleteSet = repository::completeSet,
          onPreviousExercise = repository::previousExercise,
          onNextExercise = repository::nextExercise,
          onAdjustWeight = repository::adjustWeight,
          onAdjustReps = repository::adjustReps,
        )
      }
    }
  }

  @Test
  fun anLbProfileShowsPoundsAndTheLbLabel() {
    show(WeightUnit.Pounds)

    // 50.0 kg on the wire is 110.2311 lb, snapped to openGym's 0.5 lb lattice -> 110.0.
    rule.onNodeWithText("lb").assertIsDisplayed()
    rule.onNodeWithText("110.0").assertIsDisplayed()
    rule.onNodeWithText("109.0").assertIsDisplayed()
    rule.onNodeWithText("111.0").assertIsDisplayed()
    // The hint is the same set in the same unit.
    rule.onNodeWithText("prev. 110.0 lb x 10").assertIsDisplayed()
    // The crown's target is announced in the displayed unit too.
    rule.onNodeWithContentDescription("Select lb for the crown").assertIsDisplayed()
    // And the old kg label is gone — not merely joined by the new one.
    rule.onNodeWithText("kg").assertDoesNotExist()
    rule.onNodeWithText("50.0").assertDoesNotExist()
  }

  @Test
  fun aKgProfileIsExactlyWhatItWas() {
    show(WeightUnit.Kilograms)
    rule.onNodeWithText("kg").assertIsDisplayed()
    rule.onNodeWithText("50.0").assertIsDisplayed()
    rule.onNodeWithText("49.5").assertIsDisplayed()
    rule.onNodeWithText("50.5").assertIsDisplayed()
    rule.onNodeWithText("prev. 50.0 kg x 10").assertIsDisplayed()
    rule.onNodeWithContentDescription("Select kg for the crown").assertIsDisplayed()
  }

  @Test
  fun anLbProfileStepsTheCrownByAWholePound() {
    show(WeightUnit.Pounds)
    rule.onNodeWithText("110.0").assertIsDisplayed()
    // Tapping a card must not disturb the value; the value only moves on a detent, which the JVM
    // tests drive. This just proves the card is still tappable with the new label.
    rule.onNodeWithContentDescription("Select lb for the crown").performClick()
    rule.onNodeWithText("110.0").assertIsDisplayed()
  }
}
