package ch.duartesantos.opengym.wear.navigation

import androidx.compose.runtime.mutableStateOf
import ch.duartesantos.opengym.wear.data.ExerciseSelection
import ch.duartesantos.opengym.wear.data.WorkoutState

/**
 * The three screens (M7.3): the exercise page, the workout page's list of the session's exercises,
 * and the rest timer.
 *
 * The rest timer is **not** a user-chosen screen. It is drawn in place of the chosen one while the
 * rest clock runs, which is why [WorkoutNavigation] never stores it.
 */
enum class Destination {
  ExercisePage,
  WorkoutPage,
  RestTimer,
}

/**
 * Plan §2.4's navigation state machine — the whole of it, as a plain class so every rule is
 * testable without Compose.
 *
 * The rules it encodes, and nothing else:
 *
 *  1. `RestTimer` is the screen **iff the rest clock has time left**, whatever the user chose; when
 *     the rest ends the chosen screen comes back. (Unchanged from M2.5.)
 *  2. Exactly **one** piece of user-navigation state exists: [chosen]. It starts as
 *     [Destination.ExercisePage].
 *  3. BACK (the swipe-right gesture, and the system back key) from [Destination.ExercisePage] goes to
 *     [Destination.WorkoutPage]; from [Destination.WorkoutPage] it finishes the activity.
 *  4. `‹` on the first exercise opens the workout page too ([openWorkoutPage]); `›` and `‹` in the
 *     middle keep their existing meaning.
 *  5. With no active workout the chosen screen is forced back to [Destination.ExercisePage], which
 *     shows `emptyWorkoutMessage(link)`. An empty *list* is deliberately not invented.
 *  6. A tap on a row of the workout page never switches the screen on its own: it either needs no
 *     command (the row is already current) or it sends `selectExercise` and waits — [pendingExerciseIndex]
 *     — for a `state` that carries that index. The wait also ends without a state, on a `rejected`
 *     ack, on the retry budget being spent, or on a second BACK, and the list stays.
 *
 * The one thing this class deliberately does **not** decide is what BACK does while the rest screen
 * is drawn. The rest timer is an override of the *screen*, not of the user's choice (rules 1-2), so
 * BACK acts on [chosen] — see [back]. That is a judgement call the plan is silent about, and it is
 * recorded as such in `docs/notes/20-workout-page-findings.md`.
 *
 * @param initialChosen the screen the user is on before any navigation. Must not be the rest timer.
 */
class WorkoutNavigation(initialChosen: Destination = Destination.ExercisePage) {

  init {
    require(initialChosen != Destination.RestTimer) {
      "the rest timer is an override of the chosen screen, never a chosen screen (plan §2.4 rule 2)"
    }
  }

  /** The user's chosen screen. The only user-navigation state there is (rule 2). */
  val chosen: Destination get() = chosenState.value

  /**
   * The row whose `selectExercise` has not been confirmed yet, or null (rule 6).
   *
   * While this is set the list stays on screen and the switch to the exercise page waits, because the
   * exercise page renders the phone's projection: switching early would paint the *previous* exercise
   * for a frame.
   */
  val pendingExerciseIndex: Int? get() = pendingState.value

  /*
   * Both pieces of state are Compose snapshot state, so a mutation here recomposes the caller.
   * Reading them outside a composition (a plain JVM unit test) works unchanged — `mutableStateOf` is
   * a runtime object, not an Android one.
   */
  private val chosenState = mutableStateOf(initialChosen)
  private val pendingState = mutableStateOf<Int?>(null)

  /**
   * The screen to draw: the rest while it is running, the exercise page when there is no workout,
   * otherwise the user's choice.
   *
   * Derived rather than stored, so the screen and the workout can never disagree — and so the first
   * frame after a state arrives is right even before [onState] has run.
   */
  fun screen(state: WorkoutState): Destination =
    when {
      state.isResting -> Destination.RestTimer
      !state.isWorkoutActive -> Destination.ExercisePage
      else -> chosen
    }

  /**
   * Mirrors a projection into the navigation state. Call it whenever a new one arrives.
   *
   * Two of the six rules are about state that arrives rather than state the user creates: rule 5
   * (no workout forces the exercise page back) and the accepted half of rule 6 (the tap's wait ends
   * when the projection carries the index it asked for).
   */
  fun onState(state: WorkoutState) {
    if (!state.isWorkoutActive) {
      chosenState.value = Destination.ExercisePage
      pendingState.value = null
      return
    }
    val pending = pendingState.value
    if (pending != null && pending == state.exerciseIndex) {
      pendingState.value = null
      chosenState.value = Destination.ExercisePage
    }
  }

  /**
   * The user tapped row [rowIndex] of the list. Returns the index to pass to
   * `WorkoutRepository.selectExercise`, or null when the tap needed no command at all.
   *
   * Rule 6: a tap on the row that is already current switches immediately — there is nothing to move,
   * so there is nothing to wait for. Any other row sends exactly one command, leaves the list on
   * screen, and marks that row pending.
   */
  fun onRowTap(rowIndex: Int, state: WorkoutState): Int? {
    if (rowIndex == state.exerciseIndex) {
      pendingState.value = null
      chosenState.value = Destination.ExercisePage
      return null
    }
    pendingState.value = rowIndex
    chosenState.value = Destination.WorkoutPage
    return rowIndex
  }

  /**
   * The fate of a `selectExercise` (rule 6, the other half).
   *
   * [ExerciseSelection.Abandoned] means no `state` with that index is coming — the phone refused it,
   * or the retry budget was spent — so the pending row is released and the list stays. Anything else
   * is ignored: an `applied` ack is *supposed* to be followed by a matching `state`, and clearing
   * the wait on it would defeat the reason the wait exists.
   */
  fun onSelection(selection: ExerciseSelection) {
    if (selection is ExerciseSelection.Abandoned && selection.index == pendingState.value) {
      pendingState.value = null
    }
  }

  /** Rule 4: `‹` on the first exercise opens the list. */
  fun openWorkoutPage() {
    pendingState.value = null
    chosenState.value = Destination.WorkoutPage
  }

  /**
   * BACK — the swipe-right gesture and the system back key (rule 3).
   *
   * @return true when the app should finish, i.e. the caller should let the system default run.
   *
   * From the list this finishes the activity, with one exception from rule 6: while a row is still
   * pending the first BACK abandons the tap and stays on the list, so a tap whose command never
   * landed cannot trap the user on a screen whose only exit is `finish()`.
   */
  fun back(): Boolean =
    when (chosen) {
      Destination.WorkoutPage ->
        if (pendingState.value != null) {
          pendingState.value = null
          false
        } else {
          true
        }
      // Reachable while the rest screen is drawn as well: the rest is an override of the screen, not
      // of the user's choice, so BACK acts on the choice (see the class note).
      Destination.ExercisePage -> {
        chosenState.value = Destination.WorkoutPage
        false
      }
      // Unreachable: `chosen` is only ever set to the two screens above.
      Destination.RestTimer -> true
    }
}
