package ch.duartesantos.opengym.wear.ui.exercise

import ch.duartesantos.opengym.wear.data.WorkoutState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure half of the page: number formatting, the wheel's neighbours, and the `prev.` hint.
 *
 * These run as plain JVM tests because none of them touches Compose or the repository — the
 * instrumented [ExercisePageTest] covers the wiring, this covers the arithmetic at the bounds,
 * which is where a spinner goes wrong.
 */
class ExercisePageBoundsTest {

  @Test
  fun weightAlwaysHasOneDecimalInEveryLocale() {
    assertEquals("50.0", formatWeight(50.0))
    assertEquals("0.0", formatWeight(0.0))
    assertEquals("500.0", formatWeight(500.0))
    assertEquals("62.5", formatWeight(62.5))
  }

  @Test
  fun weightNeighboursAreHalfAKilogramEitherSide() {
    assertEquals("49.5", weightNeighbour(50.0, -0.5))
    assertEquals("50.5", weightNeighbour(50.0, +0.5))
    assertEquals("0.5", weightNeighbour(0.0, +0.5))
    assertEquals("499.5", weightNeighbour(500.0, -0.5))
  }

  @Test
  fun weightNeighboursAreBlankPastTheEndsOfTheRange() {
    assertNull(weightNeighbour(0.0, -0.5))
    assertNull(weightNeighbour(500.0, +0.5))
  }

  @Test
  fun repsNeighboursAreOneEitherSideAndBlankPastTheEnds() {
    assertEquals("9", repsNeighbour(10, -1))
    assertEquals("11", repsNeighbour(10, +1))
    assertNull(repsNeighbour(1, -1))
    assertNull(repsNeighbour(99, +1))
  }

  @Test
  fun previousSetHintDescribesBothFields() {
    assertEquals("prev. 50.0 kg x 10", previousSetHint(state(weightKg = 50.0, reps = 10)))
    assertEquals("prev. 62.5 kg x 8", previousSetHint(state(weightKg = 62.5, reps = 8)))
  }

  @Test
  fun previousSetHintIsNullWhenEitherFieldIsMissing() {
    assertNull(previousSetHint(state(weightKg = null, reps = 10)))
    assertNull(previousSetHint(state(weightKg = 50.0, reps = null)))
    assertNull(previousSetHint(state(weightKg = null, reps = null)))
  }

  private fun state(weightKg: Double?, reps: Int?) =
    WorkoutState(
      isWorkoutActive = true,
      exerciseName = "Lat Pulldown",
      exerciseIndex = 0,
      exerciseCount = 4,
      currentSetIndex = 1,
      totalSets = 4,
      weightKg = 50.0,
      reps = 10,
      previousWeightKg = weightKg,
      previousReps = reps,
      restSecondsRemaining = 0,
      restSecondsTotal = 90,
    )
}
