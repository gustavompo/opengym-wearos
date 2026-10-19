package ch.duartesantos.opengym.wear.ui.exercise

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.duartesantos.opengym.wear.data.ActiveWorkout
import ch.duartesantos.opengym.wear.data.FakeWorkoutRepository
import ch.duartesantos.opengym.wear.data.TimeSource
import ch.duartesantos.opengym.wear.theme.OpenGymWearTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * the rotating crown, on the device, through the real input pipeline.
 *
 * Two independent pieces of evidence:
 *
 * 1. [aRotaryDetentStepsTheWeightAndIsRoutedToTheTappedSpinner] injects a genuine
 *    `SOURCE_ROTARY_ENCODER` / `AXIS_SCROLL` event with UiAutomation — the same axis the crown
 *    produces — so it runs unattended.
 * 2. [theCrownFromAdbMovesTheValue] waits for an event produced by the *system* binary, i.e.
 *    `adb shell input rotaryencoder scroll --axis SCROLL,<n>`, run by hand while this test is on
 *    screen. Unattended it is skipped (`assumeTrue`), never silently green.
 *
 * Run 2 by hand:
 * ```
 * ./gradlew.bat :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=ch.duartesantos.opengym.wear.ui.exercise.ExercisePageRotaryTest \
 *   -Pandroid.testInstrumentationRunnerArguments.rotaryWaitMillis=120000
 * # then, once "Lat Pulldown" is on screen:
 * adb -s emulator-5554 shell input rotaryencoder scroll --axis SCROLL,4
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ExercisePageRotaryTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private lateinit var repository: FakeWorkoutRepository

  private fun show(workout: ActiveWorkout = ActiveWorkout.FullBodyA) {
    repository =
      FakeWorkoutRepository(initialWorkout = workout, timeSource = TimeSource { 1_791_665_134_901L })
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
  fun aRotaryDetentStepsTheWeightAndIsRoutedToTheTappedSpinner() {
    show()
    rule.onNodeWithText("50.0").assertIsDisplayed()

    // One detent on `AXIS_SCROLL = +1` moves the wheel one entry *back*, so -0.5 kg on the
    // weight: by default the crown drives the `Kg` card. The sign was measured, not assumed.
    // `adb shell input rotaryencoder scroll --axis SCROLL,-1` on emulator-5554 walks the Wear
    // Settings list forwards (Connectivity y=154 -> y=35, a new row entering at the bottom), and
    // the same detent here takes the weight 50.0 -> 50.5. AXIS_SCROLL is therefore inverted with
    // respect to "forward through the list" at the Compose `verticalScrollPixels` boundary.
    injectRotaryDetent(AXIS_SCROLL_PER_DETENT)
    rule.waitForIdle()
    assertEquals(49.5, repository.observeWorkout().value.weightKg, 0.0)

    // The opposite detent returns it, so the step is a step and not a one-way latch.
    injectRotaryDetent(-AXIS_SCROLL_PER_DETENT)
    rule.waitForIdle()
    assertEquals(50.0, repository.observeWorkout().value.weightKg, 0.0)

    // Tapping the Reps card hands the crown over. Weight must not move afterwards.
    rule.onNodeWithTag(EXERCISE_PAGE_REPS_TAG).performClick()
    injectRotaryDetent(AXIS_SCROLL_PER_DETENT)
    rule.waitForIdle()
    assertEquals(9, repository.observeWorkout().value.reps)
    assertEquals(50.0, repository.observeWorkout().value.weightKg, 0.0)

    injectRotaryDetent(-AXIS_SCROLL_PER_DETENT)
    rule.waitForIdle()
    assertEquals(10, repository.observeWorkout().value.reps)
  }

  @Test
  fun theCrownFromAdbMovesTheValue() {
    show()
    rule.onNodeWithText("50.0").assertIsDisplayed()

    val waitMillis =
      InstrumentationRegistry.getArguments().getString("rotaryWaitMillis")?.toLongOrNull()
        ?: 15_000L

    val moved =
      runCatching {
          rule.waitUntil(timeoutMillis = waitMillis) {
            repository.observeWorkout().value.weightKg != 50.0
          }
        }
        .isSuccess

    // Nobody drove the crown, so there is nothing to assert. Skipped, not passed.
    assumeTrue(
      "no system rotary event arrived within ${waitMillis}ms; drive it with " +
        "`adb shell input rotaryencoder scroll --axis SCROLL,<n>`",
      moved,
    )

    val movedTo = repository.observeWorkout().value.weightKg
    println("ROTARY-PROBE: adb-injected SCROLL detents moved the weight 50.0 -> $movedTo")
    // Detents are ±0.5 kg, so the result must land on the half-kilogram lattice and must have moved.
    assertEquals(0.0, (movedTo - 50.0) % 0.5, 1e-9)
    rule.onNodeWithText(formatWeight(movedTo)).assertIsDisplayed()
  }

  /**
   * Injects one crown detent through UiAutomation: the real `SOURCE_ROTARY_ENCODER` device with a
   * real `AXIS_SCROLL` value, which is exactly what the framework hands Compose's
   * `onRotaryScrollEvent`.
   */
  private fun injectRotaryDetent(axisScroll: Float) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val now = SystemClock.uptimeMillis()
    val coordinates =
      MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_SCROLL, axisScroll) }
    val properties =
      MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_UNKNOWN
      }
    // The 16-argument obtain() is the only public way to build a non-touch axis event that also
    // carries a source: PointerCoords is where AXIS_SCROLL lives for a rotary event. Source and
    // axis together are exactly what AndroidComposeView hands to `onRotaryScrollEvent`. The API 29
    // `MotionEvent(...)` constructor with these parameters is absent from the SDK stubs, and the
    // 14-argument obtain() without displayId/classification is its deprecated twin.
    //
    // Argument order is (downTime, eventTime, action, pointerCount, pointerProperties,
    // pointerCoords, metaState, buttonState, xPrecision, yPrecision, deviceId, edgeFlags,
    // source, displayId, flags, classification).
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
     * What `adb shell input rotaryencoder scroll --axis SCROLL,1` delivers on this emulator: one
     * unit per detent on `AXIS_SCROLL`, verified against the Wear Settings list. The page reads
     * only the sign, so one detent is one step whether the platform scales that unit or not.
     */
    const val AXIS_SCROLL_PER_DETENT = 1f
  }
}
