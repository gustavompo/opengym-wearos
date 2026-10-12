package ch.duartesantos.opengym.wear.link

import ch.duartesantos.opengym.wear.data.TimeSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** A clock the test drives by hand. Same trick as `FakeWorkoutRepository`'s injected source. */
class MutableTimeSource(var now: Long = 1_790_000_000_000L) : TimeSource {
  override fun nowMillis(): Long = now

  fun advanceBy(millis: Long) {
    now += millis
  }
}

object TestFrames {
  fun workout(
    id: String = "mv2v5tthkhse8",
    name: String = "Full Body A",
    exerciseIndex: Int = 0,
    exerciseCount: Int = 3,
    exerciseName: String = "Dumbbell Bench Press",
    setIndex: Int = 1,
    setCount: Int = 4,
    weightKg: Double = 20.0,
    reps: Int = 8,
    previousWeightKg: Double? = null,
    previousReps: Int? = null,
    /** M7.3: `active.start`, or null for "the phone did not say". */
    startedAtMillis: Long? = null,
    /** M7.3: the list rows, or null for a frame from a phone that does not send them. */
    entries: List<WorkoutEntrySnapshot>? = null,
  ) =
    WorkoutSnapshot(
      id = id,
      name = name,
      exerciseIndex = exerciseIndex,
      exerciseCount = exerciseCount,
      exerciseName = exerciseName,
      setIndex = setIndex,
      setCount = setCount,
      weightKg = weightKg,
      reps = reps,
      previousWeightKg = previousWeightKg,
      previousReps = previousReps,
      startedAtMillis = startedAtMillis,
      entries = entries,
    )

  /** One `workout.entries[]` row (M7.3, plan section 2.1). */
  fun entry(index: Int, name: String, setsDone: Int, setsTotal: Int) =
    WorkoutEntrySnapshot(index = index, name = name, setsDone = setsDone, setsTotal = setsTotal)

  fun state(
    rev: Long = 1L,
    sentAtMillis: Long = 1_790_000_000_000L,
    workout: WorkoutSnapshot? = null,
    rest: RestSnapshot = RestSnapshot.Idle,
  ) = StateFrame(rev = rev, sentAtMillis = sentAtMillis, workout = workout, rest = rest)

  fun runningRest(endsAtMillis: Long, totalSeconds: Int = 90, restWhenIdleSeconds: Int = 90) =
    RestSnapshot(
      running = true,
      endsAtMillis = endsAtMillis,
      totalSeconds = totalSeconds,
      restWhenIdleSeconds = restWhenIdleSeconds,
    )
}

/**
 * A hand-driven [DataLayerClient].
 *
 * This is the only way the Data Layer's *logic* can be tested on this machine: there is no paired
 * phone and no Google account, so a real client answers `TARGET_NODE_NOT_CONNECTED` for everything
 * (`docs/notes/12-datalayer-decisions.md` §7). Everything interesting — node selection, a node
 * disappearing, a node being replaced, a refused send, routing, buffering, retention across a
 * disconnect — is above this interface, so a fake is what proves it.
 *
 * What it deliberately does **not** model is whether Play services itself behaves. That is the part
 * this project reports as unverified.
 */
