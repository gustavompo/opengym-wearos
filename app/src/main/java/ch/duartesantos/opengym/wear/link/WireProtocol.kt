package ch.duartesantos.opengym.wear.link

import ch.duartesantos.opengym.wear.data.WeightUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The phone↔watch wire format, version 1. Contract: `docs/protocol.md`.
 *
 * Framing is the transport's job (one frame per line on the TCP relay, one frame per DataItem or
 * Message). This file is only the JSON, so it is identical on both transports and is unit-testable
 * without a socket — which is why the contract tests live at this level and the socket test lives
 * one level down.
 *
 * All decoding is total: [decode] answers `null` for anything it cannot make sense of — malformed
 * JSON, a missing field, the wrong type, an unknown `type`, an unsupported `v`. A bad frame must
 * never take the link down, so nothing here throws.
 */
object WireProtocol {

  /** The only major version that exists today. */
  const val VERSION: Int = 1

  /**
   * A `state` older than this is flagged stale by [TransportWorkoutRepository]. It is a badge, not
   * a reason to blank the screen: the last known values are still the best truth available.
   */
  const val STATE_STALE_AFTER_MS: Long = 15_000L

  /**
   * [CommandName.ADJUST_REST] / [CommandName.SKIP_REST] are meaningless once their rest has
   * passed, so a queued one older than this is dropped instead of replayed.
   */
  const val COMMAND_TTL_MS: Long = 60_000L

  /** How long an unacknowledged command waits before the same id is re-sent. */
  const val ACK_TIMEOUT_MS: Long = 5_000L

  /** After this many attempts an unacknowledged command is abandoned (never applied locally). */
  const val MAX_COMMAND_ATTEMPTS: Int = 3

  /** Bound on the outbound queue; a bounded queue plus ids is the whole reliability story. */
  const val MAX_PENDING_COMMANDS: Int = 16

