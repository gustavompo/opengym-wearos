package ch.duartesantos.opengym.wear.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Where the fake session's `active.start` lands when the document carries none. 45:12. */
private const val DEFAULT_STARTED_AGO_MILLIS: Long = 45L * 60_000L + 12_000L

/**
 * In-memory [WorkoutRepository] for developing the screens before the phone bridge exists.
 *
 * Deterministic by construction: there is no randomness and no implicit clock read — [timeSource]
 * is the only thing that can move time, so a test can drive an entire rest countdown instantly by
 * advancing a virtual clock and the test scheduler together.
 *
 * Not thread-safe by design; commands are expected from one thread (Compose's main thread) and the
 * rest ticker only ever writes through [refreshRestRemaining].
 */
class FakeWorkoutRepository(
  initialWorkout: ActiveWorkout = ActiveWorkout.FullBodyA,
  private val timeSource: TimeSource = TimeSource.system(),
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  private val tickIntervalMillis: Long = 1_000L,
  /**
   * How long ago the fake session started, when [initialWorkout] carries no `active.start` of its
   * own. [ActiveWorkout.FullBodyA] leaves it null on purpose so the fake is still the one
   * thing that reads the clock, through [timeSource] rather than [System.currentTimeMillis].
   *
   * The default lands the elapsed timer at 45:12, which is the shape the reference's timer shows
   * (three digits and a colon) and is recognisable in an instrumented dump.
   */
  private val startedAgoMillis: Long = DEFAULT_STARTED_AGO_MILLIS,
  /**
   * Task 2: the simulated phone profile's unit. The fake's own weights stay kg (it is the wire
   * side of the fake), and only [WorkoutState.weightUnit] changes, so an lb test exercises the real
   * display conversion without needing a phone.
   */
  private val weightUnit: WeightUnit = WeightUnit.Kilograms,
) : WorkoutRepository {

  init {
    require(initialWorkout.exercises.isNotEmpty()) {
      "an active workout must have at least one exercise"
    }
  }

  /** openGym's `active`, or null once the session has ended. */
  private var workout: ActiveWorkout? =
    initialWorkout.let { if (it.startMillis != null) it else it.copy(startMillis = timeSource.nowMillis() - startedAgoMillis) }

  /** The workout page's tap: never pending for long here, because the fake answers immediately. */
  private val selection = MutableStateFlow<ExerciseSelection>(ExerciseSelection.Idle)

  /** openGym keeps the rest clock outside the state document, so the fake does too. */
  private var restRemainingSeconds: Int = 0
  private var restTotalSeconds: Int = 0
  private var restEndsAtMillis: Long? = null
  private var restTicker: Job? = null

  private val state = MutableStateFlow(WorkoutState.None)

  init {
    publish()
  }

  override fun observeWorkout(): StateFlow<WorkoutState> = state

  @Synchronized
  override fun completeSet() {
    val current = workout ?: return
    val exerciseIndex = current.currentExerciseIndex
    val exercise = current.exercises[exerciseIndex]
    val openIndex = exercise.sets.openSetIndex()
    if (openIndex < 0) return

    val sets =
      exercise.sets.toMutableList().apply {
        this[openIndex] =
          this[openIndex].copy(done = true, completedAtMillis = timeSource.nowMillis())
      }
    val exercises =
      current.exercises.toMutableList().apply { this[exerciseIndex] = exercise.copy(sets = sets) }

    val exerciseDone = sets.openSetIndex() < 0
    val sessionDone = exerciseDone && exerciseIndex == current.exercises.lastIndex

    when {
      // Nothing left to do. openGym drops `active` when a session is finished, so this does too.
      sessionDone -> {
        clearRest()
        workout = null
      }
      // A finished exercise hands over to the next one; `cur` advances.
      exerciseDone -> {
        workout = current.copy(exercises = exercises, currentExerciseIndex = exerciseIndex + 1)
        startRest(exercise.restSeconds)
      }
      else -> {
        workout = current.copy(exercises = exercises)
        startRest(exercise.restSeconds)
      }
    }
    publish()
  }

  @Synchronized
  override fun nextExercise() {
    val current = workout ?: return
    if (current.currentExerciseIndex >= current.exercises.lastIndex) return
    workout = current.copy(currentExerciseIndex = current.currentExerciseIndex + 1)
    publish()
  }

  @Synchronized
  override fun previousExercise() {
    val current = workout ?: return
    if (current.currentExerciseIndex <= 0) return
    workout = current.copy(currentExerciseIndex = current.currentExerciseIndex - 1)
    publish()
  }

  /**
   * the fake's answer to the workout page's tap.
   *
   * It mirrors the phone's contract rather than short-circuiting it: an out-of-range index is
   * ignored (the real phone answers `rejected: invalid` and changes nothing), an in-range one is
   * written, [ExerciseSelection.Pending] is raised and then cleared by [publish] once the projection
   * actually carries the new index — which is the same rule the transport-backed repository follows.
   */
  @Synchronized
  override fun selectExercise(index: Int) {
    val current = workout ?: return
    if (index < 0 || index >= current.exercises.size) return
    if (index != current.currentExerciseIndex) workout = current.copy(currentExerciseIndex = index)
    selection.value = ExerciseSelection.Pending(index)
    publish()
  }

  override fun observeExerciseSelection(): StateFlow<ExerciseSelection> = selection

  @Synchronized
  override fun adjustWeight(deltaKg: Double) {
    mutateOpenSet { it.copy(weightKg = clampWeightKg(it.weightKg + deltaKg)) }
  }

  @Synchronized
  override fun adjustReps(delta: Int) {
    mutateOpenSet { it.copy(reps = clampReps(it.reps + delta)) }
  }

  //  (wheel) — additive; merged by hand.
  @Synchronized
  override fun setWeight(weightKg: Double) {
    mutateOpenSet { it.copy(weightKg = clampWeightKg(weightKg)) }
  }

  @Synchronized
  override fun setReps(reps: Int) {
    mutateOpenSet { it.copy(reps = clampReps(reps)) }
  }

  @Synchronized
  override fun adjustRest(deltaSeconds: Int) {
    if (deltaSeconds == 0) return
    if (restEndsAtMillis == null) return

    // Re-derive from the clock first, so the user's adjustment applies to what they can see.
    refreshRestRemaining()
    if (restEndsAtMillis == null) return

    val remaining = clampRestSeconds(restRemainingSeconds + deltaSeconds)
    if (remaining == 0) {
      clearRest()
      publish()
      return
    }
    restRemainingSeconds = remaining
    restTotalSeconds = clampRestSeconds(restTotalSeconds + deltaSeconds).coerceAtLeast(remaining)
    restEndsAtMillis = timeSource.nowMillis() + remaining * 1_000L
    publish()
  }

  @Synchronized
  override fun skipRest() {
    if (restEndsAtMillis == null && restRemainingSeconds == 0) return
    clearRest()
    publish()
  }

  /** Applies [transform] to the open set of the current exercise and republishes. */
  private fun mutateOpenSet(transform: (WorkoutSet) -> WorkoutSet) {
    val current = workout ?: return
    val exerciseIndex = current.currentExerciseIndex
    val exercise = current.exercises[exerciseIndex]
    val openIndex = exercise.sets.openSetIndex()
    if (openIndex < 0) return

    val sets =
      exercise.sets.toMutableList().apply { this[openIndex] = transform(this[openIndex]) }
    val exercises =
      current.exercises.toMutableList().apply { this[exerciseIndex] = exercise.copy(sets = sets) }
    workout = current.copy(exercises = exercises)
    publish()
  }

  // --- rest clock -----------------------------------------------------------------------------

  /** Starts the countdown and the ticker. [seconds] of 0 means no rest at all. */
  private fun startRest(seconds: Int) {
    restTicker?.cancel()
    restTicker = null

    val total = clampRestSeconds(seconds)
    if (total == 0) {
      restRemainingSeconds = 0
      restTotalSeconds = 0
      restEndsAtMillis = null
      return
    }

    restTotalSeconds = total
    restRemainingSeconds = total
    restEndsAtMillis = timeSource.nowMillis() + total * 1_000L
    restTicker = scope.launch {
      while (isActive) {
        delay(tickIntervalMillis)
        refreshRestRemaining()
      }
    }
  }

  /** Stops the countdown and the ticker without publishing. */
  private fun clearRest() {
    restTicker?.cancel()
    restTicker = null
    restEndsAtMillis = null
    restRemainingSeconds = 0
    restTotalSeconds = 0
  }

  /**
   * The only place the countdown changes without a command: re-derives the remaining seconds from
   * [restEndsAtMillis] and the clock, rounding up so the display never skips a second.
   */
  @Synchronized
  private fun refreshRestRemaining() {
    val endsAt = restEndsAtMillis ?: return
    val millisLeft = endsAt - timeSource.nowMillis()
    val secondsLeft = if (millisLeft <= 0L) 0 else ((millisLeft + 999L) / 1_000L).toInt()
    if (secondsLeft == restRemainingSeconds) return

    restRemainingSeconds = secondsLeft
    if (secondsLeft == 0) clearRest()
    publish()
  }

  private fun publish() {
    val current = workout
    state.value =
      if (current == null) {
        WorkoutState.None
      } else {
        // While resting, the ring must stay proportional to the rest that was started. While idle,
        // the honest total is the rest the *current* exercise will start.
        val total =
          if (restEndsAtMillis != null || restRemainingSeconds > 0) restTotalSeconds
          else current.currentExercise.restSeconds
        current.toWorkoutState(
          restSecondsRemaining = restRemainingSeconds,
          restSecondsTotal = total,
          weightUnit = weightUnit,
        )
      }

    //  rule 6: a pending tap ends when the projection carries the index it asked for.
    val pending = selection.value
    if (pending is ExerciseSelection.Pending && pending.index == state.value.exerciseIndex) {
      selection.value = ExerciseSelection.Idle
    }
  }
}
