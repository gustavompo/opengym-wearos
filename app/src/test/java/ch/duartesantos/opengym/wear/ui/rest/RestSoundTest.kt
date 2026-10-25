package ch.duartesantos.opengym.wear.ui.rest

import android.media.AudioManager
import ch.duartesantos.opengym.wear.data.FakeWorkoutRepository
import ch.duartesantos.opengym.wear.data.TimeSource
import ch.duartesantos.opengym.wear.data.WeightUnit
import ch.duartesantos.opengym.wear.data.WorkoutState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s scheduling logic, on the JVM.
 *
 * The interesting tests are the ones at the bottom: they drive the *real* [FakeWorkoutRepository]
 * - the one the screens use - with a [TestScope]'s virtual clock, so a whole 90 second rest is
 * stepped through in microseconds and the tick/beep sequence is asserted exactly, with no `sleep`
 * and no wall-clock flakiness.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RestSoundTest {

  // --- the pure schedule -----------------------------------------------------------------------

  @Test
  fun eachSecondOfTheFinalWindowTicksOnce() {
    assertEquals(listOf(RestTone.Tick), restTonesFor(6, 5))
    assertEquals(listOf(RestTone.Tick), restTonesFor(5, 4))
    assertEquals(listOf(RestTone.Tick), restTonesFor(2, 1))
  }

  @Test
  fun nothingIsEmittedBeforeTheFinalWindow() {
    assertEquals(emptyList<RestTone>(), restTonesFor(90, 89))
    assertEquals(emptyList<RestTone>(), restTonesFor(6, 5, tickFromSeconds = 3))
  }

  @Test
  fun zeroEmitsThreeBeepsAndNoTick() {
    assertEquals(listOf(RestTone.Beep, RestTone.Beep, RestTone.Beep), restTonesFor(1, 0))
  }

  @Test
  fun aStepThatSkipsSecondsEmitsOneTickNotABurst() {
    // A coalesced ticker jumped the countdown from 6 to 3: one beat, not three.
    assertEquals(listOf(RestTone.Tick), restTonesFor(6, 3))
    // ...and a jump straight past the window still produces the alarm.
    assertEquals(listOf(RestTone.Beep, RestTone.Beep, RestTone.Beep), restTonesFor(6, 0))
  }

  @Test
  fun goingUpIsAlwaysSilent() {
    // A rest starting, and `+15` on a running one: neither may replay the ticks already heard.
    assertEquals(emptyList<RestTone>(), restTonesFor(0, 90))
    assertEquals(emptyList<RestTone>(), restTonesFor(4, 19))
    assertEquals(emptyList<RestTone>(), restTonesFor(5, 5))
  }

  // --- the silent / volume gate ----------------------------------------------------------------

  @Test
  fun silentAndVibrateRingerModesBlockSound() {
    assertFalse(soundAllowed(AudioManager.RINGER_MODE_SILENT, 5, false))
    assertFalse(soundAllowed(AudioManager.RINGER_MODE_VIBRATE, 5, false))
  }

  @Test
  fun normalRingerModeStillNeedsAudibleVolume() {
    assertTrue(soundAllowed(AudioManager.RINGER_MODE_NORMAL, 1, false))
    assertFalse(soundAllowed(AudioManager.RINGER_MODE_NORMAL, 0, false))
    assertFalse(soundAllowed(AudioManager.RINGER_MODE_NORMAL, 5, true))
    assertFalse(soundAllowed(AudioManager.RINGER_MODE_SILENT, 0, true))
  }

  // --- the strings the screen shows ------------------------------------------------------------

  @Test
  fun countdownIsTwoDigitMinutesAndSeconds() {
    assertEquals("01:29", formatCountdown(89))
    assertEquals("02:00", formatCountdown(120))
    assertEquals("00:05", formatCountdown(5))
    assertEquals("10:00", formatCountdown(600))
    assertEquals("00:00", formatCountdown(0))
    assertEquals("00:00", formatCountdown(-3))
  }

  @Test
  fun theSetLineDescribesTheSetTheRestIsFor() {
    assertEquals("Set 2/4: 50.0 kg x 10", formatUpcomingSet(state(remaining = 89, total = 120)))
  }

  @Test
  fun theSetLineFollowsTheProfileUnit() {
    assertEquals(
      "Set 2/4: 110.0 lb x 10",
      formatUpcomingSet(state(remaining = 89, total = 120, weightUnit = WeightUnit.Pounds)),
    )
  }

  // --- virtual time: the whole countdown --------------------------------------------------------

  /**
   * 90 seconds of rest, stepped one second at a time on a virtual clock, with the cues recorded as
   * `(virtual millis, tone)`.
   *
   * The loop stops feeding values once the countdown reaches zero, because that is what really
   * happens: `WorkoutState.isResting` goes false, `destinationFor` switches to the exercise page,
   * and this screen is disposed in the same recomposition. The beeps must still arrive.
   */
  @Test
  fun aNinetySecondRestTicksFiveTimesThenBeepsThreeTimes() {
    val scope = TestScope()
    val clock = TimeSource { scope.testScheduler.currentTime }
    val repository =
      FakeWorkoutRepository(timeSource = clock, scope = scope, tickIntervalMillis = 1_000L)
    val sink = RecordingSink(scope, clock)
    val controller = RestSoundController(sink)

    repository.completeSet()
    var remaining = repository.observeWorkout().value.restSecondsRemaining
    assertEquals("the fake's Lat Pulldown rest is 90 s", 90, remaining)
    controller.onRemainingChanged(remaining)

    while (remaining > 0) {
      scope.testScheduler.advanceTimeBy(1_000L)
      scope.testScheduler.runCurrent()
      remaining = repository.observeWorkout().value.restSecondsRemaining
      if (remaining > 0) controller.onRemainingChanged(remaining)
    }

    assertEquals(
      listOf("Tick", "Tick", "Tick", "Tick", "Tick", "Beep", "Beep", "Beep"),
      sink.cues.map { it.tone.name },
    )
    assertEquals("no tick before the final five seconds", 5, sink.cues.count { it.tone == RestTone.Tick })
    assertEquals("ticks start at 5 s remaining", 85_000L, sink.cues.first().atMillis)
    assertEquals(
      "the beeps land on the zero second",
      90_000L,
      sink.cues.first { it.tone == RestTone.Beep }.atMillis,
    )
    assertEquals(
      "each tick is rescheduled one second ahead of zero",
      listOf(5_000L, 4_000L, 3_000L, 2_000L, 1_000L),
      sink.scheduleRequests,
    )
    assertEquals("the scheduled sequence was never cancelled", 0, sink.cancels)
  }

  @Test
  fun skippingInTheFinalSecondSilencesTheScheduledBeeps() {
    val scope = TestScope()
    val clock = TimeSource { scope.testScheduler.currentTime }
    val repository =
      FakeWorkoutRepository(timeSource = clock, scope = scope, tickIntervalMillis = 1_000L)
    val sink = RecordingSink(scope, clock)
    val controller = RestSoundController(sink)

    repository.completeSet()
    controller.onRemainingChanged(90)
    repeat(89) {
      scope.testScheduler.advanceTimeBy(1_000L)
      scope.testScheduler.runCurrent()
      val now = repository.observeWorkout().value.restSecondsRemaining
      if (now > 0) controller.onRemainingChanged(now)
    }
    assertEquals("the countdown is on its last second", 1_000L, sink.scheduleRequests.last())

    // Exactly what the Skip button does.
    controller.cancelScheduledBeeps()
    scope.testScheduler.advanceTimeBy(5_000L)
    scope.testScheduler.runCurrent()

    assertEquals(listOf("Tick", "Tick", "Tick", "Tick", "Tick"), sink.cues.map { it.tone.name })
    assertEquals(1, sink.cancels)
  }

  @Test
  fun aRestartDoesNotReplayTheTicks() {
    val sink = ImmediateSink()
    val controller = RestSoundController(sink)

    controller.onRemainingChanged(90)
    controller.onRemainingChanged(4)
    controller.onRemainingChanged(19) // `+15`
    controller.onRemainingChanged(18)
    controller.onRemainingChanged(17)
    controller.onRemainingChanged(16)
    controller.onRemainingChanged(15)

    assertEquals(listOf("Tick"), sink.cues)
  }

  @Test
  fun enteringTheScreenMidRestDoesNotTickForSecondsTheUserDidNotSee() {
    val sink = ImmediateSink()
    val controller = RestSoundController(sink)
    controller.onRemainingChanged(3) // first observation, already inside the window
    assertEquals(emptyList<String>(), sink.cues)
    controller.onRemainingChanged(2)
    assertEquals(listOf("Tick"), sink.cues)
  }

  // --- helpers ---------------------------------------------------------------------------------

  private fun state(
    remaining: Int,
    total: Int,
    weightUnit: WeightUnit = WeightUnit.Kilograms,
  ) =
    WorkoutState(
      weightUnit = weightUnit,
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

  private data class Cue(val atMillis: Long, val tone: RestTone)

  /** Records cues; runs scheduled beeps on the virtual clock it is given. */
  private class RecordingSink(private val scope: CoroutineScope, private val clock: TimeSource) :
    RestToneSink {
    val cues = mutableListOf<Cue>()
    val scheduleRequests = mutableListOf<Long>()
    var cancels = 0
    private var job: Job? = null

    override fun play(tone: RestTone) {
      cues += Cue(clock.nowMillis(), tone)
    }

    override fun scheduleBeeps(delayMillis: Long) {
      scheduleRequests += delayMillis
      job?.cancel()
      job =
        scope.launch {
          delay(delayMillis)
          // No inter-beep delay here: this double is about *when* the sequence is released, and a
          // suspend between the beeps would need another virtual-clock advance to observe.
          repeat(BEEPS_AT_ZERO) { play(RestTone.Beep) }
        }
    }

    override fun cancelBeeps() {
      cancels++
      job?.cancel()
      job = null
    }
  }

  /** Plays immediately, in call order: for the controller tests that need no clock at all. */
  private class ImmediateSink : RestToneSink {
    val cues = mutableListOf<String>()

    override fun play(tone: RestTone) {
      cues += tone.name
    }

    override fun scheduleBeeps(delayMillis: Long) = Unit

    override fun cancelBeeps() = Unit
  }
}
