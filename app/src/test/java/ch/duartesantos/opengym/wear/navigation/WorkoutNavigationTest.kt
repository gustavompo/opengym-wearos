package ch.duartesantos.opengym.wear.navigation

import ch.duartesantos.opengym.wear.data.ExerciseSelection
import ch.duartesantos.opengym.wear.data.WorkoutRow
import ch.duartesantos.opengym.wear.data.WorkoutState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan §2.4, rule by rule. The state machine is a plain class precisely so these need no device: the
 * failure this file exists to catch — a tapped row that never resolves, or a screen that switches
 * before the phone's projection caught up — is invisible in a UI test that just clicks and waits.
 */
class WorkoutNavigationTest {

  private fun state(
    restSecondsRemaining: Int = 0,
    active: Boolean = true,
    exerciseIndex: Int = 0,
    exerciseCount: Int = 3,
  ): WorkoutState =
    WorkoutState.None.copy(
      isWorkoutActive = active,
      exerciseName = "Lat Pulldown",
      exerciseIndex = exerciseIndex,
      exerciseCount = exerciseCount,
      currentSetIndex = 2,
      totalSets = 4,
      restSecondsRemaining = restSecondsRemaining,
      restSecondsTotal = 90,
      workoutName = "Full Body A",
      startedAtMillis = 1_791_665_000_000L,
      exercises = List(exerciseCount) { WorkoutRow(it, "Exercise $it", 0, 3) },
    )

  // --- rule 1: the rest wins, whatever the user chose -------------------------------------------

  @Test
  fun `the rest timer is the screen while the rest clock runs, whatever was chosen`() {
    val nav = WorkoutNavigation()
    nav.openWorkoutPage()

    assertEquals(Destination.RestTimer, nav.screen(state(restSecondsRemaining = 89)))
    assertEquals(Destination.RestTimer, nav.screen(state(restSecondsRemaining = 1)))
  }

  @Test
  fun `the chosen screen comes back when the rest ends`() {
    val nav = WorkoutNavigation()
    nav.openWorkoutPage()

    assertEquals(Destination.RestTimer, nav.screen(state(restSecondsRemaining = 5)))
    assertEquals(Destination.WorkoutPage, nav.screen(state(restSecondsRemaining = 0)))
  }

  @Test
  fun `the rest wins even when the workout has ended - rule 1 is unconditional`() {
    // The two rules can only collide if the phone keeps a rest running with no workout, which its own
    // projection does not do; the order is pinned so it cannot be left to chance.
    val nav = WorkoutNavigation()
    assertEquals(Destination.RestTimer, nav.screen(state(restSecondsRemaining = 30, active = false)))
  }

  // --- rule 2: one piece of user state ----------------------------------------------------------

  @Test
  fun `the chosen screen starts on the exercise page`() {
    assertEquals(Destination.ExercisePage, WorkoutNavigation().chosen)
  }

  @Test
  fun `the rest timer can never be the chosen screen`() {
    val failure = runCatching { WorkoutNavigation(Destination.RestTimer) }.exceptionOrNull()
    assertTrue("expected an IllegalArgumentException, got $failure", failure is IllegalArgumentException)
  }

  // --- rule 3: BACK -----------------------------------------------------------------------------

  @Test
  fun `back from the exercise page goes to the list`() {
    val nav = WorkoutNavigation()
    assertEquals(Destination.ExercisePage, nav.chosen)

    assertFalse("the activity must not finish", nav.back())
    assertEquals(Destination.WorkoutPage, nav.chosen)
    assertEquals(Destination.WorkoutPage, nav.screen(state()))
  }

  @Test
  fun `back from the list finishes the activity`() {
    val nav = WorkoutNavigation()
    nav.openWorkoutPage()

    assertTrue("the system default must run", nav.back())
  }

  @Test
  fun `back from the exercise page twice, with nothing in between, reaches finish`() {
    val nav = WorkoutNavigation()
    assertFalse(nav.back())
    assertTrue(nav.back())
  }

  // --- rule 4: the first exercise's chevron -----------------------------------------------------

  @Test
  fun `the first-exercise chevron opens the list`() {
    val nav = WorkoutNavigation()
    nav.openWorkoutPage()
    assertEquals(Destination.WorkoutPage, nav.screen(state()))
  }

  @Test
  fun `opening the list from the chevron also drops any pending row`() {
    val nav = WorkoutNavigation()
    nav.onRowTap(2, state())
    assertEquals(2, nav.pendingExerciseIndex)

    nav.openWorkoutPage()
    assertNull(nav.pendingExerciseIndex)
    assertEquals(Destination.WorkoutPage, nav.chosen)
  }

  // --- rule 5: no workout forces the exercise page ----------------------------------------------

