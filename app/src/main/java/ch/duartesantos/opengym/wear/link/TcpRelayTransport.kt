package ch.duartesantos.opengym.wear.link

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Newline-delimited JSON over a TCP connection to the phone, which listens
 * (`docs/protocol.md` §7).
 *
 * **This is a development and CI transport, not the production one.** The production path is the
 * Wearable Data Layer, because only a manifest-registered Android entry point (`WearableListenerService`)
 * gets the phone's process unfrozen when the user has pocketed it — see `docs/plan.md` §1.5 and
 * `docs/notes/02-webview-background-liveness.md`. A raw socket is also owned by a frozen process and
 * receives nothing. The TCP relay exists because the Data Layer cannot be exercised between two
 * emulators on this machine without Google-account enrolment, and because a real socket can be
 * tested against a real server in a JVM unit test, which is worth more than a mock.
 *
 * Behaviour:
 *  - Connects lazily on [start], and reconnects forever on the [backoffMillis] schedule, which
 *    resets after every successful connect.
 *  - [connectionState] is honest about all three states, so the UI can tell "phone unreachable"
 *    from "no workout running".
 *  - Frames are read one line at a time and pushed into an unbounded [Channel]; nothing is lost if
 *    the collector is slow or late.
 *  - [send] never throws: a broken pipe is [SendOutcome.Failed] and the loop reconnects.
 */
class TcpRelayTransport(
  private val host: String,
  private val port: Int,
  override val name: String = "tcp",
  private val scope: CoroutineScope = defaultTransportScope(),
  private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
  private val backoffMillis: List<Long> = DEFAULT_BACKOFF_MILLIS,
  private val socketFactory: () -> Socket = { Socket() },
) : Transport {

  init {
    require(backoffMillis.isNotEmpty()) { "backoffMillis must not be empty" }
    require(port in 1..65_535) { "port out of range: $port" }
  }

  private val state = MutableStateFlow(ConnectionState.Disconnected)
  private val frames = Channel<String>(Channel.UNLIMITED)
  private val writeLock = Mutex()

  @Volatile private var loop: Job? = null

  @Volatile private var socket: Socket? = null

  @Volatile private var writer: Writer? = null

  /** How many times the loop has completed a connect. Diagnostics and tests. */
  @Volatile var connectCount: Int = 0
    private set

  override val connectionState: StateFlow<ConnectionState> = state.asStateFlow()

  override val incoming: Flow<String> = frames.receiveAsFlow()

  override fun start() {
    if (loop?.isActive == true) return
    loop = scope.launch { runLoop() }
  }

  override suspend fun send(frame: String): SendOutcome {
    if (state.value != ConnectionState.Connected) return SendOutcome.NotConnected
    val out = writer ?: return SendOutcome.NotConnected
    return writeLock.withLock {
      try {
        // One write call so a frame can never be interleaved with another frame's bytes.
        out.write(frame + "\n")
        out.flush()
        SendOutcome.Delivered
      } catch (e: IOException) {
        // The read loop will notice within one read and flip the state; report now rather than throw.
        SendOutcome.Failed(e)
      }
    }
  }

  override fun close() {
    loop?.cancel()
    loop = null
    closeSession()
    state.value = ConnectionState.Disconnected
    frames.close()
  }

  // --- internals -------------------------------------------------------------------------------

  private suspend fun runLoop() =
    withContext(Dispatchers.IO) {
      var attempt = 0
      while (isActive) {
        state.value = ConnectionState.Connecting
        val opened = openSocket()
        if (opened == null) {
          state.value = ConnectionState.Disconnected
          delay(backoffMillis[attempt.coerceAtMost(backoffMillis.lastIndex)])
          attempt++
          continue
        }

        attempt = 0
        socket = opened
        writer = BufferedWriter(OutputStreamWriter(opened.getOutputStream(), Charsets.UTF_8))
        connectCount++
        state.value = ConnectionState.Connected

        try {
          val reader = BufferedReader(InputStreamReader(opened.getInputStream(), Charsets.UTF_8))
          while (isActive) {
            val line = reader.readLine() ?: break
            if (line.isNotBlank()) frames.trySend(line)
          }
        } catch (_: IOException) {
          // Dropped by the far end, or closed under us by close(). Either way: reconnect.
        } finally {
          closeSession()
          state.value = ConnectionState.Disconnected
        }

        // A connection that came up and then died retries on the first (fastest) step.
        delay(backoffMillis.first())
      }
    }

  private fun openSocket(): Socket? {
    val candidate = socketFactory()
    return try {
      candidate.tcpNoDelay = true
      // `this.` is load-bearing. `java.net.Socket.getPort()` is exposed to Kotlin as a property
      // called `port`, so inside a `candidate.apply { ... }` block the transport's own `port` field
      // is shadowed by the (unconnected, therefore 0) socket port, the target silently becomes
      // `host:0`, and every connect fails with `BindException: Cannot assign requested address`.
      // That is exactly what the real-socket test caught; a fake transport never would have.
      candidate.connect(InetSocketAddress(this.host, this.port), connectTimeoutMillis)
      candidate
    } catch (_: IOException) {
      closeQuietly(candidate)
      null
    } catch (_: SecurityException) {
      // Missing android.permission.INTERNET, or a blocked address. Unreachable, not a crash.
      closeQuietly(candidate)
      null
    }
  }

  private fun closeSession() {
    writer = null
    val s = socket
    socket = null
    closeQuietly(s)
  }

  private fun closeQuietly(target: Socket?) {
    try {
      target?.close()
    } catch (_: IOException) {
      // Nothing useful to do while closing.
    }
  }

  companion object {
    /** 250 ms doubling to a 10 s cap. Matches `docs/protocol.md` §7. */
    val DEFAULT_BACKOFF_MILLIS: List<Long> = listOf(250L, 500L, 1_000L, 2_000L, 4_000L, 8_000L, 10_000L)

    const val CONNECT_TIMEOUT_MILLIS: Int = 5_000

    /** The emulator's view of the host loopback, where `adb forward tcp:8765` publishes the phone. */
    const val EMULATOR_HOST: String = "10.0.2.2"

    const val DEFAULT_PORT: Int = 8765
  }
}