  /** Highest version both sides understand, or null when the lists do not intersect. */
  fun negotiate(local: Collection<Int>, remote: Collection<Int>): Int? =
    local.intersect(remote.toSet()).maxOrNull()

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = false
    allowSpecialFloatingPointValues = false
  }

  // --- encoding --------------------------------------------------------------------------------

  fun encode(frame: LinkFrame): String =
    when (frame) {
      is HelloFrame ->
        buildJsonObject {
          put("v", VERSION)
          put("type", "hello")
          put("role", frame.role.wire)
          put("proto", buildJsonArray { frame.proto.forEach { add(JsonPrimitive(it)) } })
          put("sentAt", frame.sentAtMillis)
        }
      is StateFrame ->
        buildJsonObject {
          put("v", VERSION)
          put("type", "state")
          put("rev", frame.rev)
          put("sentAt", frame.sentAtMillis)
          put("workout", frame.workout?.let(::encodeWorkout) ?: JsonNull)
          put("rest", encodeRest(frame.rest))
        }
      is AckFrame ->
        buildJsonObject {
          put("v", VERSION)
          put("type", "ack")
          put("id", frame.id)
          put("result", frame.result.wire)
          put("reason", frame.reason)
          put("rev", frame.rev)
          put("sentAt", frame.sentAtMillis)
        }
      is CommandFrame ->
        buildJsonObject {
          put("v", VERSION)
          put("type", "command")
          put("id", frame.id)
          put("name", frame.name.wire)
          put("baseRev", frame.baseRev)
          put("issuedAt", frame.issuedAtMillis)
          put("args", JsonObject(frame.args))
        }
      is PingFrame ->
        buildJsonObject {
          put("v", VERSION)
          put("type", "ping")
          put("t", frame.t)
        }
      is PongFrame ->
        buildJsonObject {
          put("v", VERSION)
          put("type", "pong")
          put("t", frame.t)
        }
    }.toString()

  private fun encodeWorkout(workout: WorkoutSnapshot): JsonObject =
    buildJsonObject {
      put("id", workout.id)
      put("name", workout.name)
      put("exerciseIndex", workout.exerciseIndex)
      put("exerciseCount", workout.exerciseCount)
      put("exerciseName", workout.exerciseName)
      put("setIndex", workout.setIndex)
      put("setCount", workout.setCount)
      put("weightKg", workout.weightKg)
      put("reps", workout.reps)
      put("previousWeightKg", workout.previousWeightKg)
      put("previousReps", workout.previousReps)
      // Task 2: the profile's unit, for the watch to render `weightKg` in. Always written, so the
      // two encoders agree on the shape; a kg profile writes "kg" and nothing changes for it.
      put("unit", workout.unit.wireName)
      // M7.3, plan section 2.1. `JsonNull` when null rather than an omitted key: "present whenever
      // `workout` is non-null" is the rule, and a null value is how "the phone could not tell us"
      // is spelled. Epoch 0 is never written - it would decode to a 20 491-day timer on the watch.
      put("startedAt", workout.startedAtMillis)
      put(
        "entries",
        workout.entries?.let { rows ->
          buildJsonArray {
            rows.forEach { row ->
              add(
                buildJsonObject {
                  put("index", row.index)
                  put("name", row.name)
                  put("setsDone", row.setsDone)
                  put("setsTotal", row.setsTotal)
                }
              )
            }
          }
        } ?: JsonNull,
      )
    }

  private fun encodeRest(rest: RestSnapshot): JsonObject =
    buildJsonObject {
      put("running", rest.running)
      put("endsAt", rest.endsAtMillis)
      put("total", rest.totalSeconds)
      put("paused", rest.paused)
      put("left", rest.leftSeconds)
      put("restWhenIdle", rest.restWhenIdleSeconds)
    }

  // --- decoding --------------------------------------------------------------------------------

  fun decode(text: String): LinkFrame? {
    val obj = runCatching { json.parseToJsonElement(text.trim()) as? JsonObject }.getOrNull() ?: return null

    val version = obj.int("v") ?: return null
    if (version != VERSION) return null

    return runCatching {
      when (obj.str("type")) {
        "hello" -> decodeHello(obj)
        "state" -> decodeState(obj)
        "ack" -> decodeAck(obj)
        "command" -> decodeCommand(obj)
        "ping" -> PingFrame(t = obj.long("t") ?: 0L)
        "pong" -> PongFrame(t = obj.long("t") ?: 0L)
        else -> null
      }
    }.getOrNull()
  }

  private fun decodeHello(obj: JsonObject): HelloFrame? {
    val role = Role.fromWire(obj.str("role") ?: return null) ?: return null
    val proto = (obj["proto"] as? kotlinx.serialization.json.JsonArray)
      ?.mapNotNull { it.jsonPrimitive.intOrNull }
      ?: return null
    return HelloFrame(role = role, proto = proto, sentAtMillis = obj.long("sentAt") ?: 0L)
  }

  private fun decodeWorkout(obj: JsonObject): WorkoutSnapshot? {
    val id = obj.str("id") ?: return null
    val name = obj.str("name") ?: return null
    val exerciseName = obj.str("exerciseName") ?: return null
    return WorkoutSnapshot(
      id = id,
      name = name,
      exerciseIndex = obj.int("exerciseIndex") ?: return null,
      exerciseCount = obj.int("exerciseCount") ?: return null,
      exerciseName = exerciseName,
      setIndex = obj.int("setIndex") ?: return null,
      setCount = obj.int("setCount") ?: return null,
      weightKg = obj.double("weightKg") ?: return null,
      reps = obj.int("reps") ?: return null,
      previousWeightKg = obj.double("previousWeightKg"),
      previousReps = obj.int("previousReps"),
      // Task 2. Total by construction: only the exact string "lb" is pounds, and an absent, null
      // or unknown `unit` is kilograms - so an older phone build cannot change what the user sees.
      unit = WeightUnit.fromWire(obj.str("unit")),
      // Plan section 2.1 rule 5: a missing or unparseable `startedAt` decodes as null and NEVER as 0.
      // Zero and negative are treated as "unknown" too - both would render an elapsed time that is
      // not the workout's, and the honest answer for "the phone did not tell us" is no timer at all.
      startedAtMillis = obj.long("startedAt")?.takeIf { it > 0L },
      entries = decodeEntries(obj["entries"]),
    )
  }

  /**
   * `workout.entries[]`, or null when the phone sent none usable (M7.3, plan section 2.1).
   *
   * Null covers "the field is absent" (an older phone build, a recorded TCP fixture) **and** "the
   * field is there but not wholly well-formed". A partial list would break the `entries[i].index ==
   * i` guarantee that carrying the index explicitly exists to provide, and both sides already have a
   * defined answer for "no list": the watch renders a **one-row** list from the scalar fields
   * (`WorkoutState.listRows`, plan section 2.1 rule 6). Never throws, and never returns a list that
   * is only partly trusted.
   */
  private fun decodeEntries(element: JsonElement?): List<WorkoutEntrySnapshot>? {
    val array = element as? JsonArray ?: return null
    val rows = ArrayList<WorkoutEntrySnapshot>(array.size)
    array.forEachIndexed { position, entry ->
      val obj = entry as? JsonObject ?: return null
      val index = obj.int("index") ?: return null
      // The index is carried explicitly **and** asserted against the array position (rule 2), so a
      // consumer that filters or sorts cannot silently mis-map. A frame that disagrees is not
      // partially trusted.
      if (index != position) return null
      val name = obj.str("name")?.takeIf { it.isNotBlank() } ?: return null
      val setsDone = obj.int("setsDone") ?: return null
      val setsTotal = obj.int("setsTotal") ?: return null
      // `0 <= setsDone <= setsTotal`, and `setsTotal == 0` is legal (a zero-set exercise).
      if (setsTotal < 0 || setsDone < 0 || setsDone > setsTotal) return null
      rows.add(WorkoutEntrySnapshot(index = index, name = name, setsDone = setsDone, setsTotal = setsTotal))
    }
    return rows
  }

  private fun decodeRest(obj: JsonObject): RestSnapshot? {
    val running = obj.boolean("running") ?: return null
    val total = obj.int("total") ?: return null
    return RestSnapshot(
      running = running,
      endsAtMillis = obj.long("endsAt"),
      totalSeconds = total,
      paused = obj.boolean("paused") ?: false,
      leftSeconds = obj.int("left"),
      restWhenIdleSeconds = obj.int("restWhenIdle") ?: 0,
    )
  }

  private fun decodeState(obj: JsonObject): StateFrame? {
    val rev = obj.long("rev") ?: return null
    val sentAt = obj.long("sentAt") ?: return null
    val restObj = obj["rest"] as? JsonObject ?: return null
    val work: JsonElement = obj["workout"] ?: JsonNull
    val workout =
      when (work) {
        is JsonNull -> null
        is JsonObject -> decodeWorkout(work) ?: return null
        else -> return null
      }
    val rest = decodeRest(restObj) ?: return null
    return StateFrame(rev = rev, sentAtMillis = sentAt, workout = workout, rest = rest)
  }

  private fun decodeAck(obj: JsonObject): AckFrame? {
    val id = obj.str("id") ?: return null
    val result = AckResult.fromWire(obj.str("result") ?: return null) ?: return null
    return AckFrame(
      id = id,
      result = result,
      reason = obj.str("reason"),
      rev = obj.long("rev") ?: 0L,
      sentAtMillis = obj.long("sentAt") ?: 0L,
    )
  }

  private fun decodeCommand(obj: JsonObject): CommandFrame? {
    val id = obj.str("id") ?: return null
    val name = CommandName.fromWire(obj.str("name") ?: return null) ?: return null
    val args = (obj["args"] as? JsonObject)?.toMap() ?: emptyMap()
    return CommandFrame(
      id = id,
      name = name,
      baseRev = obj.long("baseRev") ?: 0L,
      issuedAtMillis = obj.long("issuedAt") ?: 0L,
      args = args,
    )
  }

  private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

  private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

  private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

  private fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

  private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull
}