  @Test
  fun `no active workout forces the exercise page, and does not invent an empty list`() {
    val nav = WorkoutNavigation()
    nav.openWorkoutPage()

    assertEquals(Destination.ExercisePage, nav.screen(WorkoutState.None))
    nav.onState(WorkoutState.None)
    assertEquals(Destination.ExercisePage, nav.chosen)
    // The next workout must not drop the user back onto the list they were on when the last one ended.
    assertEquals(Destination.ExercisePage, nav.screen(state(restSecondsRemaining = 0)))
  }

  @Test
  fun `no active workout also clears a pending row`() {
    val nav = WorkoutNavigation()
    nav.onRowTap(2, state())
    nav.onState(WorkoutState.None)

    assertNull(nav.pendingExerciseIndex)
  }

  // --- rule 6: the tap --------------------------------------------------------------------------

  @Test
  fun `a tap on the current row switches immediately and sends no command`() {
    val nav = WorkoutNavigation()
    nav.openWorkoutPage()

    val command = nav.onRowTap(0, state(exerciseIndex = 0))

    assertNull("nothing to move, so nothing to send", command)
    assertEquals(Destination.ExercisePage, nav.chosen)
    assertNull(nav.pendingExerciseIndex)
  }

  @Test
  fun `a tap on another row sends exactly one selectExercise and stays on the list, pending`() {
    val nav = WorkoutNavigation()
    nav.openWorkoutPage()
    val current = state(exerciseIndex = 0)

    val command = nav.onRowTap(2, current)

    assertEquals(2, command)
    assertEquals(2, nav.pendingExerciseIndex)
    // Still the list: the exercise page renders the phone's projection, so switching now would paint
    // the exercise the user just moved away from.
    assertEquals(Destination.WorkoutPage, nav.chosen)
    assertEquals(Destination.WorkoutPage, nav.screen(current))
  }

  @Test
  fun `the pending row switches the screen only when a state carries its index`() {
    val nav = WorkoutNavigation()
    nav.openWorkoutPage()
    nav.onRowTap(2, state(exerciseIndex = 0))

    // The phone publishes something else first (another set ticked, say): the wait continues.
    nav.onState(state(exerciseIndex = 0))
    assertEquals(2, nav.pendingExerciseIndex)
    assertEquals(Destination.WorkoutPage, nav.chosen)

    nav.onState(state(exerciseIndex = 2))
    assertNull(nav.pendingExerciseIndex)
    assertEquals(Destination.ExercisePage, nav.chosen)
  }

  @Test
  fun `an applied ack alone does not clear the pending row`() {
    val nav = WorkoutNavigation()
    nav.openWorkoutPage()
    nav.onRowTap(2, state(exerciseIndex = 0))

    // §2.2 guarantees a matching `state` after an `applied`, so clearing here would switch the screen
    // before the projection caught up - the exact frame the rule exists to prevent.
    nav.onSelection(ExerciseSelection.Pending(2))
    nav.onSelection(ExerciseSelection.Idle)

    assertEquals(2, nav.pendingExerciseIndex)
    assertEquals(Destination.WorkoutPage, nav.chosen)
  }

  @Test
  fun `a rejected ack clears the pending row without switching`() {
    val nav = WorkoutNavigation()
    nav.openWorkoutPage()
    nav.onRowTap(2, state(exerciseIndex = 0))

    nav.onSelection(ExerciseSelection.Abandoned(2))

    assertNull(nav.pendingExerciseIndex)
    assertEquals("the list stays", Destination.WorkoutPage, nav.chosen)
  }

  @Test
  fun `an abandoned selection for another row is ignored`() {
    val nav = WorkoutNavigation()
    nav.openWorkoutPage()
    nav.onRowTap(2, state(exerciseIndex = 0))

    nav.onSelection(ExerciseSelection.Abandoned(1))

    assertEquals(2, nav.pendingExerciseIndex)
  }

  @Test
  fun `the first back while a row is pending abandons the tap and the second finishes`() {
    val nav = WorkoutNavigation()
    nav.openWorkoutPage()
    nav.onRowTap(2, state(exerciseIndex = 0))

    assertFalse("the list stays", nav.back())
    assertNull(nav.pendingExerciseIndex)
    assertEquals(Destination.WorkoutPage, nav.chosen)
    assertTrue("and now the system default applies", nav.back())
  }

  @Test
  fun `a pending row cannot outlive a second tap`() {
    val nav = WorkoutNavigation()
    nav.openWorkoutPage()
    assertEquals(1, nav.onRowTap(1, state(exerciseIndex = 0)))
    assertEquals(2, nav.onRowTap(2, state(exerciseIndex = 0)))

    assertEquals(2, nav.pendingExerciseIndex)
    nav.onState(state(exerciseIndex = 1))
    assertEquals("the first tap's index is no longer waited for", 2, nav.pendingExerciseIndex)
  }
}
