package ch.duartesantos.opengym.wear.data

import kotlin.math.roundToInt

// --- bounds enforced on every write -----------------------------------------------------------
//
// The phone stays the source of truth, so these exist to stop the watch
// emitting nonsense, not to duplicate openGym's own validation.

/** Bodyweight work logs 0 kg. */
const val MIN_WEIGHT_KG: Double = 0.0

/** Above any real barbell load; keeps a stuck spinner from running away. */
const val MAX_WEIGHT_KG: Double = 500.0

/** The reference spinner shows 49.5 / 50.0 / 50.5 — 0.5 kg steps. See. */
const val WEIGHT_STEP_KG: Double = 0.5

const val MIN_REPS: Int = 1
const val MAX_REPS: Int = 99

/** openGym's rest lengths are tens of seconds; 10 minutes is a generous ceiling. */
const val MAX_REST_SECONDS: Int = 600

/** Snaps to [WEIGHT_STEP_KG] and clamps. Also absorbs float dust (`50.000000000000004`). */
internal fun clampWeightKg(weightKg: Double): Double {
  if (weightKg.isNaN()) return MIN_WEIGHT_KG
  val bounded = weightKg.coerceIn(MIN_WEIGHT_KG, MAX_WEIGHT_KG)
  return (bounded / WEIGHT_STEP_KG).roundToInt() * WEIGHT_STEP_KG
}

internal fun clampReps(reps: Int): Int = reps.coerceIn(MIN_REPS, MAX_REPS)

internal fun clampRestSeconds(seconds: Int): Int = seconds.coerceIn(0, MAX_REST_SECONDS)

// --- set-index derivation ---------------------------------------------------------------------

/** Index of the first set not yet done, or -1 when every set is done. */
internal fun List<WorkoutSet>.openSetIndex(): Int = indexOfFirst { !it.done }

/** The set the spinner is currently editing, or null when every set is done. */
internal fun List<WorkoutSet>.openSet(): WorkoutSet? = openSetIndex().takeIf { it >= 0 }?.let { this[it] }

/**
 * "Set 1/4": openGym shows `count { done } + 1` over `sets.size`. Clamped so that a fully
 * completed exercise reads "Set 4/4" rather than "Set 5/4".
 */
internal fun List<WorkoutSet>.currentSetIndex(): Int {
  if (isEmpty()) return 0
  val open = openSetIndex()
  return if (open < 0) size else open + 1
}

/** The last set completed before the open one, within this exercise. */
internal fun List<WorkoutSet>.previousDoneSet(): WorkoutSet? {
  val open = openSetIndex()
  val upTo = if (open < 0) size else open
  return take(upTo).lastOrNull { it.done }
}

// --- projection -------------------------------------------------------------------------------

/**
 * Builds the screen-facing [WorkoutState].
 *
 * Rest time is passed in rather than read from [ActiveWorkout] because openGym keeps it outside the
 * state document (`gym_rest` in localStorage, `{ endsAt, total, left }`), and because a fake that
 * owns its own clock can be advanced without waiting.
 */
internal fun ActiveWorkout.toWorkoutState(
  restSecondsRemaining: Int,
  restSecondsTotal: Int,
  /** Task 2: the phone profile's unit. Display only — every weight here stays kg. Defaults to kg
   *  so the fake and every pre-existing call site keep their behaviour. */
  weightUnit: WeightUnit = WeightUnit.Kilograms,
): WorkoutState {
  val exercise = currentExercise
  val inSession = exercise.sets.previousDoneSet()
  val fromHistory = previousSession[exercise.id]
  val lastKnown = exercise.sets.lastOrNull()
  val open = exercise.sets.openSet()

  return WorkoutState(
    isWorkoutActive = true,
    exerciseName = exercise.name,
    exerciseIndex = currentExerciseIndex,
    exerciseCount = exercises.size,
    currentSetIndex = exercise.sets.currentSetIndex(),
    totalSets = exercise.sets.size,
    weightKg = open?.weightKg ?: lastKnown?.weightKg ?: MIN_WEIGHT_KG,
    reps = open?.reps ?: lastKnown?.reps ?: MIN_REPS,
    // A set done in this session always wins over last session's history.
    previousWeightKg = inSession?.weightKg ?: fromHistory?.weightKg,
    previousReps = inSession?.reps ?: fromHistory?.reps,
    weightUnit = weightUnit,
    restSecondsRemaining = restSecondsRemaining,
    restSecondsTotal = restSecondsTotal,
    // the workout page. `startMillis` is nullable in the document precisely so it can never
    // be defaulted to 0 here.
    workoutName = name,
    startedAtMillis = startMillis,
    exercises = exercises.toRows(),
  )
}

/**
 * The workout page's rows, one per exercise, in order.
 *
 * `index` is the array position, so the invariant `rows[i].index == i` holds by construction here
 * and is asserted again in the tests. Progress is `done/total` sets only — no ticks, no checkmarks.
 */
internal fun List<WorkoutExercise>.toRows(): List<WorkoutRow> =
  mapIndexed { index, exercise ->
    WorkoutRow(
      index = index,
      name = exercise.name,
      setsDone = exercise.sets.count { it.done },
      setsTotal = exercise.sets.size,
    )
  }
