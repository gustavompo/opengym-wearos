package ch.duartesantos.opengym.wear.link

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DataLayerTransport] against a hand-driven [DataLayerClient].
 *
 * The premise of these tests: **the Data Layer itself cannot be exercised on this machine**
 * so the transport's contract is proven against a fake
 * and the GMS binding is reported as unverified. What is asserted here is real behaviour, not
 * "a method was called": which node is chosen, what happens when it goes away, where the bytes land,
 * and what is dropped.
 */
class DataLayerTransportTest {

  private fun TestScope.transport(
    client: FakeDataLayerClient,
    rescanMillis: Long = 10_000L,
    settleMillis: Long = 100L,
  ) =
    DataLayerTransport(
      client = client,
      scope = backgroundScope,
      nodeRescanIntervalMillis = rescanMillis,
      nodeChangeSettleMillis = settleMillis,
    )

  // --- connection lifecycle ----------------------------------------------------------------------

  @Test
  fun `no reachable node means Disconnected and a send writes nothing`() = runTest {
    val client = FakeDataLayerClient(reachable = emptyList())
    val transport = transport(client)
    transport.start()
    runCurrent()

    assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
    assertNull("no node may be selected when there is none", transport.selectedNodeId)

    assertEquals(SendOutcome.NotConnected, transport.send("{\"v\":1}"))
    assertTrue("a frame must not reach a node that is not there", client.sent.isEmpty())
  }

  @Test
  fun `a reachable phone becomes Connected and is the send target`() = runTest {
    val client = FakeDataLayerClient()
    val transport = transport(client)
    transport.start()
    runCurrent()

    assertEquals(ConnectionState.Connected, transport.connectionState.value)
    assertEquals(FakeDataLayerClient.DEFAULT_NODE.id, transport.selectedNodeId)
  }

  @Test
  fun `the phone disappearing is a Disconnected event and coming back reconnects`() = runTest {
    val client = FakeDataLayerClient()
    val transport = transport(client)
    transport.start()
    runCurrent()
    assertEquals(ConnectionState.Connected, transport.connectionState.value)

    client.setReachableNodes(emptyList())
    runCurrent()
    assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
    assertNull(transport.selectedNodeId)

    client.setReachableNodes(listOf(FakeDataLayerClient.DEFAULT_NODE))
    advanceTimeBy(500)
    runCurrent()
    assertEquals(ConnectionState.Connected, transport.connectionState.value)
    assertEquals(FakeDataLayerClient.DEFAULT_NODE.id, transport.selectedNodeId)
  }

  @Test
  fun `a different node is reported as a reconnect, not a silent retarget`() = runTest {
    val client = FakeDataLayerClient()
    val transport = transport(client)
    val seen = mutableListOf<ConnectionState>()
    backgroundScope.launch { transport.connectionState.collect { seen += it } }
    transport.start()
    runCurrent()
    assertEquals(ConnectionState.Connected, transport.connectionState.value)

    // The phone is replaced (reinstall, a second paired phone, a different node id entirely).
    client.setReachableNodes(listOf(PhoneNode(id = "phone-2", nearby = true)))
    advanceTimeBy(200)
    runCurrent()

    assertEquals("phone-2", transport.selectedNodeId)
    assertEquals(ConnectionState.Connected, transport.connectionState.value)

    // The transition must pass through `Connecting`: that is what makes the repository re-send
    // `hello` and pick up the new phone's state (`` — a new phone process
    // restarts `rev` at 1). A `Connected` that never leaves `Connected` is a no-op for a StateFlow
    // collector, so the repository would keep rendering the old phone's state forever.
    val firstConnected = seen.indexOf(ConnectionState.Connected)
    val lastConnected = seen.lastIndexOf(ConnectionState.Connected)
    assertTrue(
      "expected Connecting between the two Connected values, saw $seen",
      seen.subList(firstConnected, lastConnected).contains(ConnectionState.Connecting),
    )
  }

