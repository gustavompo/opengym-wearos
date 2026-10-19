package ch.duartesantos.opengym.wear.ui.rest

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 *  — the rest clock's audible cues.
 *
 * Three pieces, split so the interesting part is testable without a device:
 *
 *   restTonesFor(...)      pure: which cues a countdown *step* produces. No clock, no Android.
 *   RestSoundController    pure logic + a [RestToneSink]: tracks the previous second, plays ticks,
 *                          schedules the three beeps at zero on the sink's clock.
 *   AndroidRestToneSink    the device half: ToneGenerator, and the silent/volume gate.
 *
 * The split exists because the screen cannot hear the beeps itself. `WorkoutState.isResting` goes
 * false at exactly the second the rest ends, and `WearApp` derives the destination from that flag,
 * so `RestTimer` is disposed in the same recomposition as the 1 -> 0 transition. Anything that
 * waited for a `remaining == 0` state change would never run. The beeps are therefore scheduled
 * while `remaining == 1` on the sink's own scope, which outlives the composable.
 */

/** A single audible cue. [Tick] marks one of the final seconds; [Beep] is the end-of-rest alarm. */
enum class RestTone {
  Tick,
  Beep,
}

/** The tick starts when this many seconds are left. Five ticks, then the beeps. */
const val FINAL_TICK_SECONDS: Int = 5

/** How many beeps mark the end of the rest. */
const val BEEPS_AT_ZERO: Int = 3

/**
 * The cues produced when the countdown moves from [previousSeconds] to [nowSeconds].
 *
 * Pure and total, and deliberately direction-aware: any non-decreasing step (a rest starting,
 * `+15`, or a re-observation) produces nothing, so an adjustment can never replay ticks the user
 * has already heard.
 *
 * At most **one** tick per step, not one per second crossed. A tick is a metronome beat, so a step
 * that skips several seconds at once - a coalesced ticker, or a screen that was not recomposing -
 * must not fire a burst of five of them. In the normal case the state arrives once per second and
 * this is a tick per second: 6 -> 5 -> 4 -> 3 -> 2 -> 1 ticks five times, exactly as asks.
 */
fun restTonesFor(
  previousSeconds: Int,
  nowSeconds: Int,
  tickFromSeconds: Int = FINAL_TICK_SECONDS,
  beepsAtZero: Int = BEEPS_AT_ZERO,
): List<RestTone> {
  val from = previousSeconds.coerceAtLeast(0)
  val to = nowSeconds.coerceAtLeast(0)
  if (to >= from) return emptyList()

  val tones = ArrayList<RestTone>()
  if (to in 1..tickFromSeconds) tones += RestTone.Tick
  if (to == 0) repeat(beepsAtZero) { tones += RestTone.Beep }
  return tones
}

/**
 * Where cues go. The device implementation makes sound; a test double records them.
 *
 * [scheduleBeeps] is separate from [play] on purpose: it has to survive the disposal of the screen
 * that scheduled it (see the file comment), so the sink owns the timer rather than the composable.
 */
interface RestToneSink {
  /** Plays a cue now, if the watch's sound settings allow it. */
  fun play(tone: RestTone)

  /** Cancels any scheduled beeps and schedules three of them [delayMillis] from now. */
  fun scheduleBeeps(delayMillis: Long)

  /** Cancels a scheduled beep sequence. Called by `Skip`, `-15` and `+15`. */
  fun cancelBeeps()
}

/**
 * Turns a stream of countdown values into cues.
 *
 * Lifecycle: one instance per rest, remembered by [RestTimer]. If the user leaves the rest screen
 * by the *natural* route the instance is dropped, but any beeps it already scheduled are running on
 * the sink's scope and still fire. If the user leaves by `Skip` / `-15` / `+15`, the button's
 * handler calls [cancelScheduledBeeps] first, so a deliberate end is silent.
 */
class RestSoundController(
  private val sink: RestToneSink,
  private val tickFromSeconds: Int = FINAL_TICK_SECONDS,
  private val beepsAtZero: Int = BEEPS_AT_ZERO,
) {
  private var previousSeconds: Int? = null

  /** True while a beep sequence is outstanding, so cancelling it is a no-op when it is not. */
  private var beepsScheduled: Boolean = false

  /**
   * Feeds one observed value of `WorkoutState.restSecondsRemaining`.
   *
   * The first call only records the baseline: the screen can be entered mid-rest (recomposition,
   * or a rest long enough to survive a screen dim), and a tick for a second the user did not spend
   * on this screen would be a lie.
   */
  fun onRemainingChanged(seconds: Int) {
    val now = seconds.coerceAtLeast(0)
    val previous = previousSeconds
    previousSeconds = now

    if (previous == null) {
      if (now in 1..tickFromSeconds) scheduleBeeps(now)
      return
    }
    if (now >= previous) {
      // The rest restarted, or the user pushed it out. Nothing to play; drop any pending beeps.
      if (now > previous) cancelBeeps()
      return
    }

    for (tone in restTonesFor(previous, now, tickFromSeconds, beepsAtZero = 0)) sink.play(tone)
    when {
      // Only reachable if the state is still being observed at zero. Kept because a one-shot
      // `remaining` jump straight past the last second (a coalesced ticker) lands here, and the
      // alternative is silently losing the alarm.
      now == 0 -> {
        cancelBeeps()
        repeat(beepsAtZero) { sink.play(RestTone.Beep) }
      }
      now <= tickFromSeconds -> scheduleBeeps(now)
      else -> cancelBeeps()
    }
  }

  /** The user ended the rest themselves. Nothing scheduled should fire. */
  fun cancelScheduledBeeps() {
    cancelBeeps()
  }

  /** Arms the end-of-rest beeps so they fire [seconds] from now, on the sink's own clock. */
  private fun scheduleBeeps(seconds: Int) {
    beepsScheduled = true
    sink.scheduleBeeps(seconds * 1_000L)
  }

  private fun cancelBeeps() {
    if (!beepsScheduled) return
    beepsScheduled = false
    sink.cancelBeeps()
  }
}

