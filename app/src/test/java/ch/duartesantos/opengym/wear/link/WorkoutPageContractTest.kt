package ch.duartesantos.opengym.wear.link

import ch.duartesantos.opengym.wear.data.ExerciseSelection
import ch.duartesantos.opengym.wear.data.WorkoutState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M7.1/M7.3 at the level the contract is written at: a `state` frame in, the workout page's list out,
 * and a row tap out as exactly one command whose wait always ends.
 *
 * Contract: `docs/notes/19-workout-page-wheel-plan.md` §2.1 (the list), §2.2 (`selectExercise`) and
 * §2.4 rule 6 (the pending row). Every rule in §2.1 has a test here, named after the rule.
 */
class WorkoutPageContractTest {

  private fun TestScope.repository(
    transport: LoopbackTransport,
    clock: MutableTimeSource,
    ackTimeoutMillis: Long = 5_000L,
    warn: (String) -> Unit = {},
  ) =
    TransportWorkoutRepository(
      transport = transport,
      timeSource = clock,
      scope = backgroundScope,
      tickIntervalMillis = 1_000L,
      ackTimeoutMillis = ackTimeoutMillis,
      warn = warn,
    )

  private fun commands(transport: LoopbackTransport): List<CommandFrame> =
    transport.sentFrames.mapNotNull { WireProtocol.decode(it) as? CommandFrame }

  private fun ack(id: String, result: AckResult, reason: String? = null, rev: Long = 99L) =
    WireProtocol.encode(AckFrame(id = id, result = result, reason = reason, rev = rev, sentAtMillis = 0L))

  // --- §2.1: the list ---------------------------------------------------------------------------

