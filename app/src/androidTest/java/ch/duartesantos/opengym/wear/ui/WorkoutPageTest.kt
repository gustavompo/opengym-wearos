package ch.duartesantos.opengym.wear.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import ch.duartesantos.opengym.wear.data.FakeWorkoutRepository
import ch.duartesantos.opengym.wear.ui.exercise.EXERCISE_PAGE_PREV_TAG
import ch.duartesantos.opengym.wear.ui.exercise.EXERCISE_PAGE_TAG
import ch.duartesantos.opengym.wear.ui.workout.WORKOUT_PAGE_LIST_TAG
import ch.duartesantos.opengym.wear.ui.workout.WORKOUT_PAGE_TAG
import ch.duartesantos.opengym.wear.ui.workout.WORKOUT_PAGE_TIMER_TAG
import ch.duartesantos.opengym.wear.ui.workout.workoutRowTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  on a device: the workout page renders the session's list, a row tap moves the exercise, and
 * both ways of going BACK return to the list.
 *
 * Driven through the whole `WearApp` over the fake repository, so the navigation state machine and the
 * gesture wiring are exercised rather than the composable alone. The fake answers `selectExercise`
 * immediately, so what these prove is the *wiring*; the pending-row rules are proven in
 * `WorkoutNavigationTest` and `WorkoutPageContractTest` (both JVM).
 *
 * Note on the list's viewport: the page keeps the reference's measured card height (89 of its 305 px
 * disc = 0.2918 of our width), so at 454 px about **one** row is on screen at a time and the rest are
 * reached by scrolling. Tests therefore never assume a row is composed — they scroll first.
 */
