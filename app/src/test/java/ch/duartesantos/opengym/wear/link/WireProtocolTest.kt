package ch.duartesantos.opengym.wear.link

import ch.duartesantos.opengym.wear.data.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract from `docs/protocol.md`, checked at the level where it is defined.
 *
 * Decoding is total by design: a malformed or unknown frame must be ignored, never crash the link.
 * The "does it survive" cases below are the reason [WireProtocol.decode] returns null instead of
 * throwing.
 */
class WireProtocolTest {

  private val now = 1_790_000_000_000L

  // --- round trips -----------------------------------------------------------------------------

  @Test
  fun `hello round trips and keeps the protocol list`() {
    val hello = HelloFrame(role = Role.WATCH, proto = listOf(1), sentAtMillis = now)
    assertEquals(hello, WireProtocol.decode(WireProtocol.encode(hello)))
    val phone = hello.copy(role = Role.PHONE)
    assertEquals(phone, WireProtocol.decode(WireProtocol.encode(phone)))
  }

  @Test
  fun `state round trips with a workout`() {
    val state =
      TestFrames.state(
        rev = 17,
        sentAtMillis = now,
        workout = TestFrames.workout(previousWeightKg = 20.0, previousReps = 8),
        rest = TestFrames.runningRest(endsAtMillis = now + 90_000),
      )
    assertEquals(state, WireProtocol.decode(WireProtocol.encode(state)))
  }

  // --- task 2: the profile unit -----------------------------------------------------------------

  @Test
  fun `state round trips the profile unit`() {
    for (unit in WeightUnit.entries) {
      val state =
        TestFrames.state(
          rev = 18,
          sentAtMillis = now,
          workout = TestFrames.workout().copy(unit = unit),
          rest = RestSnapshot.Idle,
        )
      val decoded = WireProtocol.decode(WireProtocol.encode(state)) as StateFrame
      assertEquals(unit, decoded.workout?.unit)
      assertEquals(state, decoded)
    }
  }

  @Test
  fun `a frame with no unit is a kilogram frame`() {
    // The wire spelling the phone sends for a kg profile, with the field deleted: an older phone
    // build must not change what the user sees.
    val json = WireProtocol.encode(TestFrames.state(rev = 19, sentAtMillis = now, workout = TestFrames.workout(), rest = RestSnapshot.Idle))
    val withoutUnit = json.replace(",\"unit\":\"kg\"", "")
    assertTrue("the test must actually remove the field", withoutUnit != json && !withoutUnit.contains("unit"))
    val decoded = WireProtocol.decode(withoutUnit) as StateFrame
    assertEquals(WeightUnit.Kilograms, decoded.workout?.unit)
  }

  @Test
  fun `an unknown unit string is a kilogram frame`() {
    val json = WireProtocol.encode(TestFrames.state(rev = 20, sentAtMillis = now, workout = TestFrames.workout(), rest = RestSnapshot.Idle))
    val weird = json.replace("\"unit\":\"kg\"", "\"unit\":\"stone\"")
    assertTrue(weird != json)
    assertEquals(WeightUnit.Kilograms, (WireProtocol.decode(weird) as StateFrame).workout?.unit)
  }

  @Test
  fun `state round trips with a null workout and an idle rest`() {
    val state = TestFrames.state(rev = 3, sentAtMillis = now, workout = null, rest = RestSnapshot.Idle)
    val decoded = WireProtocol.decode(WireProtocol.encode(state))
    assertEquals(state, decoded)
    assertNull((decoded as StateFrame).workout)
    assertEquals(0, decoded.rest.remainingSeconds(now))
  }

  @Test
  fun `state round trips with a paused rest`() {
    val rest = RestSnapshot(running = true, endsAtMillis = null, totalSeconds = 90, paused = true, leftSeconds = 42)
    val state = TestFrames.state(rev = 4, sentAtMillis = now, workout = TestFrames.workout(), rest = rest)
    assertEquals(state, WireProtocol.decode(WireProtocol.encode(state)))
  }

  @Test
  fun `all seven commands round trip with their args`() {
    val id = "c-$now-1"
    val commands =
      listOf(
        WatchCommands.completeSet(id, 7, now),
        WatchCommands.nextExercise(id, 7, now),
        WatchCommands.previousExercise(id, 7, now),
        WatchCommands.adjustWeight(id, 7, now, deltaKg = -0.5),
        WatchCommands.adjustReps(id, 7, now, delta = 1),
        WatchCommands.adjustRest(id, 7, now, deltaSeconds = -15),
        WatchCommands.skipRest(id, 7, now),
      )

    for (command in commands) {
      val decoded = WireProtocol.decode(WireProtocol.encode(command))
      assertEquals("round trip failed for ${command.name}", command, decoded)
    }
  }

  @Test
  fun `command args survive as typed values, not strings`() {
    val weight = WireProtocol.decode(WireProtocol.encode(WatchCommands.adjustWeight("c-1", 7, now, 0.5)))
    assertEquals(0.5, (weight as CommandFrame).deltaKg!!, 0.0)

    val reps = WireProtocol.decode(WireProtocol.encode(WatchCommands.adjustReps("c-1", 7, now, -1)))
    assertEquals(-1, (reps as CommandFrame).delta!!)

    val rest = WireProtocol.decode(WireProtocol.encode(WatchCommands.adjustRest("c-1", 7, now, 15)))
    assertEquals(15, (rest as CommandFrame).deltaSeconds!!)
  }

