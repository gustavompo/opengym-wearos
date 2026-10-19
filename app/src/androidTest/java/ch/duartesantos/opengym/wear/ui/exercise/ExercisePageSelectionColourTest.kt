package ch.duartesantos.opengym.wear.ui.exercise

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.duartesantos.opengym.wear.data.ActiveWorkout
import ch.duartesantos.opengym.wear.data.FakeWorkoutRepository
import ch.duartesantos.opengym.wear.data.TimeSource
import ch.duartesantos.opengym.wear.theme.OpenGymBlue
import ch.duartesantos.opengym.wear.theme.OpenGymMuted
import ch.duartesantos.opengym.wear.theme.OpenGymWearTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * on the device: the `Kg` / `Reps` labels are read back from the **rendered pixels**, so this
 * fails if the colour is right in the Kotlin and wrong on the panel.
 *
 * Each label node is captured with `captureToImage` and its pixels counted against the two
 * expected values — [OpenGymBlue] `(29,130,234)` for the selected column, [OpenGymMuted]
 * `(170,170,170)` for the other. Anti-aliasing means the count is a measure of *which* colour the
 * glyph ink is, and the assertion is "most of the ink is the expected one and almost none of it is
 * the other", which is exactly the claim the user made.
 *
 * The independent, human-checkable version of this evidence — `adb screencap` + PIL on the real
 * framebuffer, both states.
 */
@RunWith(AndroidJUnit4::class)
class ExercisePageSelectionColourTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private fun show(workout: ActiveWorkout = ActiveWorkout.FullBodyA): FakeWorkoutRepository {
    val repository =
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
    return repository
  }

  /** How many of a label's pixels are within [tolerance] per channel of [target]. */
  private fun inkMatching(label: String, target: Color, tolerance: Int = 24): Int {
    val pixels = rule.onNodeWithText(label).captureToImage().toPixelMap()
    val wanted = target.toArgb()
    var hits = 0
    for (y in 0 until pixels.height) {
      for (x in 0 until pixels.width) {
        val p = pixels[x, y].toArgb()
        val a = (p shr 24) and 0xFF
        if (a < 200) continue
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        if (
          abs(r - ((wanted shr 16) and 0xFF)) <= tolerance &&
            abs(g - ((wanted shr 8) and 0xFF)) <= tolerance &&
            abs(b - (wanted and 0xFF)) <= tolerance
        ) {
          hits++
        }
      }
    }
    return hits
  }

  private fun assertKgSelected() {
    val kgBlue = inkMatching("Kg", OpenGymBlue)
    val repsBlue = inkMatching("Reps", OpenGymBlue)
    val kgMuted = inkMatching("Kg", OpenGymMuted)
    assertTrue("Kg should carry accent ink, got $kgBlue px", kgBlue > 40)
    assertEquals("Reps should carry no accent ink, got $repsBlue px", 0, repsBlue)
    assertTrue("Kg should carry no muted ink, got $kgMuted px", kgMuted < 5)
  }

  /** The fresh page, no interaction at all: the request's easy-to-get-wrong half. */
  @Test
  fun onFirstRenderTheWeightColumnIsTheSelectedOne() {
    show()
    rule.waitForIdle()
    assertKgSelected()
  }

  /** Tapping the Reps card moves the crown target; the colour must follow immediately. */
  @Test
  fun tappingTheRepsCardMovesTheAccentToReps() {
    val repository = show()
    rule.waitForIdle()
    assertKgSelected()

    rule.onNodeWithTag(EXERCISE_PAGE_REPS_TAG).performClick()
    rule.waitForIdle()

    val repsBlue = inkMatching("Reps", OpenGymBlue)
    val kgBlue = inkMatching("Kg", OpenGymBlue)
    val repsMuted = inkMatching("Reps", OpenGymMuted)
    assertTrue("Reps should carry accent ink after the tap, got $repsBlue px", repsBlue > 40)
    assertEquals("Kg should carry no accent ink after the tap, got $kgBlue px", 0, kgBlue)
    assertTrue("Reps should carry no muted ink after the tap, got $repsMuted px", repsMuted < 5)

    // The tap genuinely re-routed the crown, not just the paint: one real SOURCE_ROTARY_ENCODER
    // detent moves the REPS and leaves the weight alone. Without this the test would only prove
    // that a tap repaints two Text nodes.
    injectRotaryDetent(1f)
    rule.waitForIdle()
    assertEquals(9, repository.observeWorkout().value.reps)
    assertEquals(50.0, repository.observeWorkout().value.weightKg, 0.0)
  }

  /** Tapping back restores the initial state — the mapping is symmetric, not one-way. */
  @Test
  fun tappingTheWeightCardMovesTheAccentBack() {
    show()
    rule.waitForIdle()
    rule.onNodeWithTag(EXERCISE_PAGE_REPS_TAG).performClick()
    rule.waitForIdle()
    rule.onNodeWithTag(EXERCISE_PAGE_WEIGHT_TAG).performClick()
    rule.waitForIdle()
    assertKgSelected()
  }

  /**
   * One crown detent through UiAutomation — the real `SOURCE_ROTARY_ENCODER` device with a real
   * `AXIS_SCROLL` value, i.e. exactly what the framework hands `onRotaryScrollEvent`. Copied from
   * [ExercisePageRotaryTest] rather than shared: both classes are file-disjoint test fixtures.
   */
  private fun injectRotaryDetent(axisScroll: Float) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val now = SystemClock.uptimeMillis()
    val coordinates =
      MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_SCROLL, axisScroll) }
    val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN }
    val event =
      MotionEvent.obtain(
        now, now, MotionEvent.ACTION_SCROLL, 1,
        arrayOf(properties), arrayOf(coordinates),
        0, 0, 1f, 1f, 0, 0,
        InputDevice.SOURCE_ROTARY_ENCODER, 0, 0, 0,
      )
    instrumentation.uiAutomation.injectInputEvent(event, true)
    instrumentation.waitForIdleSync()
  }
}