  @Test
  fun `a refused send is reported as NotConnected and takes the link down`() = runTest {
    val client = FakeDataLayerClient()
    val transport = transport(client)
    transport.start()
    runCurrent()

    client.nextOutcome = SendOutcome.NotConnected
    assertEquals(SendOutcome.NotConnected, transport.send("{\"v\":1}"))
    assertEquals("a refused send is a dead link", ConnectionState.Disconnected, transport.connectionState.value)
    assertTrue(client.sent.isEmpty())
  }

  @Test
  fun `close releases the client and is idempotent`() = runTest {
    val client = FakeDataLayerClient()
    val transport = transport(client)
    transport.start()
    runCurrent()

    transport.close()
    assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
    assertTrue(client.closed)

    // Second close: a `MainActivity` recreation and a test teardown can both reach here.
    transport.close()
    assertTrue(client.closed)
  }

  // --- inbound -----------------------------------------------------------------------------------

  @Test
  fun `only the opengym path is routed into incoming`() = runTest {
    val client = FakeDataLayerClient()
    val transport = transport(client)
    val received = mutableListOf<String>()
    backgroundScope.launch { transport.incoming.collect { received += it } }
    transport.start()
    runCurrent()

    client.emitFrame("{\"v\":1,\"type\":\"pong\",\"t\":1}", path = DataLayerContract.MESSAGE_PATH)
    client.emitFrame("{\"v\":1,\"type\":\"pong\",\"t\":2}", path = "/some/other/feature")
    runCurrent()

    assertEquals(listOf("{\"v\":1,\"type\":\"pong\",\"t\":1}"), received)
  }

  @Test
  fun `a frame from an unexpected node id is still delivered`() = runTest {
    // The watch does not pin the sender: a phone reinstall changes its node id, and refusing frames
    // from the "wrong" id would look exactly like a dead phone. The protocol's own `rev` high-water
    // mark and `role` field are what protect the repository.
    val client = FakeDataLayerClient()
    val transport = transport(client)
    val received = mutableListOf<String>()
    backgroundScope.launch { transport.incoming.collect { received += it } }
    transport.start()
    runCurrent()

    client.emitFrame("{\"v\":1,\"type\":\"pong\",\"t\":7}", nodeId = "some-other-node")
    runCurrent()

    assertEquals(1, received.size)
  }

  @Test
  fun `frames that arrive before the first collector are buffered, in order`() = runTest {
    val client = FakeDataLayerClient()
    val transport = transport(client)
    transport.start()
    runCurrent()

    // Nothing is collecting `incoming` yet. A `SharedFlow(replay = 0)` would lose all three.
    client.emitFrame("{\"v\":1,\"type\":\"pong\",\"t\":1}")
    client.emitFrame("{\"v\":1,\"type\":\"pong\",\"t\":2}")
    client.emitFrame("{\"v\":1,\"type\":\"pong\",\"t\":3}")
    runCurrent()

    val received = mutableListOf<String>()
    backgroundScope.launch { transport.incoming.collect { received += it } }
    runCurrent()

    assertEquals(3, received.size)
    assertTrue(received[0].endsWith("\"t\":1}"))
    assertTrue(received[2].endsWith("\"t\":3}"))
  }

  @Test
  fun `payloads that are not utf-8 are dropped and the link stays up`() = runTest {
    val client = FakeDataLayerClient()
    val transport = transport(client)
    val received = mutableListOf<String>()
    backgroundScope.launch { transport.incoming.collect { received += it } }
    transport.start()
    runCurrent()

    // 0xC3 starts a two-byte sequence; 0x28 can never continue it. `String(bytes, UTF_8)` would
    // silently produce U+FFFD and hand `WireProtocol` a string that is merely invalid JSON.
    client.emitBytes(byteArrayOf(0xC3.toByte(), 0x28, 0x7B, 0x7D))
    client.emitFrame("{\"v\":1,\"type\":\"pong\",\"t\":9}")
    runCurrent()

    assertEquals(listOf("{\"v\":1,\"type\":\"pong\",\"t\":9}"), received)
    assertEquals(ConnectionState.Connected, transport.connectionState.value)
  }