@RunWith(AndroidJUnit4::class)
class WorkoutPageTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private fun launch() {
    rule.setContent { WearApp(repository = FakeWorkoutRepository()) }
  }

  /** The first exercise's `‹` opens the list. */
  private fun openTheList() {
    rule.onNodeWithTag(EXERCISE_PAGE_PREV_TAG).performClick()
    rule.onNodeWithTag(WORKOUT_PAGE_TAG).assertIsDisplayed()
  }

  @Test
  fun opensOnTheExercisePage() {
    launch()
    rule.onNodeWithTag(EXERCISE_PAGE_TAG).assertIsDisplayed()
  }

  @Test
  fun theFirstExercisesChevronOpensTheListWithTheWorkoutNameAndAnElapsedTimer() {
    launch()
    openTheList()

    rule.onNodeWithText("Full Body A").assertIsDisplayed()
    // The fake stamps `active.start` 45:12 before "now", so the timer has something to render.
    rule.onNodeWithTag(WORKOUT_PAGE_TIMER_TAG).assertIsDisplayed()
  }

  @Test
  fun theListOpensCentredOnTheCurrentExerciseWithItsProgress() {
    launch()
    openTheList()

    // Row 0 is the current exercise, so it is the one the list opens on, and a row is a single merged
    // node: "Lat Pulldown", 0 of 4 sets done.
    rule.onNodeWithTag(workoutRowTag(0)).assertIsDisplayed()
    rule.onNodeWithTag(workoutRowTag(0)).assertContentDescriptionEquals("Lat Pulldown, 0 of 4 sets")
  }

  /**
   * A finger drag moves the list. Asserted as *movement* rather than as "row 2 is now visible": how
   * far one injected swipe travels depends on the fling, and a test that asserts a distance will
   * eventually be wrong for a reason that is not the code.
   */
  /**
   * A finger drag moves the list. Asserted as *movement* rather than as a distance or as "row 2 is now
   * visible": one injected swipe is a fling, and a test that pins its travel will eventually be wrong
   * for a reason that is not the code. Row 0 was at the top, so it must have moved up or left the
   * viewport altogether.
   */
  @Test
  fun theListScrollsByTouch() {
    launch()
    openTheList()

    val tops = {
      rule.onAllNodesWithTag(workoutRowTag(0)).fetchSemanticsNodes().map { it.boundsInRoot.top }
    }
    val before = tops()

    // Deliberately *inside* the list's own bounds: the list's node ends at the screen's edge, and a
    // touch injected at the exact edge is not delivered to the window.
    rule.onNodeWithTag(WORKOUT_PAGE_LIST_TAG).performTouchInput {
      swipeUp(startY = bottom - 20f, endY = top + 20f)
    }
    val after = tops()

    assertTrue(
      "a finger drag must move the list up ($before -> $after)",
      after.isEmpty() || after.single() < before.single(),
    )
  }

  /**
   * A rotary detent scrolls the same list — the secondary path on this emulator, and the only one on a
   * watch with a crown.
   */
  @Test
  fun aRotaryDetentScrollsTheList() {
    launch()
    openTheList()
    rule.onNodeWithTag(workoutRowTag(0)).assertIsDisplayed()

    // NEGATIVE is "forward" here. Measured on `emulator-5554`: with the list at its top,
    // `adb shell input rotaryencoder scroll --axis SCROLL,4` moves nothing and `--axis SCROLL,-4`
    // moves it, and the exercise page's own weight spinner reads the same sign the same way.
    repeat(6) { injectRotaryDetent(-AXIS_SCROLL_PER_DETENT) }

    // Row 0 has scrolled off the top.
    rule.onNodeWithTag(workoutRowTag(0)).assertDoesNotExist()
  }

  @Test
  fun tappingTheRowThatIsAlreadyCurrentOpensTheExercisePage() {
    launch()
    openTheList()

    rule.onNodeWithTag(workoutRowTag(0)).performClick()

    rule.onNodeWithTag(EXERCISE_PAGE_TAG).assertIsDisplayed()
    rule.onNodeWithText("Lat Pulldown").assertIsDisplayed()
  }

  @Test
  fun tappingAnotherRowMovesTheExerciseAndOpensIt() {
    launch()
    openTheList()
    // A semantics scroll rather than a swipe: this test is about the tap, and scrolling to an index is
    // deterministic where a fling is not.
    rule.onNodeWithTag(WORKOUT_PAGE_LIST_TAG).performScrollToIndex(1)

    rule.onNodeWithTag(workoutRowTag(1)).performClick()

    rule.onNodeWithTag(EXERCISE_PAGE_TAG).assertIsDisplayed()
    rule.onNodeWithText("Dumbbell Row").assertIsDisplayed()
  }

  @Test
  fun theSystemBackKeyFromTheExercisePageReturnsToTheList() {
    launch()
    openTheList()
    rule.onNodeWithTag(workoutRowTag(0)).performClick()
    rule.onNodeWithTag(EXERCISE_PAGE_TAG).assertIsDisplayed()

    Espresso.pressBack()

    rule.onNodeWithTag(WORKOUT_PAGE_TAG).assertIsDisplayed()
  }

  /**
   * The behavioural boundary, the BACK half: a horizontal swipe on the exercise page — over
   * the spinner cards, which is where the wheel's vertical drag lives — navigates, with no button
   * pressed.
   *
   * (The wheel's own half, "a vertical drag changes the value and does not navigate", belongs to agent
   * B's `ui/exercise` tests after the merge. The second test below proves the part this change is
   * responsible for: that a vertical drag does not navigate.)
   */
  @Test
  fun aHorizontalSwipeRightOnTheExercisePageReturnsToTheList() {
    launch()
    rule.onNodeWithTag(EXERCISE_PAGE_TAG).assertIsDisplayed()

    rule.onNodeWithTag(EXERCISE_PAGE_TAG).performTouchInput { swipeRight() }

    rule.onNodeWithTag(WORKOUT_PAGE_TAG).assertIsDisplayed()
  }

  @Test
  fun aVerticalDragOnTheExercisePageDoesNotNavigate() {
    launch()
    rule.onNodeWithTag(EXERCISE_PAGE_TAG).assertIsDisplayed()

    rule.onNodeWithTag(EXERCISE_PAGE_TAG).performTouchInput { swipeDown() }

    rule.onNodeWithTag(EXERCISE_PAGE_TAG).assertIsDisplayed()
    rule.onNodeWithTag(WORKOUT_PAGE_TAG).assertDoesNotExist()
  }

  /**
   * One genuine crown detent, built and injected the way `ExercisePageRotaryTest` does it for this
   * device: a real `SOURCE_ROTARY_ENCODER` device carrying a real `AXIS_SCROLL`, which is exactly what
   * the framework hands Compose's `onRotaryScrollEvent`. The test API's own rotary helper does not
   * produce that event, which is why the 16-argument `MotionEvent.obtain` is used here.
   */
  private fun injectRotaryDetent(axisScroll: Float) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val now = SystemClock.uptimeMillis()
    val coordinates = MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_SCROLL, axisScroll) }
    val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN }
    // (downTime, eventTime, action, pointerCount, pointerProperties, pointerCoords, metaState,
    //  buttonState, xPrecision, yPrecision, deviceId, edgeFlags, source, displayId, flags,
    //  classification)
    val event =
      MotionEvent.obtain(
        now,
        now,
        MotionEvent.ACTION_SCROLL,
        1,
        arrayOf(properties),
        arrayOf(coordinates),
        0,
        0,
        1f,
        1f,
        0,
        0,
        InputDevice.SOURCE_ROTARY_ENCODER,
        0,
        0,
        0,
      )
    instrumentation.uiAutomation.injectInputEvent(event, true)
    instrumentation.waitForIdleSync()
  }

  private companion object {
    /**
     * What `adb shell input rotaryencoder scroll --axis SCROLL,1` delivers on this emulator: one unit
     * per detent on `AXIS_SCROLL`. The page reads only the sign, so one detent is one nudge whether the
     * platform scales that unit or not.
     */
    const val AXIS_SCROLL_PER_DETENT = 1f
  }
}
