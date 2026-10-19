package ch.duartesantos.opengym.wear.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fake exists so the screens can be built and tested before the phone bridge. These tests
 * pin the behaviour the screens depend on, and prove the rest countdown can be driven instantly.
 *
 * Rest time is driven by the test scheduler's virtual clock, so `advanceTimeBy(90_000)` runs a
 * whole countdown without a single real second passing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeWorkoutRepositoryTest {

  private fun TestScope.fake(tickIntervalMillis: Long = 1_000L): FakeWorkoutRepository =
    FakeWorkoutRepository(
      timeSource = { testScheduler.currentTime },
      scope = backgroundScope,
      tickIntervalMillis = tickIntervalMillis,
    )

  @Test
  fun `starts on the first set of the first exercise`() = runTest {
    val state = fake().observeWorkout().value

    assertTrue(state.isWorkoutActive)
    assertEquals("Lat Pulldown", state.exerciseName)
    assertEquals(1, state.currentSetIndex)
    assertEquals(4, state.totalSets)
    assertEquals(50.0, state.weightKg, 0.0)
    assertEquals(10, state.reps)
    assertFalse(state.isResting)
    assertEquals(0, state.restSecondsRemaining)
    // Idle: the honest total is the rest the current exercise will start.
    assertEquals(90, state.restSecondsTotal)
  }

  @Test
  fun `the first set takes its prev hint from last session's history`() = runTest {
    val state = fake().observeWorkout().value

    assertEquals(50.0, state.previousWeightKg!!, 0.0)
    assertEquals(10, state.previousReps)
  }

  @Test
  fun `completing a set advances the counter and starts that exercise's rest`() = runTest {
    val repository = fake()
    repository.completeSet()

    val state = repository.observeWorkout().value
    assertEquals(2, state.currentSetIndex)
    assertEquals(4, state.totalSets)
    assertTrue(state.isResting)
    assertEquals(90, state.restSecondsRemaining)
    assertEquals(90, state.restSecondsTotal)
  }

  @Test
  fun `a completed set becomes the prev hint, and the weight change belongs to it alone`() =
    runTest {
      val repository = fake()
      repository.adjustWeight(2.5)
      repository.completeSet()

      val state = repository.observeWorkout().value
      assertEquals(52.5, state.previousWeightKg!!, 0.0)
      assertEquals(10, state.previousReps)
      // The next set still carries the seeded weight.
      assertEquals(50.0, state.weightKg, 0.0)
    }

  @Test
  fun `rest counts down and ends without real waiting`() = runTest {
    val repository = fake()
    repository.completeSet()

    advanceTimeBy(30_000)
    runCurrent()
    assertEquals(60, repository.observeWorkout().value.restSecondsRemaining)

    advanceTimeBy(60_000)
    runCurrent()
    val finished = repository.observeWorkout().value
    assertEquals(0, finished.restSecondsRemaining)
    assertFalse(finished.isResting)
  }

  @Test
  fun `a partial second of rest rounds up rather than being skipped`() = runTest {
    // A tick every half second makes the rounding observable.
    val repository = fake(tickIntervalMillis = 500L)
    repository.completeSet()

    advanceTimeBy(500L)
    runCurrent()
    assertEquals(90, repository.observeWorkout().value.restSecondsRemaining)

    advanceTimeBy(1_000L)
    runCurrent()
    assertEquals(89, repository.observeWorkout().value.restSecondsRemaining)
  }

  @Test
  fun `adjusting rest shifts the running countdown and its total`() = runTest {
    val repository = fake()
    repository.completeSet()
    advanceTimeBy(10_000)
    runCurrent()
    assertEquals(80, repository.observeWorkout().value.restSecondsRemaining)

    repository.adjustRest(15)
    val extended = repository.observeWorkout().value
    assertEquals(95, extended.restSecondsRemaining)
    assertEquals(105, extended.restSecondsTotal)

    repository.adjustRest(-1_000)
    val ended = repository.observeWorkout().value
    assertEquals(0, ended.restSecondsRemaining)
    assertFalse(ended.isResting)
  }

  @Test
  fun `adjusting rest is a no-op when nothing is resting`() = runTest {
    val repository = fake()
    repository.adjustRest(15)

    val state = repository.observeWorkout().value
    assertEquals(0, state.restSecondsRemaining)
    assertEquals(90, state.restSecondsTotal)
  }

  @Test
  fun `skip ends the rest immediately`() = runTest {
    val repository = fake()
    repository.completeSet()
    repository.skipRest()

    val state = repository.observeWorkout().value
    assertEquals(0, state.restSecondsRemaining)
    assertFalse(state.isResting)
  }

  @Test
  fun `weight snaps to half kilos and clamps at both ends`() = runTest {
    val repository = fake()

    repository.adjustWeight(-100.0)
    assertEquals(0.0, repository.observeWorkout().value.weightKg, 0.0)

    repository.adjustWeight(10_000.0)
    assertEquals(500.0, repository.observeWorkout().value.weightKg, 0.0)

    repository.adjustWeight(-0.6)
    assertEquals(499.5, repository.observeWorkout().value.weightKg, 0.0)
  }

  @Test
  fun `an adjustment smaller than half a kilo snaps back to where it started`() = runTest {
    val repository = fake()
    repository.adjustWeight(0.1)

    assertEquals(50.0, repository.observeWorkout().value.weightKg, 0.0)
  }

  @Test
  fun `reps clamp to one and ninety-nine`() = runTest {
    val repository = fake()

    repository.adjustReps(-100)
    assertEquals(1, repository.observeWorkout().value.reps)

    repository.adjustReps(1_000)
    assertEquals(99, repository.observeWorkout().value.reps)
  }

  @Test
  fun `finishing an exercise moves to the next one and rests for the exercise just done`() =
    runTest {
      val repository = fake()
      repeat(4) { repository.completeSet() }

      val state = repository.observeWorkout().value
      assertEquals("Dumbbell Row", state.exerciseName)
      assertEquals(1, state.currentSetIndex)
      assertEquals(3, state.totalSets)
      assertEquals(10.0, state.weightKg, 0.0)
      assertEquals(10, state.reps)
      // The rest that just started belongs to the exercise that was completed.
      assertEquals(90, state.restSecondsRemaining)
    }

  @Test
  fun `a fresh exercise falls back to history for the prev hint`() = runTest {
    val repository = fake()
    repeat(4) { repository.completeSet() }

    // Dumbbell Row has no done set this session, so the hint is last session's 10.0 kg x 10.
    assertEquals(10.0, repository.observeWorkout().value.previousWeightKg!!, 0.0)
    assertEquals(10, repository.observeWorkout().value.previousReps)
  }

  @Test
  fun `exercise navigation clamps at both ends`() = runTest {
    val repository = fake()

    repository.previousExercise()
    assertEquals("Lat Pulldown", repository.observeWorkout().value.exerciseName)

    repository.nextExercise()
    assertEquals("Dumbbell Row", repository.observeWorkout().value.exerciseName)

    repeat(10) { repository.nextExercise() }
    assertEquals("Bench Press", repository.observeWorkout().value.exerciseName)
  }

  @Test
  fun `the state carries the exercise index and count, and the chevron flags follow`() = runTest {
    val repository = fake()

    val first = repository.observeWorkout().value
    assertEquals(0, first.exerciseIndex)
    assertEquals(4, first.exerciseCount)
    assertFalse(first.canGoPrevious)
    assertTrue(first.canGoNext)

    repository.nextExercise()
    val middle = repository.observeWorkout().value
    assertEquals(1, middle.exerciseIndex)
    assertEquals(4, middle.exerciseCount)
    assertTrue(middle.canGoPrevious)
    assertTrue(middle.canGoNext)

    repeat(10) { repository.nextExercise() }
    val last = repository.observeWorkout().value
    assertEquals(3, last.exerciseIndex)
    assertEquals("Bench Press", last.exerciseName)
    assertTrue(last.canGoPrevious)
    assertFalse(last.canGoNext)
  }

  @Test
  fun `both chevron flags are false once the session has ended`() = runTest {
    val repository = fake()
    repeat(4 + 3 + 5 + 4) { repository.completeSet() }

    val state = repository.observeWorkout().value
    assertFalse(state.canGoPrevious)
    assertFalse(state.canGoNext)
  }

  @Test
  fun `completing the session's last set ends the session`() = runTest {
    val repository = fake()
    repeat(4 + 3 + 5 + 4) { repository.completeSet() }

    val state = repository.observeWorkout().value
    assertFalse(state.isWorkoutActive)
    assertEquals(WorkoutState.None, state)
    assertEquals(0, state.restSecondsRemaining)
  }

  @Test
  fun `every command is a no-op without a session`() = runTest {
    val repository = fake()
    repeat(4 + 3 + 5 + 4) { repository.completeSet() }

    repository.completeSet()
    repository.nextExercise()
    repository.previousExercise()
    repository.adjustWeight(5.0)
    repository.adjustReps(5)
    repository.adjustRest(15)
    repository.skipRest()

    assertEquals(WorkoutState.None, repository.observeWorkout().value)
  }
}