  @Test
  fun `ack round trips for each result`() {
    for (result in AckResult.entries) {
      val ack = AckFrame("c-$now-2", result, reason = "stale", rev = 18, sentAtMillis = now)
      assertEquals(ack, WireProtocol.decode(WireProtocol.encode(ack)))
    }
  }

  @Test
  fun `ping and pong round trip`() {
    assertEquals(PingFrame(now), WireProtocol.decode(WireProtocol.encode(PingFrame(now))))
    assertEquals(PongFrame(now), WireProtocol.decode(WireProtocol.encode(PongFrame(now))))
  }

  // --- the failures the contract names ---------------------------------------------------------

  @Test
  fun `a frame from a different protocol version is ignored`() {
    assertNull(WireProtocol.decode("""{"v":2,"type":"state","rev":1,"sentAt":0,"rest":{"running":false,"total":0}}"""))
    assertNull(WireProtocol.decode("""{"v":0,"type":"hello","role":"phone","proto":[1],"sentAt":0}"""))
  }

  @Test
  fun `an unknown type is ignored`() {
    assertNull(WireProtocol.decode("""{"v":1,"type":"telepathy","payload":{}}"""))
  }

  @Test
  fun `malformed frames are ignored rather than thrown`() {
    val junk =
      listOf(
        "",
        "   ",
        "not json at all",
        "{",
        """{"v":1}""",
        """{"type":"state"}""",
        """{"v":1,"type":"state","rev":"seventeen","sentAt":1,"rest":{"running":false,"total":0}}""",
        """{"v":1,"type":"state","rev":1,"sentAt":1}""",
        """{"v":1,"type":"state","rev":1,"sentAt":1,"workout":"yes","rest":{"running":false,"total":0}}""",
        """{"v":1,"type":"state","rev":1,"sentAt":1,"workout":{"id":"x"},"rest":{"running":false,"total":0}}""",
        """{"v":1,"type":"ack","id":"c-1","result":"maybe"}""",
        """{"v":1,"type":"command","id":"c-1","name":"teleport","baseRev":1,"issuedAt":1,"args":{}}""",
        """{"v":1,"type":"hello","role":"cat","proto":[1],"sentAt":0}""",
        """{"v":1,"type":"hello","role":"phone","sentAt":0}""",
        """[1,2,3]""",
      )
    for (text in junk) {
      assertNull("expected null for: $text", WireProtocol.decode(text))
    }
  }

  @Test
  fun `unknown fields are ignored, so a newer phone can add fields without breaking the watch`() {
    val json =
      """{"v":1,"type":"state","rev":9,"sentAt":$now,"heartRate":133,"workout":{"id":"a","name":"b",
         "exerciseIndex":0,"exerciseCount":1,"exerciseName":"c","setIndex":1,"setCount":3,
         "weightKg":20.0,"reps":8,"previousWeightKg":null,"previousReps":null,"riff":true},
         "rest":{"running":false,"total":0,"whenever":7}}"""
        .replace("\n", " ")
    val decoded = WireProtocol.decode(json)
    assertTrue(decoded is StateFrame)
    assertEquals(9L, (decoded as StateFrame).rev)
  }

  // --- version negotiation ---------------------------------------------------------------------

  @Test
  fun `negotiation picks the highest common version`() {
    assertEquals(3, WireProtocol.negotiate(listOf(1, 2, 3), listOf(3, 2, 1)))
    assertEquals(1, WireProtocol.negotiate(listOf(1), listOf(1, 2)))
    assertEquals(1, WireProtocol.negotiate(listOf(1, 2), listOf(1)))
  }

  @Test
  fun `negotiation fails when the lists do not intersect`() {
    assertNull(WireProtocol.negotiate(listOf(1), listOf(2)))
    assertNull(WireProtocol.negotiate(listOf(1), emptyList()))
  }

  // --- rest is an absolute deadline, not a countdown --------------------------------------------

  @Test
  fun `remaining rest is derived from the absolute deadline, so a late frame is still right`() {
    val rest = TestFrames.runningRest(endsAtMillis = now + 90_000)

    assertEquals(90, rest.remainingSeconds(now))
    // A frame delivered 30 s late produces the correct answer with no extra bookkeeping.
    assertEquals(60, rest.remainingSeconds(now + 30_000))
    assertEquals(1, rest.remainingSeconds(now + 89_500))

    // Past the deadline: zero, never negative.
    assertEquals(0, rest.remainingSeconds(now + 90_000))
    assertEquals(0, rest.remainingSeconds(now + 5_000_000))
  }

  @Test
  fun `a paused rest does not count down`() {
    val rest = RestSnapshot(running = true, endsAtMillis = null, totalSeconds = 90, paused = true, leftSeconds = 42)
    assertEquals(42, rest.remainingSeconds(now))
    assertEquals(42, rest.remainingSeconds(now + 10_000))
  }

  @Test
  fun `an idle rest has no remaining time and uses the exercise's configured rest as its total`() {
    val rest = RestSnapshot.Idle.copy(restWhenIdleSeconds = 120)
    assertEquals(0, rest.remainingSeconds(now))
    assertEquals(120, rest.totalForDisplay(now))
  }

  @Test
  fun `the ring denominator is the rest that was started, never less than what is left`() {
    val rest = TestFrames.runningRest(endsAtMillis = now + 90_000, totalSeconds = 90)
    assertEquals(90, rest.totalForDisplay(now + 30_000))

    // A +15 grows `total`; a `total` below the remaining time is clamped rather than producing a >100% ring.
    val inconsistent = rest.copy(totalSeconds = 10)
    assertEquals(90, inconsistent.totalForDisplay(now))
  }
}