  /** Rule 1 and rule 2: present, in order, and `entries[i].index == i`. */
  @Test
  fun `entries reach the list in order, and every row's index is its position`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock)

    transport.emitIncoming(
      WireProtocol.encode(
        TestFrames.state(
          rev = 1,
          sentAtMillis = clock.now,
          workout =
            TestFrames.workout(
              exerciseIndex = 1,
              exerciseCount = 3,
              entries =
                listOf(
                  TestFrames.entry(0, "Lat Pulldown", 3, 3),
                  TestFrames.entry(1, "Dumbbell Row", 0, 3),
                  TestFrames.entry(2, "Barbell Squat", 1, 5),
                ),
            ),
        ),
      ),
    )
    runCurrent()

    val rows = repository.observeWorkout().value.listRows
    assertEquals(3, rows.size)
    assertEquals(rows.indices.toList(), rows.map { it.index })
    assertEquals(listOf("Lat Pulldown", "Dumbbell Row", "Barbell Squat"), rows.map { it.name })
    assertEquals(listOf(3, 0, 1), rows.map { it.setsDone })
    assertEquals(listOf(3, 3, 5), rows.map { it.setsTotal })
  }

  /** Rule 4: progress is bounded, and a zero-set exercise is legal. */
  @Test
  fun `a zero-set exercise is a legal row`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock)

    transport.emitIncoming(
      WireProtocol.encode(
        TestFrames.state(
          rev = 1,
          sentAtMillis = clock.now,
          workout =
            TestFrames.workout(
              entries = listOf(TestFrames.entry(0, "Mobility", 0, 0)),
            ),
        ),
      ),
    )
    runCurrent()

    val row = repository.observeWorkout().value.listRows.single()
    assertEquals(0, row.setsDone)
    assertEquals(0, row.setsTotal)
  }

  /** Rule 6: no `entries` — an older phone, or a recorded fixture. One row, no crash, one notice. */
  @Test
  fun `a frame with no entries renders a one-row list and warns exactly once`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val warnings = mutableListOf<String>()
    val repository = repository(transport, clock, warn = { warnings += it })

    transport.emitIncoming(
      WireProtocol.encode(
        TestFrames.state(
          rev = 1,
          sentAtMillis = clock.now,
          workout =
            TestFrames.workout(
              exerciseIndex = 1,
              exerciseCount = 3,
              exerciseName = "Dumbbell Row",
              setIndex = 2,
              setCount = 4,
            ),
        ),
      ),
    )
    runCurrent()

    val mirrored = repository.observeWorkout().value
    assertTrue(mirrored.isWorkoutActive)
    assertTrue(mirrored.exercises.isEmpty())
    // The fallback: one row, built from the scalars, never empty and never a crash.
    val rows = mirrored.listRows
    assertEquals(1, rows.size)
    assertEquals(1, rows[0].index)
    assertEquals("Dumbbell Row", rows[0].name)
    assertEquals(1, rows[0].setsDone) // setIndex 2 means one set is done
    assertEquals(4, rows[0].setsTotal)

    // A second frame without entries must not log again.
    transport.emitIncoming(
      WireProtocol.encode(
        TestFrames.state(rev = 2, sentAtMillis = clock.now, workout = TestFrames.workout(exerciseName = "Still none")),
      ),
    )
    runCurrent()
    assertEquals(1, warnings.size)
  }

  /** Rule 5: a missing start is null, and a zero start is null too — never epoch 0. */
  @Test
  fun `startedAt is null when absent or zero, so the timer is hidden rather than absurd`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock)

    transport.emitIncoming(
      WireProtocol.encode(
        TestFrames.state(rev = 1, sentAtMillis = clock.now, workout = TestFrames.workout(startedAtMillis = null)),
      ),
    )
    runCurrent()
    assertNull(repository.observeWorkout().value.startedAtMillis)

    // A zero that survived decoding would render 20 491 days. The decoder drops it, so it can never
    // reach the screen; this asserts the second line of defence as well.
    transport.emitIncoming(
      WireProtocol.encode(
        TestFrames.state(rev = 2, sentAtMillis = clock.now, workout = TestFrames.workout(startedAtMillis = 0L)),
      ),
    )
    runCurrent()
    assertNull(repository.observeWorkout().value.startedAtMillis)

    transport.emitIncoming(
      WireProtocol.encode(
        TestFrames.state(rev = 3, sentAtMillis = clock.now, workout = TestFrames.workout(startedAtMillis = 1_791_665_000_000L)),
      ),
    )
    runCurrent()
    assertEquals(1_791_665_000_000L, repository.observeWorkout().value.startedAtMillis)
    // And the elapsed time is derived locally, from the watch's own clock.
    assertEquals(42L, repository.observeWorkout().value.elapsedSeconds(1_791_665_042_000L))
  }

  // --- §2.2 / §2.4 rule 6: the tap --------------------------------------------------------------

  @Test
  fun `selectExercise sends exactly one absolute frame, pending, and the matching state ends the wait`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock)

    transport.emitIncoming(
      WireProtocol.encode(
        TestFrames.state(
          rev = 7,
          sentAtMillis = clock.now,
          workout = TestFrames.workout(exerciseIndex = 0, exerciseCount = 3),
        ),
      ),
    )
    runCurrent()

    repository.selectExercise(2)
    runCurrent()

    val sent = commands(transport)
    assertEquals(1, sent.size)
    assertEquals(CommandName.SELECT_EXERCISE, sent[0].name)
    assertEquals(2, sent[0].index)
    assertEquals("absolute, so it carries the rev the user acted on", 7L, sent[0].baseRev)
    assertEquals(ExerciseSelection.Pending(2), repository.observeExerciseSelection().value)

    // A state for something else does not end the wait...
    transport.emitIncoming(
      WireProtocol.encode(
        TestFrames.state(rev = 8, sentAtMillis = clock.now, workout = TestFrames.workout(exerciseIndex = 0)),
      ),
    )
    runCurrent()
    assertEquals(ExerciseSelection.Pending(2), repository.observeExerciseSelection().value)

    // ...and the state that carries the index does.
    transport.emitIncoming(
      WireProtocol.encode(
        TestFrames.state(rev = 9, sentAtMillis = clock.now, workout = TestFrames.workout(exerciseIndex = 2)),
      ),
    )
    runCurrent()
    assertEquals(ExerciseSelection.Idle, repository.observeExerciseSelection().value)
    assertEquals(2, repository.observeWorkout().value.exerciseIndex)
  }

  @Test
  fun `a rejected selectExercise abandons the pending row, whatever the reason`() = runTest {
    for (reason in listOf("invalid", "noWorkout", "stale")) {
      val clock = MutableTimeSource()
      val transport = LoopbackTransport()
      val repository = repository(transport, clock)

      transport.emitIncoming(
        WireProtocol.encode(
          TestFrames.state(rev = 1, sentAtMillis = clock.now, workout = TestFrames.workout()),
        ),
      )
      runCurrent()
      repository.selectExercise(2)
      runCurrent()

      val id = commands(transport).single().id
      transport.emitIncoming(ack(id, AckResult.REJECTED, reason))
      runCurrent()

      assertEquals("$reason must release the row", ExerciseSelection.Abandoned(2), repository.observeExerciseSelection().value)
    }
  }

  @Test
  fun `an applied ack does not release the pending row, and duplicate does`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock)

    transport.emitIncoming(
      WireProtocol.encode(TestFrames.state(rev = 1, sentAtMillis = clock.now, workout = TestFrames.workout())),
    )
    runCurrent()
    repository.selectExercise(2)
    runCurrent()

    val applied = commands(transport).single().id
    transport.emitIncoming(ack(applied, AckResult.APPLIED))
    runCurrent()
    // §2.2 guarantees the matching state; releasing here would switch the screen before it arrives.
    assertEquals(ExerciseSelection.Pending(2), repository.observeExerciseSelection().value)

    // A replay answered `duplicate` publishes nothing, so it is the end of the road for the row.
    repository.selectExercise(1)
    runCurrent()
    val duplicate = commands(transport).last().id
    transport.emitIncoming(ack(duplicate, AckResult.DUPLICATE))
    runCurrent()
    assertEquals(ExerciseSelection.Abandoned(1), repository.observeExerciseSelection().value)
  }

  @Test
  fun `a retry refusal keeps the row pending, and the spent budget releases it`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock, ackTimeoutMillis = 1_000L)

    transport.emitIncoming(
      WireProtocol.encode(TestFrames.state(rev = 1, sentAtMillis = clock.now, workout = TestFrames.workout())),
    )
    runCurrent()
    repository.selectExercise(2)
    runCurrent()
    val id = commands(transport).single().id

    // `busy` is the retryable refusal: the row must NOT be released, the command is re-sent as is.
    transport.emitIncoming(ack(id, AckResult.REJECTED, "busy"))
    runCurrent()
    assertEquals(ExerciseSelection.Pending(2), repository.observeExerciseSelection().value)

    // The sweeper is driven by virtual time; the timeout comparison uses the injected clock, so both
    // advance together (the same shape as the pre-existing retry test).
    clock.advanceBy(1_000)
    advanceTimeBy(1_000)
    runCurrent()
    assertEquals(2, commands(transport).size)
    assertEquals("the retry keeps the original id", id, commands(transport)[1].id)

    // Two more sweeps spend MAX_COMMAND_ATTEMPTS, and then the row is released - the third of the
    // three ways a tap can end without a state, so it can never stay pending for ever.
    repeat(2) {
      clock.advanceBy(1_000)
      advanceTimeBy(1_000)
      runCurrent()
    }
    assertEquals(WireProtocol.MAX_COMMAND_ATTEMPTS, commands(transport).size)
    assertEquals(ExerciseSelection.Abandoned(2), repository.observeExerciseSelection().value)
  }

  @Test
  fun `a frame that lost the race cannot abandon a newer tap`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock)

    transport.emitIncoming(
      WireProtocol.encode(TestFrames.state(rev = 1, sentAtMillis = clock.now, workout = TestFrames.workout())),
    )
    runCurrent()

    repository.selectExercise(1)
    runCurrent()
    val first = commands(transport).single().id
    repository.selectExercise(2)
    runCurrent()

    // The first tap's refusal arrives after the user has already tapped a second row.
    transport.emitIncoming(ack(first, AckResult.REJECTED, "invalid"))
    runCurrent()

    assertEquals(ExerciseSelection.Pending(2), repository.observeExerciseSelection().value)
  }

  @Test
  fun `nothing about the list or the tap makes the watch invent a workout`() = runTest {
    val clock = MutableTimeSource()
    val transport = LoopbackTransport()
    val repository = repository(transport, clock)

    transport.emitIncoming(WireProtocol.encode(TestFrames.state(rev = 1, sentAtMillis = clock.now, workout = null)))
    runCurrent()

    val none: WorkoutState = repository.observeWorkout().value
    assertEquals(false, none.isWorkoutActive)
    assertEquals(0, none.listRows.size) // `listRows` of `None` is empty; the screen shows the empty message
    repository.selectExercise(0)
    runCurrent()
    assertEquals(1, commands(transport).size) // the phone is the one that refuses it
  }
}