/** A decoded frame. Every frame carries [WireProtocol.VERSION]; a mismatch is dropped in [WireProtocol.decode]. */
sealed interface LinkFrame

/** Which end of the link a [HelloFrame] came from. */
enum class Role(val wire: String) {
  WATCH("watch"),
  PHONE("phone");

  companion object {
    fun fromWire(value: String): Role? = entries.firstOrNull { it.wire == value }
  }
}

/** Version negotiation. The watch sends first; the phone's reply settles the version. */
data class HelloFrame(val role: Role, val proto: List<Int>, val sentAtMillis: Long) : LinkFrame

/** Phone → watch: the projection the screens render, plus the rest clock's absolute deadline. */
data class StateFrame(
  val rev: Long,
  val sentAtMillis: Long,
  /** Null means "no active workout" — not an error. */
  val workout: WorkoutSnapshot?,
  val rest: RestSnapshot,
) : LinkFrame

/** Phone → watch: the outcome of one command, keyed by its id. */
data class AckFrame(
  val id: String,
  val result: AckResult,
  val reason: String?,
  val rev: Long,
  val sentAtMillis: Long,
) : LinkFrame

/** Watch → phone: one user action. Names and args are fixed by `docs/protocol.md` §4. */
data class CommandFrame(
  val id: String,
  val name: CommandName,
  /** The state [StateFrame.rev] the user was looking at when they acted. */
  val baseRev: Long,
  val issuedAtMillis: Long,
  val args: Map<String, JsonElement> = emptyMap(),
) : LinkFrame {
  val deltaKg: Double? get() = (args["deltaKg"] as? JsonPrimitive)?.doubleOrNull
  val delta: Int? get() = (args["delta"] as? JsonPrimitive)?.intOrNull
  val deltaSeconds: Int? get() = (args["deltaSeconds"] as? JsonPrimitive)?.intOrNull

  /** `selectExercise`'s absolute target index (M7.3), or null when the frame carries no usable one. */
  val index: Int? get() = (args["index"] as? JsonPrimitive)?.intOrNull
}

