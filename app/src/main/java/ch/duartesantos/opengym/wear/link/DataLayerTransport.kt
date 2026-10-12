package ch.duartesantos.opengym.wear.link

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The production [Transport]: the same frames as `docs/protocol.md` over the Wearable Data Layer.
 *
 * **This is a carrier swap, not a second protocol.** It knows nothing about JSON, `rev`, ids or
 * command names; those stay in [WireProtocol] and [TransportWorkoutRepository], exactly as they do
 * for [TcpRelayTransport]. The only things that differ from TCP are the two that cannot be the same:
 *
 *  - the far end is a **node**, not a `host:port`, so a node is chosen by [selectPhoneNode] and
 *    re-chosen whenever the reachable set changes;
 *  - the payload is a discrete byte array per message, so there is **no newline framing** — one
 *    message is exactly one frame, in both directions.
 *
 * Everything the Data Layer actually does is behind [DataLayerClient], which is where the reason for
 * that split lives: the Data Layer cannot be exercised on this machine's emulators
 * (`docs/notes/12-datalayer-decisions.md` §7), so node selection, disconnection and recovery are
 * tested against a fake and this class is the only untested part.
 *
 * Disconnect and reconnect are **first-class events**, not error paths:
 *  - [connectionState] goes [ConnectionState.Disconnected] when no node is reachable, and
 *    [ConnectionState.Connected] again when one is;
 *  - a different node is a *different link*, so while a link is already live the transition passes
 *    through [ConnectionState.Connecting] — that is what makes [TransportWorkoutRepository] re-send
 *    `hello` and get the new phone's `state` (`docs/protocol.md` §5.2: a new process may restart
 *    `rev` at 1);
 *  - nothing here clears anything. State retention lives one level up: the repository keeps its last
 *    accepted frame, so a dropped phone does not blank the watch (`12-datalayer-decisions.md` §2).
 *
 * Delivery is best-effort in both directions, like `MessageClient` itself: [SendOutcome.Delivered]
 * means "handed to the Data Layer", not "the phone processed it". Retries, ids and the bounded queue
 * are the repository's job (`docs/protocol.md` §5.3).
 *
 * @param client the Data Layer seam; [WearableDataLayerClient] in production, a fake in tests.
 * @param nodeRescanIntervalMillis how often to re-query the node set when nothing signals a change.
 *   Node ledgers lag reality — a pocketed phone can stay "connected" for seconds — so this is the
 *   backstop that recovers from a stale ledger without hammering Play services.
 * @param nodeChangeSettleMillis how long to hold [ConnectionState.Connecting] when the selected node
 *   changes **while a link is already live**. A `StateFlow` conflates, so a transition that is set
 *   and immediately overwritten may never be observed at all; this pause is what makes the reconnect
 *   an event the repository sees (and therefore what makes it re-send `hello`). Keep it far below
 *   any handshake timeout — nothing waits on it, it is only there to be observable. It is not spent
 *   on the first node acquired, because `Disconnected -> Connected` is already a value change.
 */
