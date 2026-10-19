package ch.duartesantos.opengym.wear.link

import ch.duartesantos.opengym.wear.data.WorkoutState
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mirroring rules from ``, over the [LoopbackTransport] fake — the
 * pluggable-transport half of 's evidence.
 *
 * Virtual time (`runTest`) drives the tick and the ack sweep, so this is deterministic and instant.
 * The socket itself is proven separately, against a real `ServerSocket`, in
 * `TcpRelayTransportTest`.
 */
class TransportWorkoutRepositoryTest {

  private fun TestScope.repository(
    transport: LoopbackTransport,
    clock: MutableTimeSource,
    ackTimeoutMillis: Long = 5_000L,
  ) =
    TransportWorkoutRepository(
      transport = transport,
      timeSource = clock,
      scope = backgroundScope,
      tickIntervalMillis = 1_000L,
      ackTimeoutMillis = ackTimeoutMillis,
    )

  private fun commandFrames(transport: LoopbackTransport): List<CommandFrame> =
    transport.sentFrames.mapNotNull { WireProtocol.decode(it) as? CommandFrame }

  // --- phone -> watch ---------------------------------------------------------------------------

  @Test
  fun `mirrors the projection and counts the rest down from the absolute deadline`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock)

    val frame =
      TestFrames.state(
        rev = 1,
        sentAtMillis = clock.now,
        workout =
          TestFrames.workout(
            exerciseName = "Cable Row",
            exerciseIndex = 1,
            exerciseCount = 3,
            setIndex = 2,
            setCount = 4,
            weightKg = 22.5,
            reps = 10,
            previousWeightKg = 20.0,
            previousReps = 8,
          ),
        rest = TestFrames.runningRest(endsAtMillis = clock.now + 90_000),
      )
    transport.emitIncoming(WireProtocol.encode(frame))
    runCurrent()

    val mirrored = repository.observeWorkout().value
    assertTrue(mirrored.isWorkoutActive)
    assertEquals("Cable Row", mirrored.exerciseName)
    assertEquals(1, mirrored.exerciseIndex)
    assertEquals(3, mirrored.exerciseCount)
    assertEquals(2, mirrored.currentSetIndex)
    assertEquals(4, mirrored.totalSets)
    assertEquals(22.5, mirrored.weightKg, 0.0)
    assertEquals(10, mirrored.reps)
    assertEquals(20.0, mirrored.previousWeightKg!!, 0.0)
    assertEquals(8, mirrored.previousReps!!)
    assertTrue(mirrored.canGoPrevious)
    assertTrue(mirrored.canGoNext)
    assertEquals(90, mirrored.restSecondsRemaining)
    assertEquals(90, mirrored.restSecondsTotal)

    // 40 s of nothing arriving: the value comes from the deadline, not from a local countdown.
    clock.advanceBy(40_000)
    advanceTimeBy(1_000)
    runCurrent()
    assertEquals(50, repository.observeWorkout().value.restSecondsRemaining)

    // Past the deadline the rest is simply over, and the screen goes back to the exercise page.
    clock.advanceBy(60_000)
    advanceTimeBy(1_000)
    runCurrent()
    assertEquals(0, repository.observeWorkout().value.restSecondsRemaining)
    assertFalse(repository.observeWorkout().value.isResting)
  }

  @Test
  fun `a null workout is no active workout, not an error`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock)

    transport.emitIncoming(WireProtocol.encode(TestFrames.state(rev = 1, sentAtMillis = clock.now)))
    runCurrent()

    assertFalse(repository.observeWorkout().value.isWorkoutActive)
    assertEquals(WorkoutState.None, repository.observeWorkout().value)
    assertEquals(ConnectionState.Connected, repository.observeLinkStatus().value.connection)
  }

  @Test
  fun `duplicate and out-of-order revisions are discarded`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock)

    fun frame(rev: Long, name: String) =
      WireProtocol.encode(
        TestFrames.state(
          rev = rev,
          sentAtMillis = clock.now,
          workout = TestFrames.workout(exerciseName = name),
        ),
      )

    transport.emitIncoming(frame(5, "A"))
    runCurrent()
    assertEquals("A", repository.observeWorkout().value.exerciseName)

    // Same rev, different content: says discard, so a redelivered frame cannot flicker the UI.
    transport.emitIncoming(frame(5, "B"))
    runCurrent()
    assertEquals("A", repository.observeWorkout().value.exerciseName)

    transport.emitIncoming(frame(4, "C"))
    runCurrent()
    assertEquals("A", repository.observeWorkout().value.exerciseName)

    transport.emitIncoming(frame(6, "B"))
    runCurrent()
    assertEquals("B", repository.observeWorkout().value.exerciseName)

    // A phone restart sends hello, and the rev legitimately restarts at 1.
    transport.emitIncoming(
      WireProtocol.encode(HelloFrame(Role.PHONE, proto = listOf(1), sentAtMillis = clock.now)),
    )
    runCurrent()
    transport.emitIncoming(frame(1, "D"))
    runCurrent()
    assertEquals("D", repository.observeWorkout().value.exerciseName)
  }

  @Test
  fun `a state older than the staleness threshold is flagged, and still used`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock)

    transport.emitIncoming(
      WireProtocol.encode(
        TestFrames.state(rev = 1, sentAtMillis = clock.now, workout = TestFrames.workout(exerciseName = "A")),
      ),
    )
    runCurrent()
    assertFalse(repository.observeLinkStatus().value.stale)

    clock.advanceBy(WireProtocol.STATE_STALE_AFTER_MS + 1)
    advanceTimeBy(1_000)
    runCurrent()

    assertTrue(repository.observeLinkStatus().value.stale)
    // Stale is a badge: the last known values are still the best truth available.
    assertEquals("A", repository.observeWorkout().value.exerciseName)
  }

  @Test
  fun `an incompatible phone stops commands without blanking the last known state`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock)

    transport.emitIncoming(
      WireProtocol.encode(
        TestFrames.state(rev = 1, sentAtMillis = clock.now, workout = TestFrames.workout(exerciseName = "A")),
      ),
    )
    runCurrent()

    transport.emitIncoming(
      WireProtocol.encode(HelloFrame(Role.PHONE, proto = listOf(99), sentAtMillis = clock.now)),
    )
    runCurrent()

    assertFalse(repository.observeLinkStatus().value.compatible)
    assertEquals("A", repository.observeWorkout().value.exerciseName)

    val before = commandFrames(transport).size
    repository.completeSet()
    runCurrent()
    assertEquals("no command may be sent to an incompatible phone", before, commandFrames(transport).size)
  }

  @Test
  fun `a malformed frame does not disturb the link or the last known state`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock)

    transport.emitIncoming(
      WireProtocol.encode(
        TestFrames.state(rev = 1, sentAtMillis = clock.now, workout = TestFrames.workout(exerciseName = "A")),
      ),
    )
    runCurrent()

    transport.emitIncoming("{ this is not json")
    transport.emitIncoming("""{"v":99,"type":"hello","role":"phone","proto":[99],"sentAt":0}""")
    runCurrent()

    assertEquals("A", repository.observeWorkout().value.exerciseName)
    assertTrue(repository.observeLinkStatus().value.compatible)
  }

  @Test
  fun `the link status distinguishes an unreachable phone from no workout`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock)

    // No workout running, phone reachable.
    transport.emitIncoming(WireProtocol.encode(TestFrames.state(rev = 1, sentAtMillis = clock.now)))
    runCurrent()
    assertFalse(repository.observeWorkout().value.isWorkoutActive)
    assertEquals(ConnectionState.Connected, repository.observeLinkStatus().value.connection)

    // Workout mirrored, then the phone goes away: the state stays, the link says why.
    transport.emitIncoming(
      WireProtocol.encode(
        TestFrames.state(rev = 2, sentAtMillis = clock.now, workout = TestFrames.workout(exerciseName = "A")),
      ),
    )
    runCurrent()
    transport.setConnectionState(ConnectionState.Disconnected)
    runCurrent()

    assertTrue(repository.observeWorkout().value.isWorkoutActive)
    assertEquals("A", repository.observeWorkout().value.exerciseName)
    assertEquals(ConnectionState.Disconnected, repository.observeLinkStatus().value.connection)
  }

  /**
   * The behaviour that matters on real hardware, and the reason decision calls disconnect a
   * first-class event rather than an error path.
   *
   * On a real watch the phone is backgrounded and frozen constantly: the WebView is stopped, the app
   * process may be frozen, and the node ledger takes seconds to admit it. What must *not* happen is
   * the screen going blank, because a blank screen is indistinguishable from "your workout was
   * destroyed" — and it would also be a lie, since the watch still knows the last thing the phone
   * said, down to the absolute rest deadline.
   *
   * The assertion is on the whole observed sequence, not on one sample: any `WorkoutState.None`
   * emitted between the drop and the end of the test would fail this.
   */
  @Test
  fun `a dropped phone keeps the last known workout on screen and never blanks it`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock)

    transport.emitIncoming(
      WireProtocol.encode(
        TestFrames.state(
          rev = 7,
          sentAtMillis = clock.now,
          workout =
            TestFrames.workout(
              exerciseName = "Cable Row",
              exerciseIndex = 1,
              exerciseCount = 3,
              setIndex = 2,
              setCount = 5,
              weightKg = 22.5,
              reps = 10,
            ),
          rest = TestFrames.runningRest(endsAtMillis = clock.now + 90_000),
        ),
      ),
    )
    runCurrent()

    // Start recording only now, so the pre-link `None` initial value is not mistaken for a blank.
    val seenAfterDrop = mutableListOf<WorkoutState>()
    backgroundScope.launch { repository.observeWorkout().collect { seenAfterDrop += it } }
    runCurrent()

    // The phone is pocketed / frozen / swiped away. The transport notices; nothing else happens.
    transport.setConnectionState(ConnectionState.Disconnected)
    runCurrent()

    assertFalse("the UI must be told the phone is gone", repository.observePhoneLink().value.connected)
    assertEquals(ConnectionState.Disconnected, repository.observeLinkStatus().value.connection)

    // The values are still exactly what the phone last said.
    val retained = repository.observeWorkout().value
    assertTrue(retained.isWorkoutActive)
    assertEquals("Cable Row", retained.exerciseName)
    assertEquals(2, retained.currentSetIndex)
    assertEquals(5, retained.totalSets)
    assertEquals(22.5, retained.weightKg, 0.0)
    assertEquals(10, retained.reps)
    assertTrue(retained.isResting)
    assertEquals(90, retained.restSecondsRemaining)

    // 30 s with no phone at all: the ring keeps counting, because `endsAt` is an absolute deadline
    // and not a countdown the transport was supposed to deliver.
    clock.advanceBy(30_000)
    advanceTimeBy(1_000)
    runCurrent()
    assertEquals(60, repository.observeWorkout().value.restSecondsRemaining)
    assertTrue("a state this old is a badge, not a reason to blank", repository.observeLinkStatus().value.stale)

    // Past the deadline the rest is simply over and the exercise page returns — the same path a live
    // link takes. Still the last known workout; still not `None`.
    clock.advanceBy(70_000)
    advanceTimeBy(1_000)
    runCurrent()
    assertFalse(repository.observeWorkout().value.isResting)
    assertEquals(0, repository.observeWorkout().value.restSecondsRemaining)
    assertTrue(repository.observeWorkout().value.isWorkoutActive)
    assertEquals("Cable Row", repository.observeWorkout().value.exerciseName)

    assertTrue(
      "the screen must never blank while the phone is away, saw $seenAfterDrop",
      seenAfterDrop.none { !it.isWorkoutActive },
    )

    // And the empty-state sentence is still the *link's* sentence, not a claim about the workout:
    // if the watch had no workout at all it would say "Phone not connected", which is
    // a different statement from the one this screen is making.
    assertEquals("Phone not connected", emptyWorkoutMessage(repository.observePhoneLink().value))
  }

  // --- watch -> phone ---------------------------------------------------------------------------

  @Test
  fun `commands carry an id, the repository's own name, and the rev the user acted on`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock)

    transport.emitIncoming(
      WireProtocol.encode(TestFrames.state(rev = 42, sentAtMillis = clock.now, workout = TestFrames.workout())),
    )
    runCurrent()

    repository.completeSet()
    repository.adjustWeight(0.5)
    repository.adjustReps(-1)
    repository.adjustRest(-15)
    repository.skipRest()
    repository.nextExercise()
    repository.previousExercise()
    runCurrent()

    val commands = commandFrames(transport)
    assertEquals(
      listOf(
        CommandName.COMPLETE_SET,
        CommandName.ADJUST_WEIGHT,
        CommandName.ADJUST_REPS,
        CommandName.ADJUST_REST,
        CommandName.SKIP_REST,
        CommandName.NEXT_EXERCISE,
        CommandName.PREVIOUS_EXERCISE,
      ),
      commands.map { it.name },
    )

    assertEquals(7, commands.map { it.id }.toSet().size)
    assertTrue(commands.all { it.baseRev == 42L })
    assertTrue(commands.all { it.issuedAtMillis == clock.now })
    assertEquals(0.5, commands[1].deltaKg!!, 0.0)
    assertEquals(-1, commands[2].delta!!)
    assertEquals(-15, commands[3].deltaSeconds!!)
  }

  @Test
  fun `a no-op adjustment sends nothing`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock)

    repository.adjustWeight(0.0)
    repository.adjustReps(0)
    repository.adjustRest(0)
    runCurrent()

    assertEquals(0, commandFrames(transport).size)
  }

  @Test
  fun `commands queued while the phone is unreachable are flushed on reconnect, and the ack clears them`() =
    runTest {
      val clock = MutableTimeSource()
      val transport = LoopbackTransport(initialState = ConnectionState.Disconnected)
      val repository = repository(transport, clock)

      repository.completeSet()
      runCurrent()
      assertEquals(0, transport.sentFrames.size)
      assertEquals(1, repository.observeLinkStatus().value.pendingCommands)

      transport.setConnectionState(ConnectionState.Connected)
      runCurrent()

      // hello first, then the queued command — the order matters on the wire.
      val frames = transport.sentFrames.map { WireProtocol.decode(it) }
      assertEquals(2, frames.size)
      assertTrue(frames[0] is HelloFrame)
      assertEquals(CommandName.COMPLETE_SET, (frames[1] as CommandFrame).name)
      assertEquals(1, repository.observeLinkStatus().value.pendingCommands)

      val id = (frames[1] as CommandFrame).id
      transport.emitIncoming(
        WireProtocol.encode(AckFrame(id, AckResult.APPLIED, reason = null, rev = 2, sentAtMillis = clock.now)),
      )
      runCurrent()
      assertEquals(0, repository.observeLinkStatus().value.pendingCommands)
    }

  @Test
  fun `a rest adjustment older than the TTL is dropped instead of replayed`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport(initialState = ConnectionState.Disconnected)
    val repository = repository(transport, clock)

    repository.adjustRest(15)
    runCurrent()
    assertEquals(1, repository.observeLinkStatus().value.pendingCommands)

    clock.advanceBy(WireProtocol.COMMAND_TTL_MS + 1)
    transport.setConnectionState(ConnectionState.Connected)
    runCurrent()

    // hello went out; the stale rest adjustment did not.
    assertEquals(1, transport.sentFrames.size)
    assertTrue(WireProtocol.decode(transport.sentFrames.single()) is HelloFrame)
    assertEquals(0, repository.observeLinkStatus().value.pendingCommands)
  }

  @Test
  fun `a command with no ack is re-sent with its original id, and abandoned after three attempts`() =
    runTest {
      val clock = MutableTimeSource()
      val transport = LoopbackTransport()
      val repository = repository(transport, clock, ackTimeoutMillis = 1_000L)

      repository.completeSet()
      runCurrent()
      assertEquals(1, commandFrames(transport).size)

      repeat(WireProtocol.MAX_COMMAND_ATTEMPTS + 1) {
        // The sweeper is driven by virtual time; the timeout comparison uses the injected clock.
        clock.advanceBy(1_000)
        advanceTimeBy(1_000)
        runCurrent()
      }

      val ids = commandFrames(transport).map { it.id }
      // The same id every time — that is what lets the phone deduplicate a replay.
      assertEquals(setOf(ids.first()), ids.toSet())
      assertEquals(WireProtocol.MAX_COMMAND_ATTEMPTS, ids.size)
      assertEquals(0, repository.observeLinkStatus().value.pendingCommands)
    }

  @Test
  fun `a duplicate ack is harmless and a stale rejection is terminal`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock, ackTimeoutMillis = 1_000L)

    repository.skipRest()
    runCurrent()
    val id = commandFrames(transport).single().id

    transport.emitIncoming(
      WireProtocol.encode(AckFrame(id, AckResult.DUPLICATE, reason = null, rev = 1, sentAtMillis = clock.now)),
    )
    runCurrent()
    assertEquals(0, repository.observeLinkStatus().value.pendingCommands)

    advanceTimeBy(3_000)
    runCurrent()
    assertEquals("a duplicate ack must end the retries", 1, commandFrames(transport).size)

    repository.adjustReps(1)
    runCurrent()
    val rejectedId = commandFrames(transport).last().id
    transport.emitIncoming(
      WireProtocol.encode(
        AckFrame(rejectedId, AckResult.REJECTED, reason = "stale", rev = 1, sentAtMillis = clock.now),
      ),
    )
    runCurrent()
    assertEquals(0, repository.observeLinkStatus().value.pendingCommands)
  }

  /**
   * ``. A refusal is not automatically final: `busy` means "the phone cannot
   * take it right now", which is what a frozen WebView with no workout-scoped foreground service
   * produces. The tap must survive that, under the same id.
   */
  @Test
  fun `a busy refusal keeps the command in flight and re-sends the same id`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock, ackTimeoutMillis = 1_000L)

    repository.completeSet()
    runCurrent()
    val id = commandFrames(transport).single().id
    assertEquals(1, repository.observeLinkStatus().value.pendingCommands)

    transport.emitIncoming(
      WireProtocol.encode(AckFrame(id, AckResult.REJECTED, reason = "busy", rev = 1, sentAtMillis = clock.now)),
    )
    runCurrent()
    assertEquals(
      "a busy refusal must not drop the user's tap",
      1,
      repository.observeLinkStatus().value.pendingCommands,
    )

    clock.advanceBy(1_000)
    advanceTimeBy(1_000)
    runCurrent()
    assertEquals("the retry carries the original id", listOf(id, id), commandFrames(transport).map { it.id })

    // An unrecognised reason gets the same treatment on purpose: asking
    // again is bounded, silently dropping the tap is not recoverable.
    transport.emitIncoming(
      WireProtocol.encode(
        AckFrame(id, AckResult.REJECTED, reason = "refused-by-a-future-phone", rev = 1, sentAtMillis = clock.now),
      ),
    )
    runCurrent()
    assertEquals(1, repository.observeLinkStatus().value.pendingCommands)
  }

  @Test
  fun `a busy refusal is still abandoned after the attempt budget, and nothing is applied locally`() =
    runTest {
      val clock = MutableTimeSource()
      val transport = LoopbackTransport()
      val repository = repository(transport, clock, ackTimeoutMillis = 1_000L)

      repository.completeSet()
      runCurrent()
      val id = commandFrames(transport).single().id

      repeat(WireProtocol.MAX_COMMAND_ATTEMPTS + 1) {
        transport.emitIncoming(
          WireProtocol.encode(
            AckFrame(id, AckResult.REJECTED, reason = "busy", rev = 1, sentAtMillis = clock.now),
          ),
        )
        clock.advanceBy(1_000)
        advanceTimeBy(1_000)
        runCurrent()
      }

      assertEquals(WireProtocol.MAX_COMMAND_ATTEMPTS, commandFrames(transport).size)
      assertEquals(0, repository.observeLinkStatus().value.pendingCommands)
      assertFalse(
        "the watch never invents the change it could not get the phone to make",
        repository.observeWorkout().value.isWorkoutActive,
      )
    }

  @Test
  fun `a semantic refusal is terminal for stale, invalid and noWorkout`() = runTest {
    for (reason in listOf("stale", "invalid", "noWorkout")) {
      val clock = MutableTimeSource()
      val transport = LoopbackTransport()
      val repository = repository(transport, clock, ackTimeoutMillis = 1_000L)

      repository.completeSet()
      runCurrent()
      val id = commandFrames(transport).single().id
      transport.emitIncoming(
        WireProtocol.encode(AckFrame(id, AckResult.REJECTED, reason = reason, rev = 1, sentAtMillis = clock.now)),
      )
      runCurrent()
      assertEquals("$reason must be terminal", 0, repository.observeLinkStatus().value.pendingCommands)

      clock.advanceBy(5_000)
      advanceTimeBy(5_000)
      runCurrent()
      assertEquals("$reason must not be re-sent", 1, commandFrames(transport).size)
    }
  }

  @Test
  fun `closing the repository closes the transport`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = TransportWorkoutRepository(transport, clock, scope = backgroundScope)

    runCurrent()
    assertEquals(ConnectionState.Connected, transport.connectionState.value)

    repository.close()
    assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
  }
}
