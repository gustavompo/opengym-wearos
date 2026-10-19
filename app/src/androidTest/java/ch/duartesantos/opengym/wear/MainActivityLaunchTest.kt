package ch.duartesantos.opengym.wear

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.duartesantos.opengym.wear.link.LinkConfig
import ch.duartesantos.opengym.wear.link.TransportKind
import ch.duartesantos.opengym.wear.ui.exercise.EXERCISE_PAGE_COMPLETE_TAG
import ch.duartesantos.opengym.wear.ui.exercise.EXERCISE_PAGE_NEXT_TAG
import ch.duartesantos.opengym.wear.ui.exercise.EXERCISE_PAGE_PREV_TAG
import ch.duartesantos.opengym.wear.ui.exercise.EXERCISE_PAGE_TAG
import ch.duartesantos.opengym.wear.ui.rest.REST_TIMER_SKIP_TAG
import ch.duartesantos.opengym.wear.ui.rest.REST_TIMER_TAG
import ch.duartesantos.opengym.wear.ui.workout.WORKOUT_PAGE_TAG
import ch.duartesantos.opengym.wear.ui.workout.workoutRowTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the app's real activity — real applicationId, real manifest, real `WearApp` — and drives
 * the exercise page through one full cycle (exercise -> rest -> next set).
 *
 * This is the evidence that the rename (applicationId `ch.duartesantos.opengym`, namespace
 * `.wear`) installs and runs, and that the navigation skeleton moves between its two destinations
 * on repository state rather than on a button's own state.
 *
 * **The intent carries `transport=fake`, and it has to.** Since the default for a plain launch
 * is [TransportKind.DATALAYER], so an activity started
 * with no extras builds a `TransportWorkoutRepository` against a phone that does not exist. The
 * assertions below are about `FakeWorkoutRepository`'s content ("Lat Pulldown", "Set 1/4"), so
 * without the override they would be reading the Data Layer's "phone not connected" screen.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {

  @get:Rule
  val composeTestRule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> =
    AndroidComposeTestRule(
      activityRule =
        ActivityScenarioRule<MainActivity>(
          Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(LinkConfig.EXTRA_TRANSPORT, TransportKind.FAKE.wire),
        ),
      activityProvider = ::activityOf,
    )

  @Test
  fun activityLaunchesOnTheExercisePage() {
    composeTestRule.onNodeWithTag(EXERCISE_PAGE_TAG).assertIsDisplayed()
    composeTestRule.onNodeWithText("Lat Pulldown").assertIsDisplayed()
    composeTestRule.onNodeWithText("Set 1/4").assertIsDisplayed()
  }

  @Test
  fun completingASetShowsTheRestTimer() {
    composeTestRule.onNodeWithTag(EXERCISE_PAGE_COMPLETE_TAG).performClick()

    composeTestRule.onNodeWithTag(REST_TIMER_TAG).assertIsDisplayed()
    composeTestRule.onNodeWithText("Next: Lat Pulldown").assertIsDisplayed()
  }

  @Test
  fun skippingRestReturnsToTheExercisePageOnTheNextSet() {
    composeTestRule.onNodeWithTag(EXERCISE_PAGE_COMPLETE_TAG).performClick()
    composeTestRule.onNodeWithTag(REST_TIMER_TAG).assertIsDisplayed()

    composeTestRule.onNodeWithTag(REST_TIMER_SKIP_TAG).performClick()

    composeTestRule.onNodeWithTag(EXERCISE_PAGE_TAG).assertIsDisplayed()
    composeTestRule.onNodeWithText("Set 2/4").assertIsDisplayed()
  }

  /**
   *  Delivered through the real `WearApp`: the chevron state comes from `WorkoutState.exerciseIndex` /
   * `exerciseCount` (projected from the repository), not from the screen's own defaults.
   *
   * `canGoPrevious` / `canGoNext` are *asserted*, never clicked while false: Compose removes the
   * click action from a disabled node, so `performClick` on one would fail for the wrong reason.
   */
  @Test
  fun theChevronsAreGreyedOutAtTheFirstAndLastExercise() {
    // First of four. This change made one line matter: `‹` is no longer dead on the first
    // exercise — it opens the workout page — so it is enabled, and this test asserts what it now
    // does rather than that it is greyed. `›` is unchanged.
    composeTestRule.onNodeWithText("Lat Pulldown").assertIsDisplayed()
    composeTestRule.onNodeWithTag(EXERCISE_PAGE_PREV_TAG).assertIsEnabled()
    composeTestRule.onNodeWithTag(EXERCISE_PAGE_NEXT_TAG).assertIsEnabled()

    composeTestRule.onNodeWithTag(EXERCISE_PAGE_PREV_TAG).performClick()
    composeTestRule.onNodeWithTag(WORKOUT_PAGE_TAG).assertIsDisplayed()
    composeTestRule.onNodeWithText("Full Body A").assertIsDisplayed()
    // Back to the exercise page by tapping the row that is already current (rule 6: that tap
    // needs no command), *not* with BACK — BACK from the list is what finishes the activity.
    composeTestRule.onNodeWithTag(workoutRowTag(0)).performClick()
    composeTestRule.onNodeWithText("Lat Pulldown").assertIsDisplayed()

    // Walk to the last of the four.
    repeat(3) { composeTestRule.onNodeWithTag(EXERCISE_PAGE_NEXT_TAG).performClick() }
    composeTestRule.onNodeWithText("Bench Press").assertIsDisplayed()

    composeTestRule.onNodeWithTag(EXERCISE_PAGE_PREV_TAG).assertIsEnabled()
    composeTestRule.onNodeWithTag(EXERCISE_PAGE_NEXT_TAG).assertIsNotEnabled()

    // The flags track the index in both directions: back one exercise re-enables `›`, and
    // returning to the last one disables it again.
    composeTestRule.onNodeWithTag(EXERCISE_PAGE_PREV_TAG).performClick()
    composeTestRule.onNodeWithText("Barbell Squat").assertIsDisplayed()
    composeTestRule.onNodeWithTag(EXERCISE_PAGE_NEXT_TAG).assertIsEnabled()

    composeTestRule.onNodeWithTag(EXERCISE_PAGE_NEXT_TAG).performClick()
    composeTestRule.onNodeWithText("Bench Press").assertIsDisplayed()
    composeTestRule.onNodeWithTag(EXERCISE_PAGE_NEXT_TAG).assertIsNotEnabled()
  }
}

/**
 * The `activityProvider` [AndroidComposeTestRule] needs in order to launch the activity from a
 * custom [Intent].
 *
 * `ui-test-junit4:1.10.6` has exactly this function internally (`getActivityFromTestRule` in
 * `AndroidComposeTestRule.android.kt`) and it is private, so passing our own `Intent` — the only way
 * to add the `transport` extra — means writing it here. It is three lines of the library's own code,
 * not a reimplementation of anything: `ActivityScenario.onActivity` blocks until the activity is
 * created, and the rule calls this provider from inside its own statement, after
 * `ActivityScenarioRule` has launched the activity.
 */
private fun <A : ComponentActivity> activityOf(rule: ActivityScenarioRule<A>): A {
  var activity: A? = null
  rule.scenario.onActivity { activity = it }
  return checkNotNull(activity) { "Activity was not set in the ActivityScenarioRule!" }
}