data class PingFrame(val t: Long) : LinkFrame

data class PongFrame(val t: Long) : LinkFrame

enum class AckResult(val wire: String) {
  /** Applied; a `state` with a higher rev follows. */
  APPLIED("applied"),

  /** Already applied under this id — replay is safe, nothing changed. */
  DUPLICATE("duplicate"),

  /** Not applied. See `AckFrame.reason`: `stale`, `noWorkout`, `invalid`. */
  REJECTED("rejected");

  companion object {
    fun fromWire(value: String): AckResult? = entries.firstOrNull { it.wire == value }
  }
}

/** The seven watch actions. Identical vocabulary to [ch.duartesantos.opengym.wear.data.WorkoutRepository]. */
enum class CommandName(val wire: String) {
  COMPLETE_SET("completeSet"),
  NEXT_EXERCISE("nextExercise"),
  PREVIOUS_EXERCISE("previousExercise"),
  ADJUST_WEIGHT("adjustWeight"),
  ADJUST_REPS("adjustReps"),
  ADJUST_REST("adjustRest"),
  SKIP_REST("skipRest"),

  // M7.2 (wheel) — additive; merged by hand.
  // Absolute setters, sent once per drag on release (plan §2.3). Same target as the two ADJUST_*
  // above: the open set of the current exercise.
  SET_WEIGHT("setWeight"),
  SET_REPS("setReps"),

  /**
   * M7.3, plan section 2.2 - the workout page's tap. Absolute and idempotent like the two chevrons:
   * it carries the index the user chose rather than a step, so it is **not** `baseRev`-gated and has
   * no TTL, and a replay after a reconnect still means the same thing. The phone writes
   * `active.cur` and nothing else.
   */
  SELECT_EXERCISE("selectExercise");

  companion object {
    fun fromWire(value: String): CommandName? = entries.firstOrNull { it.wire == value }
  }
}

/**
 * The fields the watch renders. Deliberately not openGym's persisted document — the phone
 * projects, the watch does not parse 4 KB of state it has no use for.
 */
data class WorkoutSnapshot(
  val id: String,
  val name: String,
  val exerciseIndex: Int,
  val exerciseCount: Int,
  val exerciseName: String,
  val setIndex: Int,
  val setCount: Int,
  val weightKg: Double,
  val reps: Int,
  val previousWeightKg: Double?,
  val previousReps: Int?,
  /**
   * The phone profile's unit (task 2). **Display only** - [weightKg] and [previousWeightKg] are
   * kilograms whatever this is. Defaults to kg: a frame with no `unit` field is a kg frame.
   */
  val unit: WeightUnit = WeightUnit.Kilograms,
  /**
   * `active.start`, epoch millis, or null when the phone did not say (M7.3, plan section 2.1).
   *
   * Null is load-bearing: epoch 0 would render a 20 491-day elapsed timer, so an absent or
   * unparseable `startedAt` must land here as null, and the workout page hides the timer for it.
   */
  val startedAtMillis: Long? = null,
  /**
   * Every exercise in the session, in order (M7.3, plan section 2.1), or null when the phone sent
   * none usable - an older phone build, or a recorded TCP fixture. `WorkoutState.listRows` turns
   * null into a one-row list built from the scalar fields above.
   */
  val entries: List<WorkoutEntrySnapshot>? = null,
)

/**
 * One row of `workout.entries[]` (M7.3, plan section 2.1): an exercise and its progress.
 *
 * [index] is carried explicitly and is asserted equal to the entry's position on decode, so a
 * consumer that filters or sorts cannot silently mis-map. Progress is `done/total` sets only.
 */
data class WorkoutEntrySnapshot(
  val index: Int,
  /** Never empty - the phone resolves it with the same resolver as `exerciseName`. */
  val name: String,
  /** `count { done }`. `0 <= setsDone <= setsTotal`. */
  val setsDone: Int,
  /** `sets.size`. Zero is legal. */
  val setsTotal: Int,
)

/**
 * Rest state, mirroring the phone's `gym_rest` localStorage key: an **absolute** deadline, not a
 * countdown. That is what makes a late frame produce the right answer instead of a late answer.
 */