/**
 * True when the watch's own settings permit a sound. Pure, so the policy is unit-testable without
 * a device: every branch below has a test.
 *
 * Silent and vibrate ringer modes both mean "do not make a noise" — the whole point of 's
 * "respect the watch's silent mode". A muted or zero-volume stream is the same answer asked a
 * different way, because Wear OS exposes volume separately from ringer mode.
 */
internal fun soundAllowed(ringerMode: Int, streamVolume: Int, streamMuted: Boolean): Boolean {
  if (ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
  if (streamMuted) return false
  return streamVolume > 0
}

/**
 * The device half of: [ToneGenerator] on the notification stream.
 *
 * Stream choice: `STREAM_NOTIFICATION`, deliberately not `STREAM_ALARM`. An alarm-stream timer
 * would blast through silent mode by design, which contradicts the brief; the notification stream
 * is what the system's silent/vibrate switch already governs, and its volume is the one the user
 * expects a short cue to follow. `soundAllowed` re-checks all three signals anyway, so the gate
 * does not depend on the stream's own policy.
 *
 * [scope] is owned by this object rather than taken from the caller, because the beep sequence has
 * to outlive the composable that scheduled it. It is never cancelled; the object is one per rest
 * and holds at most one short job.
 */
class AndroidRestToneSink(
  context: Context,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : RestToneSink {

  private val audio: AudioManager? =
    context.applicationContext.getSystemService(AudioManager::class.java)

  /** Built on first use: constructing a [ToneGenerator] touches the audio HAL, which is wasted
   * work on a rest timer that is never going to beep. */
  private val toneGenerator: ToneGenerator? by lazy {
    runCatching { ToneGenerator(STREAM, ToneGenerator.MAX_VOLUME) }.getOrNull()
  }

  private var pendingBeeps: Job? = null

  override fun play(tone: RestTone) {
    val manager = audio
    if (manager == null) {
      Log.i(LOG_TAG, "suppressed $tone: no AudioManager")
      return
    }
    val volume = runCatching { manager.getStreamVolume(STREAM) }.getOrDefault(0)
    val muted = runCatching { manager.isStreamMute(STREAM) }.getOrDefault(true)
    if (!soundAllowed(manager.ringerMode, volume, muted)) {
      Log.i(LOG_TAG, "suppressed $tone: ringerMode=${manager.ringerMode} volume=$volume muted=$muted")
      return
    }
    Log.i(LOG_TAG, "play $tone ringerMode=${manager.ringerMode} volume=$volume")
    val generator = toneGenerator ?: return
    runCatching { generator.startTone(toneId(tone), durationMillis(tone)) }
  }

  override fun scheduleBeeps(delayMillis: Long) {
    pendingBeeps?.cancel()
    pendingBeeps =
      scope.launch {
        delay(delayMillis.coerceAtLeast(0L))
        for (index in 0 until BEEPS_AT_ZERO) {
          play(RestTone.Beep)
          if (index < BEEPS_AT_ZERO - 1) delay(BEEP_SPACING_MILLIS)
        }
      }
  }

  override fun cancelBeeps() {
    pendingBeeps?.cancel()
    pendingBeeps = null
  }

  private companion object {
    /** Logcat tag. The only host-observable proof of the sound path on an emulator, whose audio
     * output cannot be captured from the ADB host; see */
    const val LOG_TAG = "RestSound"

    /** See the class comment for why this is the notification stream and not the alarm stream. */
    const val STREAM = AudioManager.STREAM_NOTIFICATION

    /** A short click for a per-second tick; the default 200 ms beep would smear five of them. */
    const val TICK_MILLIS = 55

    /** Long enough to read as an alarm after the ticks. */
    const val BEEP_MILLIS = 220

    /** Gap between the three end beeps. Each `startTone` pre-empts the previous one. */
    const val BEEP_SPACING_MILLIS = 260L

    fun toneId(tone: RestTone): Int =
      when (tone) {
        RestTone.Tick -> ToneGenerator.TONE_PROP_BEEP
        RestTone.Beep -> ToneGenerator.TONE_PROP_BEEP
      }

    fun durationMillis(tone: RestTone): Int =
      when (tone) {
        RestTone.Tick -> TICK_MILLIS
        RestTone.Beep -> BEEP_MILLIS
      }
  }
}
