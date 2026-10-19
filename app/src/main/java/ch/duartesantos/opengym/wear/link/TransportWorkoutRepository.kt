package ch.duartesantos.opengym.wear.link

import ch.duartesantos.opengym.wear.data.ExerciseSelection
import ch.duartesantos.opengym.wear.data.TimeSource
import ch.duartesantos.opengym.wear.data.WorkoutRepository
import ch.duartesantos.opengym.wear.data.WorkoutRow
import ch.duartesantos.opengym.wear.data.WorkoutState
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A [WorkoutRepository] whose state comes from the phone over a [Transport], as specified in
 * ``.
 *
 * The phone is the source of truth, so this class mirrors only what it receives:
 *  - it never mutates state in response to a command, and never applies a command optimistically;
 *  - the rest countdown is re-derived from `rest.endsAt` on every tick, so a late or duplicated
 *    `state` still produces the right remaining time;
 *  - `state` frames whose `rev` is not higher than the last accepted one are discarded.
 *
 * Commands are written to the transport, queued while it is down, and retried with their original
 * ids. A lost command therefore shows as "the tap did nothing" rather than as a local lie.
 */
/** Log tag for the one-off "this frame carries no `workout.entries`" notice. */
private const val TAG = "WearWorkoutRepository"

/**
 * The default sink for that notice.
 *
 * Wrapped in `runCatching` on purpose: `android.util.Log` is a stub under a JVM unit test, so an
 * unguarded call throws `Method w in android.util.Log not mocked` — and a log line must never be
 * able to break the mirror, on a device or off it. A test that wants to assert the notice passes its
 * own sink instead.
 */
private val DEFAULT_WARN: (String) -> Unit = { message -> runCatching { android.util.Log.w(TAG, message) } }

