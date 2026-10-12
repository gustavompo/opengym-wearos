package ch.duartesantos.opengym.wear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the state model: set-index derivation, the `prev.` lookup, and the clamps that
 * every write goes through. No coroutines, no clock — these are the rules the screens rely on.
 */
class WorkoutProjectionTest {

  private fun set(done: Boolean, weightKg: Double = 50.0, reps: Int = 10): WorkoutSet =
    WorkoutSet(weightKg = weightKg, reps = reps, done = done)

  @Test
  fun `set index is the count of done sets plus one`() {
    assertEquals(1, listOf(set(false), set(false), set(false)).currentSetIndex())
    assertEquals(2, listOf(set(true), set(false), set(false)).currentSetIndex())
    assertEquals(3, listOf(set(true), set(true), set(false)).currentSetIndex())
  }

  @Test
  fun `set index clamps to the set count rather than reading 5 of 4`() {
    val sets = listOf(set(true), set(true), set(true), set(true))

    assertEquals(4, sets.currentSetIndex())
    assertEquals(-1, sets.openSetIndex())
    assertNull(sets.openSet())
  }

  @Test
  fun `set index of an empty list is zero`() {
    assertEquals(0, emptyList<WorkoutSet>().currentSetIndex())
  }

  @Test
  fun `the open set is the first one not done`() {
    val sets = listOf(set(true, weightKg = 40.0), set(false, weightKg = 45.0))

    assertEquals(45.0, sets.openSet()!!.weightKg, 0.0)
    assertEquals(1, sets.openSetIndex())
  }

  @Test
  fun `the previous set is the last done set before the open one`() {
    val sets = listOf(set(true, reps = 8), set(true, reps = 9), set(false))

    assertEquals(9, sets.previousDoneSet()!!.reps)
  }

  @Test
  fun `there is no previous set when nothing has been done yet`() {
    assertNull(listOf(set(false), set(false)).previousDoneSet())
  }

  @Test
  fun `a fully done exercise still reports its last done set as previous`() {
    val sets = listOf(set(true, reps = 8), set(true, reps = 12))

    assertEquals(12, sets.previousDoneSet()!!.reps)
  }

  @Test
  fun `weight snaps to half kilos`() {
    assertEquals(50.0, clampWeightKg(50.2), 0.0)
    assertEquals(50.5, clampWeightKg(50.3), 0.0)
    assertEquals(49.5, clampWeightKg(49.5), 0.0)
    assertEquals(0.0, clampWeightKg(0.2), 0.0)
  }

  @Test
  fun `weight clamps to zero and five hundred`() {
    assertEquals(0.0, clampWeightKg(-30.0), 0.0)
    assertEquals(500.0, clampWeightKg(9_999.0), 0.0)
  }

  @Test
  fun `a NaN weight does not leak into the state`() {
    assertEquals(0.0, clampWeightKg(Double.NaN), 0.0)
  }

  @Test
  fun `reps clamp to one and ninety-nine`() {
    assertEquals(1, clampReps(-4))
    assertEquals(1, clampReps(0))
    assertEquals(12, clampReps(12))
    assertEquals(99, clampReps(1_000))
  }

  @Test
  fun `rest clamps to zero and ten minutes`() {
    assertEquals(0, clampRestSeconds(-15))
    assertEquals(90, clampRestSeconds(90))
    assertEquals(600, clampRestSeconds(10_000))
  }

  // --- projection -------------------------------------------------------------------------------

  private val workout =
    ActiveWorkout(
      name = "Test",
      currentExerciseIndex = 0,
      exercises =
        listOf(
          WorkoutExercise(
            id = "a",
            name = "Lat Pulldown",
            restSeconds = 90,
            sets = listOf(set(true, 50.0, 8), set(false, 52.5, 12)),
          )
        ),
      previousSession = mapOf("a" to LoggedSet(weightKg = 47.5, reps = 10)),
    )

  @Test
  fun `the projection carries the open set's weight and reps`() {
    val state = workout.toWorkoutState(restSecondsRemaining = 0, restSecondsTotal = 90)

    assertEquals("Lat Pulldown", state.exerciseName)
    assertEquals(2, state.currentSetIndex)
    assertEquals(2, state.totalSets)
    assertEquals(52.5, state.weightKg, 0.0)
    assertEquals(12, state.reps)
  }

