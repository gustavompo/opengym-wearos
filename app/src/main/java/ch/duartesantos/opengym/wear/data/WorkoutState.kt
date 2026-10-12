package ch.duartesantos.opengym.wear.data

/**
 * The projection the watch screens render.
 *
 * This is deliberately *not* openGym's persisted document. It is the ~12 fields the watch needs,
 * derived from the `active` object of that document (see
 * `docs/research/03-local-spike-opengym-observability.md`) by [toWorkoutState]. Field names follow
 * openGym's vocabulary where openGym has a name for the concept, so that swapping the fake source
 * for the real phone projection is a transport change and not a rename.
 *
 * Units: weight in kilograms on the wire — see [weightUnit] for the display conversion — rest in
 * whole seconds, set indices one-based (the UI shows "Set 1/4", not "Set 0/3").
 */
data class WorkoutState(
  /** False when no session is running. Every other field is then meaningless. */
  val isWorkoutActive: Boolean,
  /** `entries[cur]` resolved to a human name. */
  val exerciseName: String,
  /** `active.cur`: zero-based index of the current exercise inside the session. */
  val exerciseIndex: Int,
  /** `active.entries.size`: how many exercises the session has. */
  val exerciseCount: Int,
  /** `count { done } + 1` over the current exercise's sets, clamped to `1..totalSets`. */
  val currentSetIndex: Int,
  /** `entries[cur].sets.size`. */
  val totalSets: Int,
  /** `entries[cur].sets[currentSetIndex - 1].w`. */
  val weightKg: Double,
  /** `entries[cur].sets[currentSetIndex - 1].r`. */
  val reps: Int,
  /**
   * The phone profile's unit, for DISPLAY only (task 2). [weightKg] and [previousWeightKg] are
   * always kilograms whatever this is.
   *
   * Defaults to [WeightUnit.Kilograms] so every construction site that predates the field — the
   * fake repository's tests, the recorded fixtures, `WorkoutState.None` — keeps the behaviour it
   * had, and so a phone that never sends `unit` is a kg phone.
   */
  val weightUnit: WeightUnit = WeightUnit.Kilograms,
  /** Weight of the previous set, for the `prev. 50.0 kg x 10` hint. Null when there is none. */
  val previousWeightKg: Double?,
  /** Reps of the previous set. Null when there is none. */
  val previousReps: Int?,
  /** Seconds left on the rest clock. Zero means "not resting". */
  val restSecondsRemaining: Int,
  /** Length of the rest currently running, or the current exercise's configured rest when idle. */
  val restSecondsTotal: Int,
  /**
   * `active.name` — the workout's own name, e.g. "Full Body A". The workout page's title.
   *
   * M7.3: the phone has always sent this as `workout.name` (`docs/protocol.md` §3.1) and the watch
   * simply had no screen that needed it until the workout page existed.
   */
  val workoutName: String = "",
  /**
   * `active.start`, epoch millis, or **null** when the phone did not send one.
   *
   * M7.3: never 0. Epoch 0 is 20 491 days ago, so a zero here would render an absurd elapsed timer
   * rather than an absent one (`docs/notes/19-workout-page-wheel-plan.md` §2.1 rule 5). Null, a
   * future start and a start that is somehow negative all mean **the timer is hidden**.
   */
  val startedAtMillis: Long? = null,
  /**
   * `active.entries` — every exercise in the session, in order, with its progress (M7.3).
   *
   * Empty when the phone sent no `entries` at all: an older phone build, or a recorded TCP fixture.
   * [listRows] is what turns that into the one-row fallback.
   */
  val exercises: List<WorkoutRow> = emptyList(),
) {
  /** True while the rest clock has time left. This is what drives the RestTimer destination. */
  val isResting: Boolean get() = restSecondsRemaining > 0

  /**
   * M2.7: whether `‹` can do anything. False on the workout's first exercise (and when no workout
   * is running).
   *
   * M7.3 changed what the screen does with a false: the chevron is no longer dead there — it opens
   * the workout page (`docs/notes/19-workout-page-wheel-plan.md` §2.4 rule 4), and `WearApp` passes
   * `true` for enablement and keeps this flag for the decision. This property keeps its meaning: it
   * reports whether there is a *previous exercise* to move to.
   */
  val canGoPrevious: Boolean get() = isWorkoutActive && exerciseIndex > 0

  /** M2.7: whether `›` can do anything. False on the workout's last exercise. */
  val canGoNext: Boolean get() = isWorkoutActive && exerciseIndex < exerciseCount - 1

  /**
   * The workout page's list (M7.3).
   *
   * [exercises] when the phone sent them; otherwise a **one-row** list built from the scalars, so an
   * older phone build renders the one exercise those scalars describe instead of an empty list or a
   * crash (`docs/notes/19-workout-page-wheel-plan.md` §2.1 rule 6).
   *
   * **Empty when no workout is running.** The fallback is for a *frame that carried no list*, not for
   * the absence of a workout: plan §2.4 rule 5 keeps the exercise page (and its
   * `emptyWorkoutMessage(link)`) on screen then, and an invented one-row list of an empty name would
   * be worse than the empty state it replaced.
   *
   * `setsDone` from the scalars is `currentSetIndex - 1`, because `setIndex` is
   * `count { done } + 1` clamped to `1..totalSets` — the same derivation, minus the un-clamped part
   * that `setIndex` cannot express.
   */
  val listRows: List<WorkoutRow>
    get() =
      when {
        !isWorkoutActive -> emptyList()
        exercises.isNotEmpty() -> exercises
        else ->
          listOf(
            WorkoutRow(
              index = exerciseIndex,
              name = exerciseName,
              setsDone = (currentSetIndex - 1).coerceIn(0, totalSets),
              setsTotal = totalSets,
            )
          )
      }

  /**
   * Seconds elapsed since [startedAtMillis], or null when the timer must be hidden.
   *
   * Derived from the watch's own clock against an absolute start, never ticked down from a frame:
   * a `state` that arrives late still produces the right elapsed time, and no phone traffic is
   * needed to keep the timer moving (plan §2 rule for the timer).
   */
  fun elapsedSeconds(nowMillis: Long): Long? {
    val start = startedAtMillis ?: return null
    val seconds = (nowMillis - start) / 1_000L
    return if (seconds < 0L) null else seconds
  }

  companion object {
    /** No session running. */
    val None: WorkoutState =
      WorkoutState(
        isWorkoutActive = false,
        exerciseName = "",
        exerciseIndex = 0,
        exerciseCount = 0,
        currentSetIndex = 0,
        totalSets = 0,
        weightKg = 0.0,
        reps = 0,
        previousWeightKg = null,
        previousReps = null,
        restSecondsRemaining = 0,
        restSecondsTotal = 0,
      )
  }
}

/**
 * One row of the workout page's list (M7.3): an exercise of the session and how far it has got.
 *
 * Progress is `setsDone/setsTotal` and nothing else — no per-set ticks, no checkmarks
 * (`docs/notes/19-workout-page-wheel-plan.md` §0 decision 5).
 */
data class WorkoutRow(
  /**
   * Position inside the session. Carried explicitly **and** equal to the row's position in
   * [WorkoutState.listRows], so a consumer that filters or sorts cannot silently mis-map
   * (plan §2.1 rule 2). It is this value — not a list position — that `selectExercise` takes.
   */
  val index: Int,
  /** Never empty: the phone resolves it with the same resolver as `exerciseName` (§2.1 rule 3). */
  val name: String,
  /** `count { done }` over this exercise's sets. `0 <= setsDone <= setsTotal`. */
  val setsDone: Int,
  /** `sets.size`. Zero is legal — a zero-set exercise (§2.1 rule 4). */
  val setsTotal: Int,
)