class DataLayerTransport(
  private val client: DataLayerClient,
  override val name: String = "datalayer",
  private val scope: CoroutineScope = defaultTransportScope(),
  private val nodeRescanIntervalMillis: Long = NODE_RESCAN_INTERVAL_MILLIS,
  private val nodeChangeSettleMillis: Long = NODE_CHANGE_SETTLE_MILLIS,
) : Transport {

  init {
    require(nodeRescanIntervalMillis > 0) { "nodeRescanIntervalMillis must be positive" }
    require(nodeChangeSettleMillis >= 0) { "nodeChangeSettleMillis must not be negative" }
  }

  private val state = MutableStateFlow(ConnectionState.Disconnected)
  private val frames = Channel<String>(Channel.UNLIMITED)

  /** Conflated: only "something changed, look again" is interesting, and a lost signal is covered by
   * [nodeRescanIntervalMillis] anyway. */
  private val rescans = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  private val jobs = mutableListOf<Job>()

  @Volatile private var started = false

  @Volatile private var selected: PhoneNode? = null

  /** The node the transport is talking to, or null. Diagnostics, logs and tests. */
  val selectedNodeId: String?
    get() = selected?.id

  /** How many times the node set has been queried. Diagnostics and tests. */
  @Volatile var nodeQueryCount: Int = 0
    private set

  /** How many times the stored `state` item has been read. Diagnostics and tests. */
  @Volatile var storedItemReadCount: Int = 0
    private set

  override val connectionState: StateFlow<ConnectionState> = state.asStateFlow()

  override val incoming: Flow<String> = frames.receiveAsFlow()

  @Synchronized
  override fun start() {
    if (started) return
    started = true
    jobs += scope.launch { runLoop() }
    jobs +=
      scope.launch {
        client.nodeChanges.collect { rescans.trySend(Unit) }
      }
    jobs +=
      scope.launch {
        client.incoming.collect(::onMessage)
      }
    // §1a's non-obvious half, and the one the hardware run proved is required: a process that
    // starts **after** the phone published gets no change event for that write, so waiting for one
    // renders nothing until the phone happens to publish again. The item is on the watch already;
    // it is read here rather than waited for. `onDataChanged` cannot cover this case at all.
    jobs +=
      scope.launch {
        readStoredFrame()
      }
  }

  override suspend fun send(frame: String): SendOutcome {
    val node = selected
    if (node == null || state.value != ConnectionState.Connected) return SendOutcome.NotConnected

    val outcome = client.send(node.id, DataLayerContract.MESSAGE_PATH, frame.toByteArray(Charsets.UTF_8))
    if (outcome !is SendOutcome.Delivered) {
      // A refused send is the fastest honest signal that the node ledger is stale — the phone may
      // have been pocketed as the user pressed `✓`. Report the disconnect now rather than rendering
      // a connected link that cannot carry anything. Nothing is cleared here, and the loop does not
      // rescan immediately: if the ledger still lists the node, every rescan would re-select it and
      // the repository's re-sent `hello` would fail again in a tight loop. The normal rescan interval
      // is the backoff.
      selected = null
      state.value = ConnectionState.Disconnected
    }
    return outcome
  }

  @Synchronized
  override fun close() {
    jobs.forEach { it.cancel() }
    jobs.clear()
    started = false
    selected = null
    state.value = ConnectionState.Disconnected
    frames.close()
    client.close()
  }

  // --- internals -------------------------------------------------------------------------------

  private suspend fun runLoop() {
    while (currentCoroutineContext().isActive) {
      val chosen = selectPhoneNode(client.reachablePhoneNodes())
      nodeQueryCount++

      when {
        chosen == null -> {
          selected = null
          state.value = ConnectionState.Disconnected
        }
        chosen.id == selected?.id -> state.value = ConnectionState.Connected
        else -> {
          // A new node, or a different one replacing a live link. `Connecting` is only meaningful for
          // the second: the repository re-sends `hello` on a *change into* `Connected`, and from
          // `Disconnected` that change is already observable on its own. Publishing `Connecting`
          // followed immediately by `Connected` with no suspension between them would be
          // invisible to a conflating `StateFlow`, which is exactly what the pause below avoids.
          val replacingLiveLink = state.value == ConnectionState.Connected
          selected = chosen
          if (replacingLiveLink) {
            state.value = ConnectionState.Connecting
            delay(nodeChangeSettleMillis)
          }
          state.value = ConnectionState.Connected
          // A node appearing is also "someone may have written while we were not looking", and the
          // reason the item is the carrier of record (§1a): it is the only carrier that still has
          // the frame after the writer went away. Read after `Connected` so the `hello` the
          // repository sends on that transition is not delayed by a store query.
          readStoredFrame()
        }
      }

      withTimeoutOrNull(nodeRescanIntervalMillis) { rescans.receive() }
    }
  }

  private fun onMessage(message: DataLayerMessage) {
    // §1: one path for everything. Another path is either a different feature or noise; either way
    // it is not a frame, and dropping it must not disturb the link.
    if (message.path != DataLayerContract.MESSAGE_PATH) return
    val text = decodeStrictUtf8(message.data) ?: return
    if (text.isBlank()) return
    frames.trySend(text)
  }

  /**
   * Feeds the stored `state` item in through [onMessage], so a frame read from the store takes
   * exactly the same route as one delivered as a change event or as a message: same path rule, same
   * strict decode, same `rev` gate one level up.
   *
   * A duplicate is not a problem and is not treated as one (§1a). The same frame arrives here at
   * startup, again on every new node, and possibly again as a message and as a change event; the
   * repository's high-water mark makes every copy after the first a no-op.
   */
  private suspend fun readStoredFrame() {
    storedItemReadCount++
    val stored = client.readItem(DataLayerContract.MESSAGE_PATH) ?: return
    onMessage(stored)
  }

  companion object {
    /**
     * Backstop rescan. The capability listener makes a real node change visible immediately; this
     * covers the case where it does not fire or the ledger is simply stale.
     */
    const val NODE_RESCAN_INTERVAL_MILLIS: Long = 10_000L

    /** See [DataLayerTransport.nodeChangeSettleMillis]. */
    const val NODE_CHANGE_SETTLE_MILLIS: Long = 100L
  }
}

/**
 * UTF-8 decode that reports rather than replaces.
 *
 * A `state` frame that arrives as mangled bytes is dropped, and a dropped frame must never take the
 * link down (`docs/protocol.md` §2). Decoding leniently would turn garbage into replacement
 * characters and hand [WireProtocol] a string that is merely *invalid JSON* instead of *not text* —
 * the same outcome by a longer route. Returns null when the bytes are not UTF-8.
 */
internal fun decodeStrictUtf8(bytes: ByteArray): String? =
  try {
    Charsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes))
      .toString()
  } catch (_: CharacterCodingException) {
    null
  }
