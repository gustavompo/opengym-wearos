package ch.duartesantos.opengym.wear.ui.rest

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.duartesantos.opengym.wear.data.WorkoutState
import ch.duartesantos.opengym.wear.theme.OpenGymWearTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * s screen, rendered on a device: the strings, the three controls, and 's cue schedule.
 *
 * The cue tests substitute a recording [RestToneSink] through [RestTimer]'s trailing parameter, so
 * they observe what the screen decided to play without making a sound.
 */
@RunWith(AndroidJUnit4::class)
class RestTimerTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun theCountdownTheNextLineAndTheSetLineAreRendered() {
    setRestScreen(restState(remaining = 89, total = 120))

    composeTestRule.onNodeWithTag(REST_TIMER_TAG).assertIsDisplayed()
    composeTestRule.onNodeWithText("01:29").assertIsDisplayed()
    composeTestRule.onNodeWithText("Next: Lat Pulldown").assertIsDisplayed()
    composeTestRule.onNodeWithText("Set 2/4: 50.0 kg x 10").assertIsDisplayed()
    composeTestRule.onNodeWithText("-15").assertIsDisplayed()
    composeTestRule.onNodeWithText("+15").assertIsDisplayed()
    composeTestRule.onNodeWithText("Skip").assertIsDisplayed()
  }

  @Test
  fun theCountdownShowsTheStateItIsGiven() {
    var state by mutableStateOf(restState(remaining = 90, total = 90))
    composeTestRule.setContent {
      OpenGymWearTheme { RestTimer(state = state, onAdjustRest = {}, onSkipRest = {}) }
    }
    composeTestRule.onNodeWithText("01:30").assertIsDisplayed()

    composeTestRule.runOnIdle { state = restState(remaining = 7, total = 90) }
    composeTestRule.onNodeWithText("00:07").assertIsDisplayed()
  }

  /**
   * the countdown moved UP (offset -62 -> -75 px) and the `Next:` label takes two lines, so
   * the countdown's box now clears the `Next:` block entirely. Semantics bounds can't see the
   * bezel, but they can pin the vertical order this restructure promised.
   */
  @Test
  fun theCountdownClearsTheTwoLineNextBlock() {
    var state by mutableStateOf(
      restState(remaining = 89, total = 120).copy(
        exerciseName = "cable lat pulldown full range of motion",
      )
    )
    composeTestRule.setContent {
      OpenGymWearTheme { RestTimer(state = state, onAdjustRest = {}, onSkipRest = {}) }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("Next: cable lat pulldown full range of motion").assertIsDisplayed()
    val countdown = composeTestRule.onNodeWithTag(REST_TIMER_COUNTDOWN_TAG).fetchSemanticsNode().boundsInRoot
    val next = composeTestRule.onNodeWithTag(REST_TIMER_NEXT_TAG).fetchSemanticsNode().boundsInRoot
    val set = composeTestRule.onNodeWithTag(REST_TIMER_SET_TAG).fetchSemanticsNode().boundsInRoot

    // Two lines: the Next box is distinctly taller than its one-line predecessor would be.
    // (detail font ~14.66 dp: one line ~29 px, two lines ~59 px at 320 dpi.)
    assertTrue("Next box height %s".format(next.height), next.height >= 52f)
    assertTrue("Next box height %s".format(next.height), next.height < 90f)
    // Order and clearance, top to bottom: countdown, Next block, Set line.
    // Order and clearance, top to bottom: countdown, Next block, Set line. The countdown's
    // semantic box is taller than its ink (numeralLarge's own line metrics), so pin the centres:
    // the countdown's centre sits above the Next block's top, and the Next block clears the Set.
    assertTrue("countdown centre %s vs next top %s".format(countdown.center.y, next.top), countdown.center.y < next.top)
    assertTrue(next.bottom <= set.top)
  }

  /** the `-15` / `+15` stadiums are larger than the reference's 77 x 48 px. */
  @Test
  fun theRestPillsAreLargerThanTheReferenceTarget() {
    setRestScreen(restState(remaining = 89, total = 120))

    val minus = composeTestRule.onNodeWithTag(REST_TIMER_MINUS_TAG).fetchSemanticsNode().boundsInRoot
    val plus = composeTestRule.onNodeWithTag(REST_TIMER_PLUS_TAG).fetchSemanticsNode().boundsInRoot
    // 84/305 x 227 dp = 62.5 dp = 125 px wide; the reference was 77/305 = 57.3 dp = 114.6 px.
    // Assert we are past the reference width and both pills are equal.
    assertTrue("minus width %s".format(minus.width), minus.width > 116f)
    assertEquals(minus.width, plus.width, 2f)
    assertEquals(minus.height, plus.height, 2f)
  }

  @Test
  fun thePillsAndSkipReportTheirCommands() {
    val adjustments = mutableListOf<Int>()
    var skips = 0
    setRestScreen(
      restState(remaining = 89, total = 120),
      onAdjustRest = { adjustments += it },
      onSkipRest = { skips++ },
    )

    composeTestRule.onNodeWithTag(REST_TIMER_MINUS_TAG).performClick()
    composeTestRule.onNodeWithTag(REST_TIMER_PLUS_TAG).performClick()
    composeTestRule.onNodeWithTag(REST_TIMER_SKIP_TAG).performClick()
    composeTestRule.waitForIdle()

    assertEquals(listOf(-15, 15), adjustments)
    assertEquals(1, skips)
  }

  @Test
  fun fiveTicksAreScheduledAndTheEndBeepsOneSecondAheadOfZero() {
    var state by mutableStateOf(restState(remaining = 6, total = 90))
    val sink = RecordingSink()
    composeTestRule.setContent {
      OpenGymWearTheme {
        RestTimer(state = state, onAdjustRest = {}, onSkipRest = {}, toneSink = sink)
      }
    }
    composeTestRule.waitForIdle()
    assertEquals("the first observation only sets the baseline", emptyList<String>(), sink.played)

    for (remaining in 5 downTo 1) {
      composeTestRule.runOnIdle { state = restState(remaining = remaining, total = 90) }
      composeTestRule.waitForIdle()
    }

    assertEquals(listOf("Tick", "Tick", "Tick", "Tick", "Tick"), sink.played)
    assertEquals(
      "each second is rescheduled, so the last request is one second before zero",
      1_000L,
      sink.scheduleRequests.last(),
    )
    assertEquals(listOf(5_000L, 4_000L, 3_000L, 2_000L, 1_000L), sink.scheduleRequests)
  }

  /**
   * The lifecycle the real app has: `WorkoutState.isResting` goes false at zero, so `WearApp` swaps
   * this screen for the exercise page and this composable is disposed - it never observes
   * `remaining == 0`. The beeps must still arrive.
   */
  @Test
  fun theScheduledBeepsSurviveTheScreenBeingDisposed() {
    var state by mutableStateOf(restState(remaining = 1, total = 90))
    var showing by mutableStateOf(true)
    val sink = DelayedSink()
    composeTestRule.setContent {
      OpenGymWearTheme {
        if (showing) {
          RestTimer(state = state, onAdjustRest = {}, onSkipRest = {}, toneSink = sink)
        }
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.runOnIdle { showing = false }
    composeTestRule.waitForIdle()
    composeTestRule.waitUntil(timeoutMillis = 3_000L) { sink.beeps >= BEEPS_AT_ZERO }

    assertEquals(BEEPS_AT_ZERO, sink.beeps)
  }

  @Test
  fun skippingInTheFinalSecondCancelsTheScheduledBeeps() {
    var state by mutableStateOf(restState(remaining = 1, total = 90))
    var showing by mutableStateOf(true)
    val sink = DelayedSink()
    composeTestRule.setContent {
      OpenGymWearTheme {
        if (showing) {
          RestTimer(state = state, onAdjustRest = {}, onSkipRest = { showing = false }, toneSink = sink)
        }
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag(REST_TIMER_SKIP_TAG).performClick()
    composeTestRule.waitForIdle()
    Thread.sleep(1_600L)
    composeTestRule.waitForIdle()

    assertEquals(0, sink.beeps)
    assertEquals(1, sink.cancels)
  }

  // --- helpers ---------------------------------------------------------------------------------

  private fun setRestScreen(
    state: WorkoutState,
    onAdjustRest: (Int) -> Unit = {},
    onSkipRest: () -> Unit = {},
  ) {
    composeTestRule.setContent {
      OpenGymWearTheme {
        RestTimer(state = state, onAdjustRest = onAdjustRest, onSkipRest = onSkipRest)
      }
    }
    composeTestRule.waitForIdle()
  }

  private fun restState(remaining: Int, total: Int) =
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
      restSecondsRemaining = remaining,
      restSecondsTotal = total,
    )

  /** Records cues; `scheduleBeeps` is noted but never fired. */
  private class RecordingSink : RestToneSink {
    val played = mutableListOf<String>()
    val scheduleRequests = mutableListOf<Long>()

    override fun play(tone: RestTone) {
      played += tone.name
    }

    override fun scheduleBeeps(delayMillis: Long) {
      scheduleRequests += delayMillis
    }

    override fun cancelBeeps() = Unit
  }

  /** Fires scheduled beeps on a real background scope, like the device sink does. */
  private class DelayedSink : RestToneSink {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val lock = Any()
    private var job: Job? = null

    @Volatile var beeps: Int = 0

    @Volatile var cancels: Int = 0

    override fun play(tone: RestTone) {
      if (tone == RestTone.Beep) beeps++
    }

    override fun scheduleBeeps(delayMillis: Long) {
      synchronized(lock) {
        job?.cancel()
        job = scope.launch {
          delay(delayMillis)
          repeat(BEEPS_AT_ZERO) { play(RestTone.Beep) }
        }
      }
    }

    override fun cancelBeeps() {
      cancels++
      synchronized(lock) {
        job?.cancel()
        job = null
      }
    }
  }
}