  @Test
  fun `an empty payload is dropped`() = runTest {
    val client = FakeDataLayerClient()
    val transport = transport(client)
    val received = mutableListOf<String>()
    backgroundScope.launch { transport.incoming.collect { received += it } }
    transport.start()
    runCurrent()

    client.emitBytes(ByteArray(0))
    client.emitFrame("   ")
    runCurrent()

    assertTrue(received.isEmpty())
  }

  // the stored item: the cold-start half ----------------------------------------

  /**
   * The case `onDataChanged` cannot cover, and the one the hardware run found: the phone published
   * **before this process existed**, so there is no change event to deliver — ever. The item is
   * sitting in the store, and reading it is the only way the watch sees the workout
   * .
   *
   * The frame legitimately arrives twice — once from the startup read, once from the read on the
   * first node selection — and that is asserted rather than tidied away: repeated delivery is what
   * a trades for having two carriers at all, and the `rev` high-water mark is what makes it
   * harmless one layer up.
   */
  @Test
  fun `a stored state item is read at startup with no change event at all`() = runTest {
    val client = FakeDataLayerClient()
    client.storeItem("{\"v\":1,\"type\":\"pong\",\"t\":42}")
    val transport = transport(client)
    val received = mutableListOf<String>()
    backgroundScope.launch { transport.incoming.collect { received += it } }

    transport.start()
    runCurrent()

    assertTrue("the stored frame must be delivered", received.isNotEmpty())
    assertEquals(
      "every copy must be the same frame; a repeat is a no-op for the repository",
      setOf("{\"v\":1,\"type\":\"pong\",\"t\":42}"),
      received.toSet(),
    )
    assertTrue("the store really was queried", client.itemReads >= 1)
  }

  /**
   * The whole point of the fix, end to end and without a device: a workout in the store, a watch
   * process that starts afterwards, and no change event anywhere — the repository must still mirror
   * it. This is the cold-start half of the hardware repro, one layer up from the transport.
   */
  @Test
  fun `a cold start mirrors the workout stored by the phone, with no change event`() = runTest {
    val client = FakeDataLayerClient()
    client.storeItem(
      WireProtocol.encode(
        TestFrames.state(
          rev = 8,
          workout = TestFrames.workout(exerciseName = "triceps press", setIndex = 2, setCount = 3),
        ),
      ),
    )
    val repository =
      TransportWorkoutRepository(
        transport = DataLayerTransport(client = client, scope = backgroundScope),
        timeSource = MutableTimeSource(),
        scope = backgroundScope,
        tickIntervalMillis = 1_000L,
      )

    runCurrent()

    val mirrored = repository.observeWorkout().value
    assertTrue("a stored frame must mirror with no change event", mirrored.isWorkoutActive)
    assertEquals("triceps press", mirrored.exerciseName)
    assertEquals(2, mirrored.currentSetIndex)
    assertEquals(3, mirrored.totalSets)
  }

  @Test
  fun `a stored item on another path is not routed`() = runTest {
    val client = FakeDataLayerClient()
    client.storeItem("{\"v\":1,\"type\":\"pong\",\"t\":1}", path = "/some/other/feature")
    val transport = transport(client)
    val received = mutableListOf<String>()
    backgroundScope.launch { transport.incoming.collect { received += it } }

    transport.start()
    runCurrent()

    assertTrue("stored items are routed by the same path rule as everything else", received.isEmpty())
  }

