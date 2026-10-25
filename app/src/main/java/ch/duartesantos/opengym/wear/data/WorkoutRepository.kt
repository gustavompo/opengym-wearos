package ch.duartesantos.opengym.wear.data

import kotlinx.coroutines.flow.StateFlow

/** Anything that can tell the current time. Injected so rest countdowns are testable. */
fun interface TimeSource {
  fun nowMillis(): Long

  companion object {
    /** Epoch millis — the same unit openGym's `active.start` and `gym_rest.endsAt` use. */
    fun system(): TimeSource = TimeSource { System.currentTimeMillis() }
  }
}

/**
 * The watch's view of the running workout, and the only way the UI changes it.
 *
 * Methods are named after the watch's buttons, not after openGym's internals. The bridge
 * maps them onto the store actions the phone's own buttons call, so a command issued from the
 * watch cannot diverge from the same command issued on the phone.
 */
interface WorkoutRepository {

  /**
   * The current projection. A [StateFlow] rather than a plain cold flow so the UI can read
   * `.value` immediately and tests need no collector to observe a command's effect.
   */
  fun observeWorkout(): StateFlow<WorkoutState>

  /**
   * Recomputes the projection now instead of waiting for the next tick.
   *
   * The default is a no-op: a repository whose state is not derived from a deadline has nothing to
   * recompute.
   */
  fun refresh() {}

  /**
   * Marks the open set done, stamps it with the current time, and starts that exercise's rest.
   * When it was the exercise's last set, advances [WorkoutState.exerciseName] to the next exercise.
   * When it was the *session's* last set, the session ends: no rest, and the state becomes
   * [WorkoutState.None].
   */
  fun completeSet()

  /** Moves to the next exercise. No-op on the last one (the `›` chevron is disabled there). */
  fun nextExercise()

  /** Moves to the previous exercise. No-op on the first one (the `‹` chevron is disabled there). */
  fun previousExercise()

  /**
   * Makes `index` the phone's current exercise.
   *
   * The workout page's tap. Absolute and idempotent — it carries the index the user chose, not a
   * step — so a late delivery is still correct and the phone does not need to gate it on `baseRev`.
   * It must not touch sets and must not start, stop or alter a rest; the phone owns that. The
   * command is queued and retried like every other one.
   *
   * Deliberately **not** applied locally: the watch renders only what the phone sends, so the
   * tapped row stays pending until a `state` with `exerciseIndex == index` arrives. [observeExerciseSelection]
   * is how a caller learns the outcome.
   */
  fun selectExercise(index: Int)

  /**
   * The fate of the last [selectExercise], for the workout page's pending row.
   *
   * The two events that end a pending row are both internal to the command machinery — an accepted
   * `state` that matches the requested index, and the command ending without one (`rejected`, or the
   * retry budget spent) — so the repository exposes them rather than making the UI infer them from
   * `observeWorkout()` and a hand-rolled timeout. A row must never stay pending for ever.
   */
  fun observeExerciseSelection(): StateFlow<ExerciseSelection>

  /** Adjusts the open set's weight in kg, snapping to [WEIGHT_STEP_KG] and clamping. */
  fun adjustWeight(deltaKg: Double)

  /** Adjusts the open set's reps, clamped to [MIN_REPS]..[MAX_REPS]. */
  fun adjustReps(delta: Int)

  //  (wheel) — additive; merged by hand.
  //
  // Absolute setters, for the drag wheel's release.
  // `adjustWeight`/`adjustReps` are relative and `baseRev`-gated, so a drag of ten steps cannot use
  // them: one absolute frame per drag is one command, where ten deltas are one command plus nine
  // `stale` refusals. Both target exactly what the deltas target — the open set of the current
  // exercise — so a drag and a crown step can never edit different sets.

  /** Sets the open set's weight in kg, snapping to [WEIGHT_STEP_KG] and clamping. */
  fun setWeight(weightKg: Double)

  /** Sets the open set's reps, clamped to [MIN_REPS]..[MAX_REPS]. */
  fun setReps(reps: Int)

  /** Moves the running rest clock by whole seconds. No-op when no rest is running. */
  fun adjustRest(deltaSeconds: Int)

  /** Ends the running rest immediately. No-op when no rest is running. */
  fun skipRest()
}

/**
 * The state of the workout page's tap.
 *
 * [Abandoned] is deliberately sticky until the next selection rather than a one-shot event: a
 * collector that subscribes a moment late must still see it, and a stale one is harmless because the
 * page only acts on it while the index still matches the row it is waiting for.
 */
sealed interface ExerciseSelection {
  /** Nothing outstanding. */
  data object Idle : ExerciseSelection

  /**
   * Sent; the row stays pending until a `state` with `exerciseIndex == index` arrives. The watch does
   * not switch to the exercise page before that, because the page renders the phone's projection and
   * the watch carries no per-set data for the exercise the user just moved to.
   */
  data class Pending(val index: Int) : ExerciseSelection

  /**
   * The phone will never apply this: it answered `rejected` (any reason), the link turned out to be
   * incompatible, or the retry budget was spent. The page clears its pending row and **stays on the
   * list**.
   */
  data class Abandoned(val index: Int) : ExerciseSelection
}
