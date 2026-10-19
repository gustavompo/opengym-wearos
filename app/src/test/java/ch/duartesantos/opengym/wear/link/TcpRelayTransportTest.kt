package ch.duartesantos.opengym.wear.link

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TcpRelayTransport] against a **real** `ServerSocket` on the loopback interface.
 *
 * Why a JVM unit test and not an instrumented one: this code is `java.net` and nothing else — no
 * Context, no permissions, no Activity, no UI. What can actually fail here is the socket state
 * machine (read loop, EOF detection, backoff, reconnect, framing), and all of it is testable in a
 * plain JVM test that runs in CI without a device. A mock transport would prove none of it, which
 * is why this file has no mock.
 *
 * What this does **not** prove: Android's `INTERNET` permission, the emulator's `10.0.2.2`
 * host-loopback hop, or `adb forward`. Those need a device and are listed as unverified in
 * ``.
 */
class TcpRelayTransportTest {

  /** Tiny stand-in for the phone's listener: accepts clients, records lines, can drop a client. */
  private class RelayServer : AutoCloseable {
    // Bound to the IPv4 loopback literal on purpose: `InetAddress.getLoopbackAddress()` returns
    // `::1` on a dual-stack JVM, and a client that dials 127.0.0.1 then never reaches it.
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort

    /** Every line received from every client, in arrival order. */
    val lines = LinkedBlockingQueue<String>()

    @Volatile private var current: Socket? = null

    @Volatile private var accepted: Int = 0

    private val acceptThread =
      Thread({ acceptLoop() }, "relay-accept").apply {
        isDaemon = true
        start()
      }

    private fun acceptLoop() {
      while (!server.isClosed) {
        val client =
          try {
            server.accept()
          } catch (_: IOException) {
            return
          }
        current = client
        accepted += 1
        Thread({ readLoop(client) }, "relay-read").apply {
          isDaemon = true
          start()
        }
      }
    }

    private fun readLoop(socket: Socket) {
      try {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        while (true) {
          val line = reader.readLine() ?: break
          lines.put(line)
        }
      } catch (_: IOException) {
        // client vanished; the accept loop is still live
      }
    }

    fun connectionCount(): Int = accepted

    /** The client connect() can complete before this thread reaches accept(), so wait for it. */
    private fun awaitClient(timeoutMillis: Long = 5_000): Socket? {
      val deadline = System.currentTimeMillis() + timeoutMillis
      while (System.currentTimeMillis() < deadline) {
        current?.let { return it }
        Thread.sleep(5)
      }
      return null
    }

    /** Called from the test thread only, so one writer per send is fine. */
    fun send(line: String): Boolean {
      val socket = awaitClient() ?: return false
      return try {
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        writer.write(line + "\n")
        writer.flush()
        true
      } catch (_: IOException) {
        false
      }
    }

    fun dropCurrentClient() {
      try {
        current?.close()
      } catch (_: IOException) {
        // already gone
      }
      current = null
    }

    override fun close() {
      dropCurrentClient()
      try {
        server.close()
      } catch (_: IOException) {
        // nothing useful to do
      }
      acceptThread.interrupt()
    }
  }

  private fun transport(
    server: RelayServer,
    backoff: List<Long> = listOf(50L, 100L, 200L),
  ) =
    TcpRelayTransport(
      host = "127.0.0.1",
      port = server.port,
      scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
      connectTimeoutMillis = 2_000,
      backoffMillis = backoff,
    )

  private suspend fun awaitConnected(transport: TcpRelayTransport) {
    withTimeout(10_000) { transport.connectionState.first { it == ConnectionState.Connected } }
  }