class TransportWorkoutRepository(
  private val transport: Transport,
  private val timeSource: TimeSource = TimeSource.system(),
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  private val tickIntervalMillis: Long = 1_000L,
  private val ackTimeoutMillis: Long = WireProtocol.ACK_TIMEOUT_MS,
  /**
   * Where the one-off "the phone sent no `entries`" notice goes.
   * Injected so a JVM unit test can assert it fires exactly once without `android.util.Log` having
   * to work off-device.
   */
  private val warn: (String) -> Unit = DEFAULT_WARN,
) : WorkoutRepository, PhoneLinkSource {

  /** Distinguishes this watch boot's command ids from any earlier boot's. */
  private val bootStamp: Long = timeSource.nowMillis()
  private val idCounter = AtomicLong(0)

  private val state = MutableStateFlow(WorkoutState.None)

  private val link = MutableStateFlow(LinkStatus(connection = transport.connectionState.value))

  /**
   * The UI-facing derivation of [link]. Kept as its own flow rather than mapped on read so the
   * screens can collect it with `collectAsStateWithLifecycle` and never recompute it themselves.
   */
  private val phoneLink =
    MutableStateFlow(
      PhoneLinkState(transportName = transport.name, connected = false),
    )

  /** The newest accepted `state` frame, or null when none has arrived yet. */
  private var snapshot: StateFrame? = null

  /** The workout page's tap. See [ExerciseSelection]. */
  private val selection = MutableStateFlow<ExerciseSelection>(ExerciseSelection.Idle)

  /**
   * True once the missing-`entries` notice has been emitted. Once per process, not once per frame:
   * a phone that never sends `entries` sends that absence with every `state`, and one line is the
   * diagnosis while a line per frame is noise.
   */
  private var warnedMissingEntries = false

  /** High-water mark for. Reset when the phone's process restarts (a new `hello`). */
  private var highWaterRev: Long = -1L

  private val pending = ArrayDeque<CommandFrame>()

  private class InFlight(val command: CommandFrame, var attempts: Int, var lastSentAtMillis: Long)

  private val inFlight = LinkedHashMap<String, InFlight>()

  init {
    refreshPhoneLink()
    scope.launch {
      transport.connectionState.collect { connection ->
        updateLink { it.copy(connection = connection) }
        refreshPendingCount()
      }
    }
    scope.launch {
      // The watch speaks first, and again after every reconnect. StateFlow already emits only
      // on change, so filtering is enough — `distinctUntilChanged` on a StateFlow is an error.
      transport.connectionState
        .filter { it == ConnectionState.Connected }
        .collect { sendHello() }
    }
    scope.launch {
      transport.incoming.collect { text -> onFrame(text) }
    }
    scope.launch { restAndAckSweeper() }

    transport.start()
  }

  override fun observeWorkout(): StateFlow<WorkoutState> = state.asStateFlow()

  /** The workout page's tap state. */
  override fun observeExerciseSelection(): StateFlow<ExerciseSelection> = selection.asStateFlow()

  /** Link health for the UI's "phone not connected" state. */
  override fun observePhoneLink(): StateFlow<PhoneLinkState> = phoneLink.asStateFlow()

  /** Transport-level link health, including the fields the UI does not show. Not read by the UI. */
  fun observeLinkStatus(): StateFlow<LinkStatus> = link.asStateFlow()

  // --- commands --------------------------------------------------------------------------------

  @Synchronized
  override fun completeSet() =
    dispatch(WatchCommands.completeSet(nextId(), baseRev(), timeSource.nowMillis()))

  @Synchronized
  override fun nextExercise() =
    dispatch(WatchCommands.nextExercise(nextId(), baseRev(), timeSource.nowMillis()))

  @Synchronized
  override fun previousExercise() =
    dispatch(WatchCommands.previousExercise(nextId(), baseRev(), timeSource.nowMillis()))

  /**
   * section 2.2. Absolute: the index is the user's intent, so nothing here is applied
   * locally and the row stays pending until the phone's `state` carries it. [ExerciseSelection]
   * reports how that wait ends.
   */
  @Synchronized
  override fun selectExercise(index: Int) {
    selection.value = ExerciseSelection.Pending(index)
    dispatch(WatchCommands.selectExercise(nextId(), baseRev(), timeSource.nowMillis(), index))
  }

  @Synchronized
  override fun adjustWeight(deltaKg: Double) {
    if (deltaKg == 0.0) return
    dispatch(WatchCommands.adjustWeight(nextId(), baseRev(), timeSource.nowMillis(), deltaKg))
  }

  @Synchronized
  override fun adjustReps(delta: Int) {
    if (delta == 0) return
    dispatch(WatchCommands.adjustReps(nextId(), baseRev(), timeSource.nowMillis(), delta))
  }

  //  (wheel) — additive; merged by hand.
  //
  // Absolute, so neither is guarded on "the value changed": the value IS the user's intent and a
  // late delivery of the same frame is the same state. A drag that returns to where it
  // started is filtered on the UI side, before it ever gets here.
  @Synchronized
  override fun setWeight(weightKg: Double) =
    dispatch(WatchCommands.setWeight(nextId(), baseRev(), timeSource.nowMillis(), weightKg))

  @Synchronized
  override fun setReps(reps: Int) =
    dispatch(WatchCommands.setReps(nextId(), baseRev(), timeSource.nowMillis(), reps))

  @Synchronized
  override fun adjustRest(deltaSeconds: Int) {
    if (deltaSeconds == 0) return
    dispatch(WatchCommands.adjustRest(nextId(), baseRev(), timeSource.nowMillis(), deltaSeconds))
  }

  @Synchronized
  override fun skipRest() =
    dispatch(WatchCommands.skipRest(nextId(), baseRev(), timeSource.nowMillis()))

  /** Ids are unique per watch boot and stable across retries. */
  private fun nextId(): String = "c-$bootStamp-${idCounter.incrementAndGet()}"

  /**
   * `baseRev` of -1 means "no state has arrived"; the transport still sends it, and the phone is
   * expected to answer `rejected: stale` for a relative command rather than guess. Absolute
   * commands are applied regardless.
   */
  private fun baseRev(): Long = snapshot?.rev ?: -1L

  @Synchronized
  private fun dispatch(command: CommandFrame) {
    scope.launch { deliver(command) }
  }

  private suspend fun deliver(command: CommandFrame) {
    // with no common protocol version the watch does not send commands at all. Dropping here
    // rather than queueing keeps the queue from filling with commands the phone cannot understand.
    // A pending `selectExercise` is released rather than left waiting for a state that can never
    // come.
    if (!link.value.compatible) {
      abandonSelection(command)
      return
    }
    if (transport.connectionState.value != ConnectionState.Connected) {
      enqueue(command)
      return
    }
    when (transport.send(WireProtocol.encode(command))) {
      is SendOutcome.Delivered ->
        synchronized(this) {
          inFlight[command.id] = InFlight(command, attempts = 1, lastSentAtMillis = timeSource.nowMillis())
          refreshPendingCount()
        }
      else -> enqueue(command)
    }
  }

  @Synchronized
  private fun enqueue(command: CommandFrame) {
    pending.addLast(command)
    // Oldest first: a fresh command is more likely to still be meaningful than the one it displaces.
    while (pending.size > WireProtocol.MAX_PENDING_COMMANDS) pending.removeFirst()
    refreshPendingCount()
  }

  /** Flushes the queue in order, dropping time-bound commands whose rest has long since passed. */
  private suspend fun flushPending() {
    while (true) {
      val command = synchronized(this) { pending.removeFirstOrNull() } ?: return
      val age = timeSource.nowMillis() - command.issuedAtMillis
      if (command.isTimeBound() && age > WireProtocol.COMMAND_TTL_MS) {
        synchronized(this) { refreshPendingCount() }
        continue
      }
      when (transport.send(WireProtocol.encode(command))) {
        is SendOutcome.Delivered ->
          synchronized(this) {
            inFlight[command.id] = InFlight(command, attempts = 1, lastSentAtMillis = timeSource.nowMillis())
            refreshPendingCount()
          }
        else -> {
          // Still down. Put it back and stop; the next Connected transition retries.
          synchronized(this) {
            pending.addFirst(command)
            refreshPendingCount()
          }
          return
        }
      }
    }
  }

  @Synchronized
  private fun updateLink(transform: (LinkStatus) -> LinkStatus) {
    link.value = transform(link.value)
    refreshPhoneLink()
  }

  @Synchronized
  private fun refreshPhoneLink() {
    val status = link.value
    phoneLink.value =
      PhoneLinkState(
        transportName = transport.name,
        connected = status.connection == ConnectionState.Connected,
        compatible = status.compatible,
        lastStateAtMillis = status.lastStateAtMillis,
      )
  }

  @Synchronized
  private fun refreshPendingCount() {
    updateLink { it.copy(pendingCommands = pending.size + inFlight.size) }
  }

  private suspend fun sendHello() {
    val hello = HelloFrame(role = Role.WATCH, proto = listOf(WireProtocol.VERSION), sentAtMillis = timeSource.nowMillis())
    transport.send(WireProtocol.encode(hello))
    flushPending()
  }

  // --- inbound ---------------------------------------------------------------------------------

  private suspend fun onFrame(text: String) {
    when (val frame = WireProtocol.decode(text)) {
      null -> Unit // malformed or unknown: ignored, link stays up
      is HelloFrame -> if (frame.role == Role.PHONE) onPhoneHello(frame)
      is StateFrame -> onState(frame)
      is AckFrame -> onAck(frame)
      is PingFrame -> transport.send(WireProtocol.encode(PongFrame(frame.t)))
      is CommandFrame -> Unit // the watch does not receive commands
      is PongFrame -> Unit
    }
  }

  @Synchronized
  private fun onPhoneHello(frame: HelloFrame) {
    val negotiated = WireProtocol.negotiate(listOf(WireProtocol.VERSION), frame.proto)
    updateLink { it.copy(compatible = negotiated != null, negotiatedVersion = negotiated) }
    if (negotiated == null) {
      // Incompatible: stop sending commands, keep the last known state, keep retrying hello.
      pending.clear()
      inFlight.clear()
      refreshPendingCount()
      return
    }
    // A new hello means a new phone epoch: rev may legitimately be lower than the last one seen
    // . The phone announces one whenever its counter really restarts; a restart that keeps
    // its stored epoch continues the count instead, which is why this is not a reset to rely on.
    highWaterRev = -1L
  }

  @Synchronized
  private fun onState(frame: StateFrame) {
    if (frame.rev <= highWaterRev) return
    highWaterRev = frame.rev
    snapshot = frame
    publish()
  }

  @Synchronized
  private fun onAck(frame: AckFrame) {
    if (!frame.endsRetries()) {
      // A *retryable* refusal — `rejected: busy`, or a reason this build has never heard of. The
      // entry deliberately stays in `inFlight`, so the ack sweeper re-sends the same id after
      // `ackTimeoutMillis`, up to `MAX_COMMAND_ATTEMPTS`. That is the whole point of the id: the
      // phone comes back,
      // and the tap the user made is not silently discarded.
      refreshPendingCount()
      return
    }
    // `applied` and `duplicate` both mean "stop retrying", and a *semantic* refusal
    // (`stale`/`invalid`/`noWorkout`) means the phone understood the command and declined it, so
    // re-sending the identical bytes would be declined identically. See ``.
    val entry = inFlight.remove(frame.id)
    refreshPendingCount()
    // rule 6: a `selectExercise` that did not come back `applied` will never be
    // followed by a matching `state`, so the workout page's pending row is released and the list
    // stays. `applied` deliberately does NOT release it: guarantees the matching state, and
    // the row must not switch to the exercise page before it arrives.
    if (entry != null && frame.result != AckResult.APPLIED) abandonSelection(entry.command)
  }

  // --- projection ------------------------------------------------------------------------------

  /**
   * Recomputes the projection. Called on every accepted state and on every tick, because the rest
   * clock moves without any frame arriving and because staleness changes with time alone.
   */
  @Synchronized
  private fun publish() {
    val now = timeSource.nowMillis()
    val frame = snapshot
    val workout = frame?.workout

    state.value =
      if (workout == null) {
        WorkoutState.None
      } else {
        val rest = frame.rest
        // rule 6: an older phone build — or a recorded TCP fixture — carries no
        // `entries`. Say so once, and let `WorkoutState.listRows` render the one row the scalars
        // describe rather than an empty list.
        val entries = workout.entries
        if (entries == null && !warnedMissingEntries) {
          warnedMissingEntries = true
          warn("state frame carries no workout.entries; the workout page will render a one-row list from the scalar fields")
        }
        WorkoutState(
          isWorkoutActive = true,
          exerciseName = workout.exerciseName,
          exerciseIndex = workout.exerciseIndex,
          exerciseCount = workout.exerciseCount,
          currentSetIndex = workout.setIndex,
          totalSets = workout.setCount,
          weightKg = workout.weightKg,
          reps = workout.reps,
          // Task 2: display-unit passthrough. `weightKg` above stays kg; this is only how the
          // exercise page renders it. Absent in the frame -> Kilograms (see `WeightUnit.fromWire`).
          weightUnit = workout.unit,
          previousWeightKg = workout.previousWeightKg,
          previousReps = workout.previousReps,
          restSecondsRemaining = rest.remainingSeconds(now),
          restSecondsTotal = rest.totalForDisplay(now),
          workoutName = workout.name,
          startedAtMillis = workout.startedAtMillis,
          exercises = entries?.map { WorkoutRow(it.index, it.name, it.setsDone, it.setsTotal) }.orEmpty(),
        )
      }

    val sentAt = frame?.sentAtMillis
    updateLink {
      it.copy(
        lastStateAtMillis = sentAt,
        stale = sentAt != null && frame.workout != null && now - sentAt > WireProtocol.STATE_STALE_AFTER_MS,
      )
    }

    resolveSelection()
  }

  /**
   *  rule 6, the accepted half: the pending tap ends when the projection carries the index
   * it asked for. The page does the switching; the repository only reports that the wait is over.
   */
  @Synchronized
  private fun resolveSelection() {
    val pending = selection.value
    if (pending is ExerciseSelection.Pending && state.value.isWorkoutActive && pending.index == state.value.exerciseIndex) {
      selection.value = ExerciseSelection.Idle
    }
  }

  /**
   *  rule 6, the abandoned half: this command will never be followed by a matching `state`,
   * so the pending row is released and the list stays. Only `selectExercise` has a pending row to
   * release; every other command's failure is the ack machinery's business and nothing more.
   */
  @Synchronized
  private fun abandonSelection(command: CommandFrame) {
    if (command.name != CommandName.SELECT_EXERCISE) return
    val index = command.index ?: return
    if (selection.value == ExerciseSelection.Pending(index)) {
      selection.value = ExerciseSelection.Abandoned(index)
    }
  }

  /**
   * One loop for the two things that happen on a clock rather than on a frame: the rest countdown
   * and the ack timeout.
   */
  private suspend fun restAndAckSweeper() {
    var sinceAckSweepMillis = 0L
    while (currentCoroutineContext().isActive) {
      delay(tickIntervalMillis)
      publish()
      sinceAckSweepMillis += tickIntervalMillis
      if (sinceAckSweepMillis >= ackTimeoutMillis) {
        sinceAckSweepMillis = 0L
        sweepUnacknowledged()
      }
    }
  }

  /** Re-sends commands still waiting for an ack, with their original ids, up to [MAX_COMMAND_ATTEMPTS]. */
  private suspend fun sweepUnacknowledged() {
    if (transport.connectionState.value != ConnectionState.Connected) return
    val now = timeSource.nowMillis()
    val due =
      synchronized(this) {
        inFlight.values
          .filter { now - it.lastSentAtMillis >= ackTimeoutMillis }
          .map { it.command }
      }
    for (command in due) {
      var exhausted = false
      val attempts =
        synchronized(this) {
          val entry = inFlight[command.id] ?: return@synchronized 0
          if (entry.attempts >= WireProtocol.MAX_COMMAND_ATTEMPTS) {
            inFlight.remove(command.id)
            refreshPendingCount()
            exhausted = true
            0
          } else {
            entry.attempts += 1
            entry.lastSentAtMillis = now
            entry.attempts
          }
        }
      // rule 6: the retry budget is spent, so this `selectExercise` will never be
      // followed by a matching `state`. Release the pending row rather than leaving it pending for
      // ever — the third of the three ways a tap can end without a state.
      if (exhausted) {
        abandonSelection(command)
        continue
      }
      if (attempts == 0) continue
      transport.send(WireProtocol.encode(command))
    }
  }

  /** Stops the sweeper and the transport. */
  fun close() {
    scope.coroutineContext[Job]?.cancel()
    transport.close()
  }
}

