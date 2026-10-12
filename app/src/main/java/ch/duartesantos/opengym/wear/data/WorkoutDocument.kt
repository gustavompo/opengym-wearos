package ch.duartesantos.opengym.wear.data

/**
 * The in-memory analogue of openGym's persisted `active` object.
 *
 * Only the fields the watch projection needs are modelled:
 *
 * ```jsonc
 * "active": {
 *   "name": "Full Body A",
 *   "cur": 0,                       // -> currentExerciseIndex
 *   "entries": [                    // -> exercises
 *     { "id": "0043",
 *       "sets": [ { "w": 50.0, "r": 10, "done": true, "at": 1791665144842 } ] }
 *   ]
 * }
 * ```
 *
 * openGym's `target` / `plan` / `planned` / `routineIds` / `bw` and the rest of the 4 KB document
 * are intentionally absent — the watch never reads them.
 *
 * The rest *duration* is per exercise in the reference UX ("rest 90 s" after a compound lift, less
 * after an isolation one) and per-routine-exercise in openGym; openGym also has a global `restSec`
 * fallback that this model does not need.
 */
data class ActiveWorkout(
  /** `active.name`, e.g. "Full Body A". */
  val name: String,
  /** `active.cur` — index into [exercises]. */
  val currentExerciseIndex: Int,
  /** `active.entries[]`. */
  val exercises: List<WorkoutExercise>,
  /**
   * `active.start`, epoch millis, or null when the document has no start.
   *
   * M7.3: the workout page derives its elapsed timer from this against the watch's own clock. Null
   * means "the timer is hidden" — and it is never coerced to 0, which would read as 20 491 days
   * (plan §2.1 rule 5). [FullBodyA] leaves it null on purpose: the fake stamps a recent start in its
   * own constructor so a session does not appear to have begun in 1970, and so a test can drive the
   * timer through [TimeSource] instead of reading the wall clock.
   */
  val startMillis: Long? = null,
  /**
   * The last set logged for an exercise in an *earlier* session, keyed by exercise id.
   *
   * openGym resolves the `prev. 50.0 kg x 10` hint from "previous set / `workouts[]` history for
   * that exercise"; on the first set of the first exercise there is no previous set in the current
   * session, so the hint can only come from history. This map is that stand-in.
   */
  val previousSession: Map<String, LoggedSet> = emptyMap(),
) {
  /** `entries[cur]`. Requires [exercises] to be non-empty. */
  val currentExercise: WorkoutExercise get() = exercises[currentExerciseIndex]

  companion object {
    /**
     * A believable five-minute upper-body session. Set counts and weights differ per exercise so
     * the screens have something real to render, and the first exercise reproduces the values in
     * `references/exercise-page.png` (`Lat Pulldown`, `Set 1/4`, `50.0` kg, `10` reps,
     * `prev. 50.0 kg x 10`).
     *
     * Exercise ids follow openGym's catalogue id shape; only `0043` (Lat Pulldown) is verified
     * against the spike's persisted document. The rest are plausible, not verified.
     */
    val FullBodyA: ActiveWorkout =
      ActiveWorkout(
        name = "Full Body A",
        currentExerciseIndex = 0,
        exercises =
          listOf(
            WorkoutExercise(
              id = "0043",
              name = "Lat Pulldown",
              restSeconds = 90,
              sets = List(4) { WorkoutSet(weightKg = 50.0, reps = 10) },
            ),
            WorkoutExercise(
              id = "0078",
              name = "Dumbbell Row",
              restSeconds = 60,
              sets = List(3) { WorkoutSet(weightKg = 10.0, reps = 10) },
            ),
            WorkoutExercise(
              id = "0032",
              name = "Barbell Squat",
              restSeconds = 120,
              sets = List(5) { WorkoutSet(weightKg = 60.0, reps = 8) },
            ),
            WorkoutExercise(
              id = "0041",
              name = "Bench Press",
              restSeconds = 90,
              sets = List(4) { WorkoutSet(weightKg = 40.0, reps = 12) },
            ),
          ),
        previousSession =
          mapOf(
            "0043" to LoggedSet(weightKg = 50.0, reps = 10),
            "0078" to LoggedSet(weightKg = 10.0, reps = 10),
            "0032" to LoggedSet(weightKg = 62.5, reps = 8),
            "0041" to LoggedSet(weightKg = 40.0, reps = 12),
          ),
      )
  }
}

/** One exercise inside an active session — openGym's `entries[]` element. */
data class WorkoutExercise(
  /** openGym catalogue exercise id, e.g. `"0043"`. */
  val id: String,
  /** Display name. openGym stores only the id; the phone's projection resolves the name. */
  val name: String,
  /** Seconds of rest started after a set of this exercise is completed. */
  val restSeconds: Int,
  /** openGym's `sets[]`, in order. */
  val sets: List<WorkoutSet>,
)

/** One set — openGym's `{ w, r, done, at }`. */
data class WorkoutSet(
  val weightKg: Double,
  val reps: Int,
  val done: Boolean = false,
  /** openGym's `at`: epoch millis at which the set was marked done. */
  val completedAtMillis: Long? = null,
)

/** A set logged in an earlier session: only the two fields needed for the `prev. …` hint. */
data class LoggedSet(val weightKg: Double, val reps: Int)