  @Test
  fun `the projection prefers a set done this session over history`() {
    val state = workout.toWorkoutState(restSecondsRemaining = 0, restSecondsTotal = 90)

    assertEquals(50.0, state.previousWeightKg!!, 0.0)
    assertEquals(8, state.previousReps)
  }

  @Test
  fun `the projection falls back to history when nothing has been done this session`() {
    val fresh =
      workout.copy(
        exercises =
          listOf(
            workout.currentExercise.copy(
              sets = listOf(set(false, 60.0, 5), set(false, 60.0, 5))
            )
          )
      )

    val state = fresh.toWorkoutState(restSecondsRemaining = 0, restSecondsTotal = 90)

    assertEquals(47.5, state.previousWeightKg!!, 0.0)
    assertEquals(10, state.previousReps)
  }

  @Test
  fun `the projection reports no previous set when there is neither a session nor a history`() {
    val unknown =
      workout.copy(
        exercises =
          listOf(workout.currentExercise.copy(sets = listOf(set(false, 60.0, 5), set(false, 60.0, 5)))),
        previousSession = emptyMap(),
      )

    val state = unknown.toWorkoutState(restSecondsRemaining = 0, restSecondsTotal = 90)

    assertNull(state.previousWeightKg)
    assertNull(state.previousReps)
  }

  @Test
  fun `the projection passes rest through and derives isResting`() {
    val resting = workout.toWorkoutState(restSecondsRemaining = 34, restSecondsTotal = 90)
    val idle = workout.toWorkoutState(restSecondsRemaining = 0, restSecondsTotal = 90)

    assertEquals(34, resting.restSecondsRemaining)
    assertEquals(90, resting.restSecondsTotal)
    assertEquals(true, resting.isResting)
    assertEquals(false, idle.isResting)
  }

  @Test
  fun `the projection carries the exercise index and count`() {
    val state = workout.toWorkoutState(restSecondsRemaining = 0, restSecondsTotal = 90)

    assertEquals(0, state.exerciseIndex)
    assertEquals(1, state.exerciseCount)
  }

  @Test
  fun `the chevron flags are false at the first and last exercise`() {
    // A one-exercise workout is both the first and the last, so neither chevron can do anything.
    val only = workout.toWorkoutState(restSecondsRemaining = 0, restSecondsTotal = 90)
    assertFalse(only.canGoPrevious)
    assertFalse(only.canGoNext)

    val three =
      workout.copy(
        exercises = workout.exercises + workout.exercises + workout.exercises,
        currentExerciseIndex = 0,
      )

    val first = three.toWorkoutState(restSecondsRemaining = 0, restSecondsTotal = 90)
    assertEquals(0, first.exerciseIndex)
    assertEquals(3, first.exerciseCount)
    assertFalse(first.canGoPrevious)
    assertTrue(first.canGoNext)

    val middle = three.copy(currentExerciseIndex = 1).toWorkoutState(0, 90)
    assertTrue(middle.canGoPrevious)
    assertTrue(middle.canGoNext)

    val last = three.copy(currentExerciseIndex = 2).toWorkoutState(0, 90)
    assertEquals(2, last.exerciseIndex)
    assertTrue(last.canGoPrevious)
    assertFalse(last.canGoNext)
  }

  @Test
  fun `no workout means no navigation in either direction`() {
    assertFalse(WorkoutState.None.canGoPrevious)
    assertFalse(WorkoutState.None.canGoNext)
  }

  @Test
  fun `the seeded workout has four exercises with different set counts and weights`() {
    val seed = ActiveWorkout.FullBodyA

    assertEquals(0, seed.currentExerciseIndex)
    assertEquals(4, seed.exercises.size)
    assertEquals(
      listOf(4, 3, 5, 4),
      seed.exercises.map { it.sets.size },
    )
    assertEquals(
      listOf(50.0, 10.0, 60.0, 40.0),
      seed.exercises.map { it.sets.first().weightKg },
    )
    assertEquals(
      listOf(90, 60, 120, 90),
      seed.exercises.map { it.restSeconds },
    )
    // Every exercise can resolve the `prev. …` hint on its first set.
    assertEquals(seed.exercises.map { it.id }, seed.previousSession.keys.toList())
  }
}