class FakeDataLayerClient(
  reachable: List<PhoneNode> = listOf(DEFAULT_NODE),
) : DataLayerClient {

  /** One payload the transport handed to the client, with the node and path it chose. */
  class Sent(val nodeId: String, val path: String, val payload: ByteArray) {
    val text: String get() = String(payload, Charsets.UTF_8)

    override fun equals(other: Any?): Boolean =
      this === other ||
        (other is Sent &&
          nodeId == other.nodeId &&
          path == other.path &&
          payload.contentEquals(other.payload))

    override fun hashCode(): Int = (nodeId.hashCode() * 31 + path.hashCode()) * 31 + payload.contentHashCode()

    override fun toString(): String = "Sent(to=$nodeId, path=$path, ${payload.size} bytes)"
  }

  private val messages = Channel<DataLayerMessage>(Channel.UNLIMITED)
  private val changeSignals = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  private val sentLock = Any()
  private val sentMessages = mutableListOf<Sent>()

  /**
   * What `reachablePhoneNodes()` answers next. Replace it via [setReachableNodes] to simulate a
   * phone appearing or going. Deliberately not a public `var`: a Kotlin `var` named
   * `reachableNodes` generates a JVM setter `setReachableNodes(List)`, which clashes with that
   * function's own signature (the same class of trap as `Socket.getPort` shadowing a constructor
   * parameter, `docs/notes/08-protocol-transport.md`).
   */
  @Volatile private var nodes: List<PhoneNode> = reachable

  /** When set, every `send` answers this instead of delivering. */
  @Volatile var nextOutcome: SendOutcome? = null

  @Volatile var queries: Int = 0
    private set

  /** How many times [readItem] has been asked. Lets a test assert the startup read really happened. */
  @Volatile var itemReads: Int = 0
    private set

  /**
   * The item the store holds. Deliberately **not** a channel: a stored item is what a process that
   * starts after the write finds, and it produces no change event at all. That is the difference
   * [readItem] exists for.
   */
  @Volatile private var stored: DataLayerMessage? = null

  @Volatile var closed: Boolean = false
    private set

  val sent: List<Sent>
    get() = synchronized(sentLock) { sentMessages.toList() }

  override val incoming: Flow<DataLayerMessage> = messages.receiveAsFlow()

  override val nodeChanges: Flow<Unit> = changeSignals.receiveAsFlow()

  override suspend fun reachablePhoneNodes(): List<PhoneNode> {
    queries++
    return nodes
  }

  override suspend fun send(nodeId: String, path: String, payload: ByteArray): SendOutcome {
    val refused = nextOutcome
    if (refused != null) return refused
    synchronized(sentLock) { sentMessages += Sent(nodeId, path, payload) }
    return SendOutcome.Delivered
  }

  override suspend fun readItem(path: String): DataLayerMessage? {
    itemReads++
    return stored?.takeIf { it.path == path }
  }

  override fun close() {
    closed = true
  }

  // --- test drivers ----------------------------------------------------------------------------

  fun emit(message: DataLayerMessage) {
    messages.trySend(message)
  }

  fun emitFrame(
    text: String,
    nodeId: String = DEFAULT_NODE.id,
    path: String = DataLayerContract.MESSAGE_PATH,
  ) = emit(DataLayerMessage(nodeId, path, text.toByteArray(Charsets.UTF_8)))

  fun emitBytes(
    bytes: ByteArray,
    nodeId: String = DEFAULT_NODE.id,
    path: String = DataLayerContract.MESSAGE_PATH,
  ) = emit(DataLayerMessage(nodeId, path, bytes))

  /** The phone appears, disappears or is replaced. Signals the transport, like a capability event. */
  fun setReachableNodes(nodes: List<PhoneNode>) {
    this.nodes = nodes
    changeSignals.trySend(Unit)
  }

  fun allSentFrames(): List<String> = sent.map { it.text }

  /**
   * Puts a frame in the store, as the phone's `putDataItem` does. It does **not** emit anything:
   * an item that is already there when a process starts produces no change event, which is exactly
   * the cold-start case (§1a). Use [emitFrame] for a change event.
   */
  fun storeItem(
    text: String,
    nodeId: String = DEFAULT_NODE.id,
    path: String = DataLayerContract.MESSAGE_PATH,
  ) {
    stored = DataLayerMessage(nodeId, path, text.toByteArray(Charsets.UTF_8))
  }

  fun storeBytes(
    bytes: ByteArray,
    nodeId: String = DEFAULT_NODE.id,
    path: String = DataLayerContract.MESSAGE_PATH,
  ) {
    stored = DataLayerMessage(nodeId, path, bytes)
  }

  fun clearStoredItem() {
    stored = null
  }

  companion object {
    val DEFAULT_NODE: PhoneNode = PhoneNode(id = "phone-1", displayName = "Gus's phone", nearby = true)
  }
}
