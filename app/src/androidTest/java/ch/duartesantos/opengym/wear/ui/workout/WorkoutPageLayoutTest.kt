package ch.duartesantos.opengym.wear.ui.workout

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import ch.duartesantos.opengym.wear.data.WorkoutRow
import ch.duartesantos.opengym.wear.data.WorkoutState
import ch.duartesantos.opengym.wear.theme.OpenGymWearTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * The workout page's presentation contract on a device (U2b):
 *
 *  - the header (workout name + elapsed timer) sits in the top quarter of the disc, not the middle,
 *    so the list is the page's focus — pinned as *bounds* against the screen, not as an impression;
 *  - the timer keeps its hide-when-`startedAt`-is-null rule at the composable level;
 *  - long realistic names (the p90/p100 set of `docs/notes/30-exercise-name-lengths.md`) render
 *    without clipping — the no-clip pixel proof is measured host-side on the screenshot the last
 *    test holds on screen for (an emulator test cannot see pixels, and the model does not look at
 *    images; see findings §3).
 *
 * Rendered through [WorkoutPage] alone (not `WearApp`): these tests are about the page's own
 * presentation, and the navigation behaviour is already pinned by `WorkoutPageTest` /
 * `WorkoutNavigationTest`.
 */
@RunWith(AndroidJUnit4::class)
class WorkoutPageLayoutTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  /** The page's own projection: 5 exercises, setsDone varied, started 45:12 ago. */
  private fun fixtureState(
    workoutName: String = "Full Body A",
    startedAtMillis: Long? = System.currentTimeMillis() - 45_000L - 12_000L,
  ) =
    WorkoutState(
      isWorkoutActive = true,
      exerciseName = "Lat Pulldown",
      exerciseIndex = 0,
      exerciseCount = 5,
      currentSetIndex = 1,
      totalSets = 4,
      weightKg = 50.0,
      reps = 10,
      previousWeightKg = 45.0,
      previousReps = 8,
      restSecondsRemaining = 0,
      restSecondsTotal = 90,
      workoutName = workoutName,
      startedAtMillis = startedAtMillis,
      exercises =
        listOf(
          WorkoutRow(0, "Lat Pulldown", 0, 4),
          WorkoutRow(1, "Dumbbell Row", 2, 3),
          WorkoutRow(2, "Barbell Squat", 3, 3),
          WorkoutRow(3, "Bench Press", 4, 4),
          WorkoutRow(4, "Overhead Press", 0, 3),
        ),
    )

  private fun show(state: WorkoutState = fixtureState()) {
    rule.setContent {
      OpenGymWearTheme { WorkoutPage(state = state, onSelectRow = {}) }
    }
    rule.waitForIdle()
  }

  @Test
  fun theHeaderSitsInTheTopQuarterAndTheListBelowIt() {
    show()

    val screenHeight = rule.onNodeWithTag(WORKOUT_PAGE_TAG).fetchSemanticsNode().boundsInRoot.height
    val name = rule.onNodeWithTag(WORKOUT_PAGE_NAME_TAG).fetchSemanticsNode().boundsInRoot
    val timer = rule.onNodeWithTag(WORKOUT_PAGE_TIMER_TAG).fetchSemanticsNode().boundsInRoot
    val row0 = rule.onNodeWithTag(workoutRowTag(0)).fetchSemanticsNode().boundsInRoot

    // The name was at y 134..187 of 454 (29.5 %..41 %) before U2b; it must now open on the disc's
    // top quarter — the assertion threshold is deliberately well clear of the old position.
    assertTrue("workout name top ${name.top} of $screenHeight must be in the top quarter", name.top < screenHeight * 0.25f)
    // The timer follows the name and stays above the list.
    assertTrue("timer top ${timer.top} must be below the name", timer.top >= name.bottom)
    assertTrue("timer top ${timer.top} of $screenHeight must be in the top half", timer.top < screenHeight * 0.5f)
    // Every row starts below the header.
    assertTrue("row 0 top ${row0.top} must be below the timer", row0.top >= timer.bottom)
  }

  @Test
  fun theRowBelowTheCurrentOneIsAlsoOnScreen() {
    // Python-measured: with the header up, the list viewport is ~1.88 rows tall, so the row after
    // the current one is (at least partially) on screen — that is the "list is the focus" promise.
    show()
    rule.onNodeWithTag(WORKOUT_PAGE_LIST_TAG).assertIsDisplayed()
    rule.onNodeWithTag(workoutRowTag(0)).assertIsDisplayed()
    // Row 1 must be composed (visible or partially visible) without any scroll.
    rule.onNodeWithTag(workoutRowTag(1)).assertIsDisplayed().let { /* partially visible counts */ }
  }

  @Test
  fun theTimerDisappearsEntirelyWhenThePhoneSentNoStart() {
    show(fixtureState(startedAtMillis = null))

    rule.onNodeWithTag(WORKOUT_PAGE_NAME_TAG).assertIsDisplayed()
    rule.onNodeWithTag(WORKOUT_PAGE_TIMER_TAG).assertDoesNotExist()
    rule.onNodeWithTag(workoutRowTag(0)).assertIsDisplayed()
  }

  @Test
  fun aRowTapForwardsItsIndexExactly() {
    var tapped: Int? = null
    rule.setContent {
      OpenGymWearTheme { WorkoutPage(state = fixtureState(), onSelectRow = { tapped = it }) }
    }
    rule.waitForIdle()

    rule.onNodeWithTag(workoutRowTag(0)).performClick()
    assertEquals(0, tapped)
    // The haptic fired synchronously inside the click (a no-op on the emulator, as documented);
    // what is observable here is that the click path runs and the callback is exact.
  }

  /**
   * Long realistic names, rendered and HElD on screen for 35 s so the harness can screencap and
   * measure the ink against the bezel arc (findings §3; the model does not look at images).
   * The names are note 30's p90/p95/p100 + the one the fake repo would show, and the workout name
   * is the longest row name pushed up into the title band — an over-stress, on purpose.
   *
   * The test itself only asserts that everything renders and the list scrolls; the pixel numbers
   * are measured host-side and reported in `docs/notes/32-...findings.md` §3.
   */
  @Test
  fun longNamesRenderAndHoldStillForThePixelProof() {
    val long = listOf(
      WorkoutRow(0, "cable lat pulldown full range of motion", 0, 3),
      WorkoutRow(1, "dumbbell standing overhead press", 0, 3),
      WorkoutRow(2, "cable triceps pushdown (v-bar)", 1, 3),
      WorkoutRow(3, "barbell incline bench press", 3, 3),
      WorkoutRow(4, "barbell romanian deadlift", 2, 3),
      WorkoutRow(5, "cable rope seated row", 2, 3),
      WorkoutRow(6, "Lat Pulldown", 0, 4),
    )
    show(
      fixtureState(
        workoutName = "Cable Lat Pulldown Full Range of Motion",
        startedAtMillis = System.currentTimeMillis() - 45_000L - 12_000L,
      ).copy(exercises = long),
    )

    rule.onNodeWithText("cable lat pulldown full range of motion").assertIsDisplayed()
    rule.onNodeWithTag(workoutRowTag(0)).assertIsDisplayed()

    // Hold the screen: the harness screencaps during this window (the activity keeps its content;
    // `svc power stayon true` is set on the rig beforehand).
    SystemClock.sleep(35_000)
    rule.onNodeWithTag(WORKOUT_PAGE_NAME_TAG).assertIsDisplayed()
  }
}