/** Rest adjustments and `skipRest` act on a rest clock, so they expire; everything else does not. */
internal fun CommandFrame.isTimeBound(): Boolean =
  name == CommandName.ADJUST_REST || name == CommandName.SKIP_REST

/**
 * The refusal reasons that end the watch's retries for a command id: the phone understood the
 * command and declined it, so re-sending the identical bytes would be declined identically.
 * Contract: ``.
 */
internal val TERMINAL_REFUSAL_REASONS: Set<String> = setOf("stale", "invalid", "noWorkout")

/**
 * Whether this ack is the end of the road for its command id.
 *
 * `applied` and `duplicate` obviously are. A `rejected` ack is **not automatically terminal**:
 * `stale`/`invalid`/`noWorkout` are semantic refusals ([TERMINAL_REFUSAL_REASONS]), while `busy` —
 * the name for "no JS right now", which is what a frozen WebView with no workout-scoped foreground
 * service produces — is a temporary refusal the retry machinery
 * exists for.
 *
 * An **unrecognised** reason is treated as retryable on purpose. The two failure modes are not
 * symmetric: retrying an unrecognised refusal costs at most [WireProtocol.MAX_COMMAND_ATTEMPTS]
 * sends and then the command is abandoned anyway, whereas treating it as final drops a tap the user
 * made and will never see again. It also means a phone fork that invents its own reason word still
 * gets retried by a watch build that predates it.
 */
internal fun AckFrame.endsRetries(): Boolean =
  when (result) {
    AckResult.APPLIED, AckResult.DUPLICATE -> true
    AckResult.REJECTED -> reason in TERMINAL_REFUSAL_REASONS
  }