data class RestSnapshot(
  val running: Boolean,
  /** Epoch ms. Required while running and unpaused; null while paused. */
  val endsAtMillis: Long?,
  val totalSeconds: Int,
  val paused: Boolean = false,
  /** Whole seconds remaining, frozen. Only meaningful while [paused]. */
  val leftSeconds: Int? = null,
  /** The rest the current exercise will start, used for the ring while no rest is running. */
  val restWhenIdleSeconds: Int = 0,
) {
  /** Remaining whole seconds at [nowMillis], always derived from the deadline and never ticked down. */
  fun remainingSeconds(nowMillis: Long): Int {
    if (!running) return 0
    if (paused) return (leftSeconds ?: 0).coerceAtLeast(0)
    val endsAt = endsAtMillis ?: return 0
    val millisLeft = endsAt - nowMillis
    return if (millisLeft <= 0L) 0 else ((millisLeft + 999L) / 1_000L).toInt()
  }

  /**
   * The ring's denominator: the rest that was actually started (which a `+15` grows), never less
   * than what is left. When idle it is the rest the current exercise will start.
   */
  fun totalForDisplay(nowMillis: Long): Int {
    if (!running) return restWhenIdleSeconds
    return maxOf(totalSeconds, remainingSeconds(nowMillis))
  }

  companion object {
    /** No rest running, no rest configured. */
    val Idle = RestSnapshot(running = false, endsAtMillis = null, totalSeconds = 0)
  }
}

/**
 * Factories for the seven commands. Keeping the constructor call sites here means the arg key
 * names (`deltaKg`, `delta`, `deltaSeconds`) exist in exactly one place.
 */
object WatchCommands {
  fun completeSet(id: String, baseRev: Long, issuedAtMillis: Long) =
    CommandFrame(id, CommandName.COMPLETE_SET, baseRev, issuedAtMillis)

  fun nextExercise(id: String, baseRev: Long, issuedAtMillis: Long) =
    CommandFrame(id, CommandName.NEXT_EXERCISE, baseRev, issuedAtMillis)

  fun previousExercise(id: String, baseRev: Long, issuedAtMillis: Long) =
    CommandFrame(id, CommandName.PREVIOUS_EXERCISE, baseRev, issuedAtMillis)

  fun adjustWeight(id: String, baseRev: Long, issuedAtMillis: Long, deltaKg: Double) =
    CommandFrame(
      id,
      CommandName.ADJUST_WEIGHT,
      baseRev,
      issuedAtMillis,
      mapOf("deltaKg" to JsonPrimitive(deltaKg)),
    )

  fun adjustReps(id: String, baseRev: Long, issuedAtMillis: Long, delta: Int) =
    CommandFrame(
      id,
      CommandName.ADJUST_REPS,
      baseRev,
      issuedAtMillis,
      mapOf("delta" to JsonPrimitive(delta)),
    )

  // M7.2 (wheel) — additive; merged by hand.
  // `kg` is a finite Double the phone snaps to 0.5 and clamps to 0.0..500.0; `reps` an Int clamped
  // to 1..99. Absolute, so `baseRev` is carried as information rather than as a gate.
  fun setWeight(id: String, baseRev: Long, issuedAtMillis: Long, kg: Double) =
    CommandFrame(
      id,
      CommandName.SET_WEIGHT,
      baseRev,
      issuedAtMillis,
      mapOf("kg" to JsonPrimitive(kg)),
    )

  fun setReps(id: String, baseRev: Long, issuedAtMillis: Long, reps: Int) =
    CommandFrame(
      id,
      CommandName.SET_REPS,
      baseRev,
      issuedAtMillis,
      mapOf("reps" to JsonPrimitive(reps)),
    )

  fun adjustRest(id: String, baseRev: Long, issuedAtMillis: Long, deltaSeconds: Int) =
    CommandFrame(
      id,
      CommandName.ADJUST_REST,
      baseRev,
      issuedAtMillis,
      mapOf("deltaSeconds" to JsonPrimitive(deltaSeconds)),
    )

  fun skipRest(id: String, baseRev: Long, issuedAtMillis: Long) =
    CommandFrame(id, CommandName.SKIP_REST, baseRev, issuedAtMillis)

  /**
   * M7.3, plan section 2.2. Absolute: it carries the index the user tapped, not a step, so a replay
   * after a reconnect still means the same thing. The phone validates the range and answers
   * `rejected: invalid` for anything out of it.
   */
  fun selectExercise(id: String, baseRev: Long, issuedAtMillis: Long, index: Int) =
    CommandFrame(
      id,
      CommandName.SELECT_EXERCISE,
      baseRev,
      issuedAtMillis,
      mapOf("index" to JsonPrimitive(index)),
    )
}