  private suspend fun awaitCondition(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return
      delay(10)
    }
    throw AssertionError("condition not met within ${timeoutMillis}ms")
  }

  @Test
  fun `a command reaches the phone and a state frame reaches the watch over one real socket`() =
    runBlocking {
      RelayServer().use { server ->
        val transport = transport(server)
        try {
          transport.start()
          awaitConnected(transport)

          // --- watch -> phone ---
          val command =
            WatchCommands.adjustWeight("c-1790-1", baseRev = 7, issuedAtMillis = 1_790L, deltaKg = 0.5)
          val encoded = WireProtocol.encode(command)
          assertEquals(SendOutcome.Delivered, transport.send(encoded))

          val asReceivedByPhone = server.lines.poll(10, TimeUnit.SECONDS)
          assertNotNull("the phone never received the frame", asReceivedByPhone)

          // Byte-for-byte on the wire, and still the same command once parsed.
          assertEquals(encoded, asReceivedByPhone)
          assertEquals(command, WireProtocol.decode(asReceivedByPhone!!))

          // --- phone -> watch ---
          val state =
            TestFrames.state(
              rev = 8,
              sentAtMillis = 1_790_000_000_000L,
              workout = TestFrames.workout(exerciseName = "Cable Row"),
              rest = TestFrames.runningRest(endsAtMillis = 1_790_000_090_000L),
            )
          assertTrue("the server could not write to the client", server.send(WireProtocol.encode(state)))

          val asReceivedByWatch = withTimeout(10_000) { transport.incoming.first() }
          assertEquals(state, WireProtocol.decode(asReceivedByWatch))
        } finally {
          transport.close()
        }
      }
    }

  @Test
  fun `the transport reconnects after the phone drops the connection`() = runBlocking {
    RelayServer().use { server ->
      val transport = transport(server)
      try {
        transport.start()
        awaitConnected(transport)
        assertEquals(1, transport.connectCount)

        val first = WatchCommands.nextExercise("c-1", baseRev = 1, issuedAtMillis = 1L)
        assertEquals(SendOutcome.Delivered, transport.send(WireProtocol.encode(first)))
        assertNotNull(server.lines.poll(10, TimeUnit.SECONDS))

        // The phone hangs up.
        server.dropCurrentClient()
        awaitCondition { server.connectionCount() >= 2 }
        awaitConnected(transport)

        // Same transport, new socket: commands flow again.
        val second = WatchCommands.skipRest("c-2", baseRev = 2, issuedAtMillis = 2L)
        assertEquals(SendOutcome.Delivered, transport.send(WireProtocol.encode(second)))
        val received = server.lines.poll(10, TimeUnit.SECONDS)
        assertNotNull("nothing arrived after the reconnect", received)
        assertEquals(second, WireProtocol.decode(received!!))
      } finally {
        transport.close()
      }
    }
  }

  @Test
  fun `frames that arrive before anyone collects them are buffered, not dropped`() = runBlocking {
    RelayServer().use { server ->
      val transport = transport(server)
      try {
        transport.start()
        awaitConnected(transport)

        // The real race: the phone answers before the repository has started collecting.
        for (rev in 1L..3L) {
          assertTrue(server.send(WireProtocol.encode(TestFrames.state(rev = rev, sentAtMillis = rev))))
        }
        // Give the reader a moment to have pulled them all in with no collector attached.
        delay(200)

        val received = withTimeout(10_000) { transport.incoming.take(3).toList() }
        assertEquals(3, received.size)
        assertEquals(listOf(1L, 2L, 3L), received.map { (WireProtocol.decode(it) as StateFrame).rev })
      } finally {
        transport.close()
      }
    }
  }

  @Test
  fun `send reports NotConnected rather than throwing when nothing is listening`() = runBlocking {
    // A port nobody is listening on: bind it on the same interface the transport dials, then release it.
    val freePort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    val transport =
      TcpRelayTransport(
        host = "127.0.0.1",
        port = freePort,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        connectTimeoutMillis = 500,
        backoffMillis = listOf(50L, 50L, 50L),
      )
    try {
      transport.start()
      // The loop must keep retrying and never claim Connected.
      awaitCondition(timeoutMillis = 5_000) { transport.connectCount == 0 }
      delay(200)
      assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
      assertEquals(SendOutcome.NotConnected, transport.send("""{"v":1,"type":"ping","t":1}"""))
    } finally {
      transport.close()
    }
  }

  @Test
  fun `close stops reconnecting and further sends report NotConnected`() = runBlocking {
    RelayServer().use { server ->
      val transport = transport(server)
      transport.start()
      awaitConnected(transport)
      val connects = transport.connectCount

      transport.close()
      transport.close()

      assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
      delay(300)
      assertEquals(connects, transport.connectCount)
      assertEquals(SendOutcome.NotConnected, transport.send("""{"v":1,"type":"ping","t":1}"""))
      assertEquals(1, server.connectionCount())
    }
  }
}