  @Test
  fun `stored bytes that are not utf-8 are dropped and the link stays up`() = runTest {
    val client = FakeDataLayerClient()
    client.storeBytes(byteArrayOf(0xC3.toByte(), 0x28, 0x7B, 0x7D))
    val transport = transport(client)
    val received = mutableListOf<String>()
    backgroundScope.launch { transport.incoming.collect { received += it } }

    transport.start()
    runCurrent()

    assertTrue(received.isEmpty())
    assertEquals(ConnectionState.Connected, transport.connectionState.value)
  }

  /**
   * A node appearing is a "someone may have written while we were not looking" moment: the item is
   * the carrier of record precisely because it is still there after the writer went away, so the
   * read is repeated on every connect, not only at startup.
   */
  @Test
  fun `the stored item is read again when a node connects`() = runTest {
    val client = FakeDataLayerClient(reachable = emptyList())
    val transport = transport(client)
    val received = mutableListOf<String>()
    backgroundScope.launch { transport.incoming.collect { received += it } }
    transport.start()
    runCurrent()
    val readsBeforeNode = client.itemReads

    client.storeItem("{\"v\":1,\"type\":\"pong\",\"t\":5}")
    client.setReachableNodes(listOf(FakeDataLayerClient.DEFAULT_NODE))
    advanceTimeBy(200)
    runCurrent()

    assertTrue("the read must be repeated on connect, was $readsBeforeNode", client.itemReads > readsBeforeNode)
    assertTrue("the frame stored while no node was reachable must arrive", received.isNotEmpty())
    assertEquals(setOf("{\"v\":1,\"type\":\"pong\",\"t\":5}"), received.toSet())
  }

  @Test
  fun `an empty store reads as nothing, not as an error`() = runTest {
    val client = FakeDataLayerClient()
    val transport = transport(client)
    val received = mutableListOf<String>()
    backgroundScope.launch { transport.incoming.collect { received += it } }

    transport.start()
    runCurrent()

    assertTrue(received.isEmpty())
    assertEquals(ConnectionState.Connected, transport.connectionState.value)
  }

  // --- outbound ----------------------------------------------------------------------------------

  @Test
  fun `send writes exactly the WireProtocol bytes to the selected node on the opengym path`() = runTest {
    val client = FakeDataLayerClient()
    val transport = transport(client)
    transport.start()
    runCurrent()

    val command = WatchCommands.adjustWeight(id = "c-1-1", baseRev = 17L, issuedAtMillis = 1_790_000_000_000L, deltaKg = 0.5)
    val frame = WireProtocol.encode(command)

    assertEquals(SendOutcome.Delivered, transport.send(frame))

    assertEquals(1, client.sent.size)
    val message = client.sent.single()
    assertEquals(FakeDataLayerClient.DEFAULT_NODE.id, message.nodeId)
    assertEquals(DataLayerContract.MESSAGE_PATH, message.path)

    // The bytes are `WireProtocol`'s, byte for byte: no second encoder, and no newline framing (the
    // Data Layer carries discrete payloads, so a frame is one message and must not be terminated).
    assertTrue(message.payload.contentEquals(frame.toByteArray(Charsets.UTF_8)))
    assertFalse("the frame must not be newline-terminated", message.text.endsWith("\n"))

    // And it decodes back to the same frame through the *same* decoder.
    assertEquals(command, WireProtocol.decode(message.text))
  }

  @Test
  fun `send is NotConnected while no node is selected, and delivers again after reconnect`() = runTest {
    val client = FakeDataLayerClient(reachable = emptyList())
    val transport = transport(client)
    transport.start()
    runCurrent()
    assertEquals(SendOutcome.NotConnected, transport.send("{\"v\":1}"))

    client.setReachableNodes(listOf(FakeDataLayerClient.DEFAULT_NODE))
    advanceTimeBy(500)
    runCurrent()
    assertEquals(SendOutcome.Delivered, transport.send("{\"v\":1}"))
    assertEquals(1, client.sent.size)
  }
}
