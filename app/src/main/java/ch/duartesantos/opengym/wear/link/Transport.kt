package ch.duartesantos.opengym.wear.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * How healthy the pipe to the phone is, independent of what is being said over it.
 *
 * [Connected] with an empty [WorkoutState] is "the phone is fine, no workout is running".
 * Anything else with a stale [WorkoutState] is "the phone is not answering". Those two are the
 * distinction the UI needs and they are deliberately not the same signal.
 */
enum class ConnectionState {
  /** No connection and none being attempted (before [Transport.start], or after [Transport.close]). */
  Disconnected,

  /** Trying to establish a connection. */
  Connecting,

  /** Frames can flow. */
  Connected,
}

/** The result of a [Transport.send]. Nothing throws out of the transport on a lost connection. */
sealed interface SendOutcome {
  /** The frame was handed to the underlying channel. Delivery is still at-least-once, not exactly-once. */
  data object Delivered : SendOutcome

  /** Nothing is connected right now, so nothing was written. The caller decides whether to queue. */
  data object NotConnected : SendOutcome

  /** The pipe broke while writing. The transport will reconnect on its own. */
  data class Failed(val cause: Throwable) : SendOutcome
}

/**
 * A framed, bidirectional, at-least-once string channel to the phone.
 *
 * Frames are opaque here: the wire format (newline-delimited JSON, one object per frame) lives in
 * [WireProtocol], not in the transport. That split is what makes the two implementations genuinely
 * interchangeable — `DataLayerTransport` and `TcpRelayTransport` differ only in how bytes move, and
 * both are checked against the same contract tests.
 *
 * Implementing the interface means:
 *  - [connectionState] reflects reality, including transient reconnects. It is a `StateFlow`, so a
 *    late collector still sees the current value.
 *  - [incoming] carries every frame the phone sent, in order, with no drops while nobody is
 *    collecting. The default implementation is a [Channel] drained as a flow for exactly that
 *    reason: a `SharedFlow(replay = 0)` would silently lose frames that arrive before the first
 *    collector, which is a real race on both transports.
 *  - [start]/[close] are idempotent. Calling [start] twice must not open two connections.
 *
 * No implementation may throw from [send]; a dead pipe is a [SendOutcome], not an exception.
 */
interface Transport {

  /** Short, stable label for logs and the debug UI: `"tcp"`, `"datalayer"`, `"loopback"`. */
  val name: String

  val connectionState: StateFlow<ConnectionState>

  /** Frames from the phone. Hot, but buffered until a collector appears. */
  val incoming: Flow<String>

  /** Begins connecting/accepting. Idempotent. */
  fun start()

  /** Sends one frame ([WireProtocol.encode] output; the transport adds any framing). */
  suspend fun send(frame: String): SendOutcome

  /** Releases the socket/listener and stops reconnecting. Idempotent. */
  fun close()
}

/**
 * In-memory [Transport] used by tests and by anything that needs the link's *shape* without a
 * phone. Part of `main` rather than `test` so the instrumented suite can swap it in for the fake
 * repository later without copying code across source sets.
 *
 * It is a fake, and it is not evidence that the real transports work — see
 * `docs/notes/08-protocol-transport.md` for the real-socket test that is.
 */
class LoopbackTransport(
  override val name: String = "loopback",
  initialState: ConnectionState = ConnectionState.Connected,
) : Transport {

  private val state = MutableStateFlow(initialState)
  private val frames = Channel<String>(Channel.UNLIMITED)

  /** Everything the watch handed to [send], in order, unparsed. */
  val sentFrames: MutableList<String> = mutableListOf()

  override val connectionState: StateFlow<ConnectionState> = state.asStateFlow()

  override val incoming: Flow<String> = frames.receiveAsFlow()

  override fun start() = Unit

  override suspend fun send(frame: String): SendOutcome {
    if (state.value != ConnectionState.Connected) return SendOutcome.NotConnected
    synchronized(sentFrames) { sentFrames += frame }
    return SendOutcome.Delivered
  }

  override fun close() {
    state.value = ConnectionState.Disconnected
    frames.close()
  }

  /** Test/phone-side helper: pretend the phone sent [frame]. */
  fun emitIncoming(frame: String) {
    frames.trySend(frame)
  }

  fun setConnectionState(newState: ConnectionState) {
    state.value = newState
  }

  /** Test helper: the last frame [send] accepted, or null. */
  fun lastSentFrame(): String? = synchronized(sentFrames) { sentFrames.lastOrNull() }
}

/**
 * The link's health, as the screens would want to show it. Kept separate from
 * [ch.duartesantos.opengym.wear.data.WorkoutState] so that "the phone is unreachable" and "no
 * workout is running" cannot be confused for one another (`docs/protocol.md` §6).
 *
 * Nothing in the UI consumes this yet — M3 is plumbing, and the current screens render
 * [ch.duartesantos.opengym.wear.data.WorkoutRepository] only.
 */
data class LinkStatus(
  val connection: ConnectionState = ConnectionState.Disconnected,
  /** False when the version intersection was empty (`docs/protocol.md` §2). */
  val compatible: Boolean = true,
  val negotiatedVersion: Int? = null,
  /** `sentAt` of the newest accepted `state`, or null if none has ever arrived. */
  val lastStateAtMillis: Long? = null,
  /** The newest accepted state is older than [WireProtocol.STATE_STALE_AFTER_MS]. */
  val stale: Boolean = false,
  /** Commands queued because the link was down, plus commands awaiting an ack. */
  val pendingCommands: Int = 0,
)

/** A scope for transports that own background work, so callers do not have to pass one. */
internal fun defaultTransportScope(): CoroutineScope =
  CoroutineScope(SupervisorJob() + Dispatchers.IO)
