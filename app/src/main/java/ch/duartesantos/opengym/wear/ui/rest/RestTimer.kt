package ch.duartesantos.opengym.wear.ui.rest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.Typography
import ch.duartesantos.opengym.wear.data.WorkoutState
import ch.duartesantos.opengym.wear.theme.OpenGymBlack
import ch.duartesantos.opengym.wear.theme.OpenGymBlue
import ch.duartesantos.opengym.wear.theme.OpenGymMuted
import ch.duartesantos.opengym.wear.theme.OpenGymOutline
import ch.duartesantos.opengym.wear.theme.OpenGymSurfaceHigh
import ch.duartesantos.opengym.wear.theme.OpenGymWhite
import ch.duartesantos.opengym.wear.ui.exercise.ChordWrappedText
import ch.duartesantos.opengym.wear.ui.exercise.formatWeight
import ch.duartesantos.opengym.wear.ui.exercise.rememberHapticTick
import ch.duartesantos.opengym.wear.ui.safeWidth
import java.util.Locale

/*
 * M2.5 - the rest timer.
 *
 * Geometry comes from `references/rest-period-timer.png`, measured with
 * `android/build/evidence/measure-rest.py` (see docs/notes/05-rest-timer-evidence.md). That crop is
 * 321x343 px with a watch disc of diameter 305 px, so every constant below is a fraction of the
 * *display width*: `149f / 305f` means "149 of the reference display's 305 px". The same fraction is
 * then multiplied by our width in dp, so the reference's px -> dp conversion is applied exactly
 * once, and the layout stays proportional on a 192 dp watch as well as on the 227 dp emulator.
 *
 * This is the specific mistake `ExercisePage.kt` makes and this file does not: it folds 227/297
 * (px -> dp) into each font fraction *and* multiplies by a width already in dp, so its text renders
 * at 0.80x. Here the cap-height constants are pure ratios - cap px / display px - divided by
 * Roboto's cap-to-em ratio, which is a number with no unit at all.
 */

/** Roboto cap height per em. A digit's ink box is exactly the cap height. */
private const val CAP_TO_EM = 0.711f

/** The reference display's diameter in px. Every fraction below is measured against this. */
private const val REF_DISPLAY_PX = 305f

/**
 * Ring outer radius: measured 149.0 px = 0.4885 of the display width. Notes 01 recommends
 * 0.47-0.49 x minDimension; this sits just inside the reference, which draws flush to the bezel.
 */
private const val F_RING_RADIUS = 149f / REF_DISPLAY_PX

/**
 * Stroke as a fraction of the ring's *radius*: `docs/notes/01-rest-ring-semantics.md` says 0.076
 * (this crop measures 0.0738, notes 01 measure 0.0765). "roughly 1/6 of the radius" - the figure in
 * the task brief - is 0.167 and would be 2.3x the reference; the reference does not contain it.
 */
private const val F_RING_STROKE_OF_RADIUS = 0.076f

/**
 * Below this fill fraction the arc is a couple of degrees, and a round cap degenerates into a dot
 * that reads as a rendering bug. Notes 01 says draw nothing instead.
 */
private const val MIN_DRAWABLE_FRACTION = 0.006f

/*
 * Measured ink heights (cap heights, so font size = cap px / 0.711):
 *   countdown 34 px, `Next:` and `Set` lines 14 px, pill labels 14.5 px, `Skip` 17 px.
 */
private const val F_FONT_COUNTDOWN = (34f / REF_DISPLAY_PX) / CAP_TO_EM
private const val F_FONT_DETAIL = (14f / REF_DISPLAY_PX) / CAP_TO_EM
private const val F_FONT_PILL = (14.5f / REF_DISPLAY_PX) / CAP_TO_EM
private const val F_FONT_SKIP = (17f / REF_DISPLAY_PX) / CAP_TO_EM

/*
 * Measured ink-band centres, as offsets from the ring centre: countdown -62.0 px, `Next:` -13.0,
 * `Set` +10.0, pills +55.0, `Skip` +111.5. The reference's stats row (two icons and two numbers at
 * -112 px) is not rendered: WorkoutState carries no heart rate or calorie field and inventing one is
 * outside this task's file scope. That is why this block sits below the disc centre.
 *
 * U2a (Gus: "the 'next: exercise-name' is also hitting the sides and being cut... move the number
 * counter a bit up... make this label a 2-line label"): the countdown moves up -62 -> -75 and the
 * `Next:` block moves up -13 -> -32 so its second line clears the `Set` line below, then takes two
 * lines (see [REST_NEXT_MAX_LINES]). Both values stay negative (above the ring centre), inside the
 * ring.
 */
private const val F_DY_COUNTDOWN = -75f / REF_DISPLAY_PX
private const val F_DY_NEXT = -32f / REF_DISPLAY_PX
private const val F_DY_SET = 10f / REF_DISPLAY_PX
private const val F_DY_PILLS = 55f / REF_DISPLAY_PX
private const val F_DY_SKIP = 111.5f / REF_DISPLAY_PX

/** U2a: `Next: <name>` wraps to at most two lines (the user asked for the two-line label). */
private const val REST_NEXT_MAX_LINES = 2

/** Pills: measured 77 x 48 px stadiums, 24 px apart, centres 51 px either side of the ring centre. */
private const val F_PILL_W = 84f / REF_DISPLAY_PX
private const val F_PILL_H = 52f / REF_DISPLAY_PX
private const val F_PILL_GAP = 24f / REF_DISPLAY_PX

/** The pills' border, same visual weight as the exercise page's button border. */
private const val F_PILL_BORDER = 2f / REF_DISPLAY_PX

/** How far the rest clock can be pushed: the reference's pills are labelled `-15` / `+15`. */
const val REST_ADJUST_SECONDS: Int = 15

/**
 * The arc. Measured core pixel `rgb(29,130,234)` = `#1D82EA`, which is now the theme's
 * [OpenGymBlue]: M2.2 corrected that token away from the earlier transcription's `#2196F3`, so the
 * screen no longer needs its own copy. Notes 01's arguably-closer standard value `#1E88E5` is not
 * what the reference file contains.
 *
 * There is deliberately **no track** under it - see notes 01.
 */
private val RingBlue = OpenGymBlue

/**
 * The pills' fill, before: measured `rgb(28,28,30)` = `#1C1C1E`, the same dark neutral the spinner
 * cards and the chevrons used — and the reason the user could not see where a button ended. U2a
 * moves the pills to the palette's button rung, [OpenGymSurfaceHigh], with an [OpenGymOutline]
 * edge (same treatment as the exercise page's chevrons).
 */
private val PillFill = OpenGymSurfaceHigh

/** Tags. */
const val REST_TIMER_TAG: String = "rest_timer"
const val REST_TIMER_COUNTDOWN_TAG: String = "rest_timer.countdown"
const val REST_TIMER_NEXT_TAG: String = "rest_timer.next"
const val REST_TIMER_SET_TAG: String = "rest_timer.set"
const val REST_TIMER_MINUS_TAG: String = "rest_timer.minus"
const val REST_TIMER_PLUS_TAG: String = "rest_timer.plus"
const val REST_TIMER_SKIP_TAG: String = "rest_timer.skip"

/**
 * The rest timer: a depleting progress ring around the whole face, the countdown inside it, the
 * upcoming set under that, and three controls at the bottom.
 *
 * Ring semantics are `docs/notes/01-rest-ring-semantics.md`, decided by pixel measurement and not
 * revisited here: one colour, **no track**; the blue arc is the **remaining** time and therefore
 * **empties**; rounded caps. U3: the arc is eaten from **one** end only - its right-hand end
 * retreats counterclockwise like a reverse clock while the left-hand end stays fixed at clock
 * 7:30 - see [restArcAngles]. (It used to shrink from both ends, meeting at 12 o'clock.)
 *
 * M2.8's cues live in [RestSoundController], driven from the state's remaining seconds, so the tick
 * and beep schedule follows the repository's clock - the one with the injected `TimeSource` and
 * `CoroutineScope` - rather than a second clock inside the UI.
 *
 * [toneSink] is appended after [modifier] on purpose: the four-parameter signature the integration
 * step calls is unchanged, so positional and named calls written against it still bind to it, and
 * instrumented tests can substitute a recording sink.
 */
@Composable
fun RestTimer(
  state: WorkoutState,
  onAdjustRest: (Int) -> Unit,
  onSkipRest: () -> Unit,
  modifier: Modifier = Modifier,
  toneSink: RestToneSink? = null,
) {
  val context = LocalContext.current
  val deviceSink = remember(context) { AndroidRestToneSink(context) }
  val sink = toneSink ?: deviceSink
  val sound = remember(sink) { RestSoundController(sink) }

  // U2a: one CLOCK_TICK per pill / Skip press, gated on the system haptics setting.
  val tick = rememberHapticTick()

  // Keyed on the countdown, so every second the repository publishes reaches the scheduler.
  LaunchedEffect(sound, state.restSecondsRemaining) {
    sound.onRemainingChanged(state.restSecondsRemaining)
  }

  BoxWithConstraints(
    modifier = modifier.fillMaxSize().background(OpenGymBlack).testTag(REST_TIMER_TAG),
  ) {
    // Hoisted out of the `remember` because `MaterialTheme.typography` is a composable getter and
    // `remember`'s calculation lambda is not composable.
    val typography = MaterialTheme.typography
    val metrics = remember(maxWidth, typography) { RestMetrics(maxWidth, typography) }
    val remaining = state.restSecondsRemaining.coerceAtLeast(0)

    Canvas(Modifier.matchParentSize()) { drawRestArc(remaining, state.restSecondsTotal) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(
        text = formatCountdown(remaining),
        style = metrics.countdownStyle,
        color = OpenGymWhite,
        maxLines = 1,
        modifier = Modifier.offsetY(metrics.countdownOffset).testTag(REST_TIMER_COUNTDOWN_TAG),
      )

      // U2a: the two-line `Next:` label, per-line chord-capped — the fix for "the next line hits
      // the sides". Before, one 380 px line ran to both bezel edges (measured: clearance +0.0 px,
      // semantic bounds [0,191][454,225], docs/notes/31 §2b.4). Each line now gets its own chord.
      ChordWrappedText(
        text = "Next: ${state.exerciseName}",
        style = metrics.detailStyle,
        color = OpenGymMuted,
        lineSafeWidth = { line -> metrics.nextLineCap(line) },
        maxLines = REST_NEXT_MAX_LINES,
        modifier = Modifier.offsetY(metrics.nextOffset).testTag(REST_TIMER_NEXT_TAG),
      )

      Text(
        text = formatUpcomingSet(state),
        style = metrics.detailStyle,
        color = OpenGymMuted,
        maxLines = 1,
        modifier = Modifier.offsetY(metrics.setOffset).testTag(REST_TIMER_SET_TAG),
      )

      Row(
        modifier = Modifier.offsetY(metrics.pillsOffset),
        horizontalArrangement = Arrangement.spacedBy(metrics.pillGap),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Pill(
          label = "-$REST_ADJUST_SECONDS",
          description = "Decrease rest by $REST_ADJUST_SECONDS seconds",
          tag = REST_TIMER_MINUS_TAG,
          metrics = metrics,
          onTick = tick,
          // A deliberate end to the rest: nothing scheduled for the natural end may fire.
          onClick = {
            sound.cancelScheduledBeeps()
            onAdjustRest(-REST_ADJUST_SECONDS)
          },
        )
        Pill(
          label = "+$REST_ADJUST_SECONDS",
          description = "Increase rest by $REST_ADJUST_SECONDS seconds",
          tag = REST_TIMER_PLUS_TAG,
          metrics = metrics,
          onTick = tick,
          onClick = {
            sound.cancelScheduledBeeps()
            onAdjustRest(REST_ADJUST_SECONDS)
          },
        )
      }

      // Bare white text in the reference, with no pill behind it. The 48 dp touch target is
      // padding, not paint, and it is symmetric so the ink centre stays at the measured offset.
      Text(
        text = "Skip",
        style = metrics.skipStyle,
        color = OpenGymWhite,
        maxLines = 1,
        modifier =
          Modifier.offsetY(metrics.skipOffset)
            .clickable {
              tick()
              sound.cancelScheduledBeeps()
              onSkipRest()
            }
            .semantics {
              role = Role.Button
              contentDescription = "Skip rest"
            }
            .padding(metrics.skipTouchPadding)
            .testTag(REST_TIMER_SKIP_TAG),
      )
    }
  }
}

/** `01:29`. Two-digit minutes, so a ten minute rest (`MAX_REST_SECONDS`) still reads correctly. */
internal fun formatCountdown(secondsRemaining: Int): String {
  val seconds = secondsRemaining.coerceAtLeast(0)
  return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
}

/** `Set 2/4: 50.0 kg x 10` - the set the user is about to do, which is the one the rest is for. */
internal fun formatUpcomingSet(state: WorkoutState): String =
  "Set ${state.currentSetIndex}/${state.totalSets}: " +
    "${formatWeight(state.weightKg)} kg x ${state.reps}"

/**
 * The rest arc's angles in Compose's convention: 0 degrees at 3 o'clock, growing clockwise.
 *
 * [startAngle] is where the arc begins and never changes; [sweepAngle] is how far it runs clockwise
 * from there, so only the far end moves as the rest depletes.
 */
internal data class RestArcAngles(val startAngle: Float, val sweepAngle: Float)

/**
 * Compose angle of the arc's **fixed** end: 135 degrees = clock 7:30.
 *
 * Notes 01 measured the reference's left cap centre at clock 224.98 degrees and its gap centre at
 * 178.0 (5.93 o'clock) at 89 s remaining. A one-sided ring whose *right-hand* cap retreats
 * counterclockwise is pinned at `178.0 - 94.0/2 = 131.0` for the moving cap and
 * `178.0 + 94.0/2 = 225.0` for the fixed one. 225.0 clock = 135 Compose, which reproduces the
 * reference's left cap to 0.02 degrees; the previous symmetric `180(1-f) + 90` formula renders the
 * same moment 2 degrees off (caps at 227.0/133.0, gap centre 180.0).
 */
internal const val REST_ARC_START_ANGLE: Float = 135f

/**
 * The arc angles for a remaining fraction [fill] (1 at the start of the rest, 0 at the beep).
 *
 * Exactly one endpoint moves: the right-hand one, retreating counterclockwise - a reverse clock -
 * as [fill] falls. At [fill] `f` the moving cap sits at Compose `135 + 360f`, i.e. clock
 * `225 + 360f`, so the sweep stays linear in [fill] and therefore in elapsed rest time. The absolute
 * independence of [startAngle] from [fill] is what makes the ring one-sided, and
 * `RestTimerArcTest` fails if the symmetric `180(1-f) + 90` term comes back.
 */
internal fun restArcAngles(fill: Float): RestArcAngles {
  val f = fill.coerceIn(0f, 1f)
  return RestArcAngles(startAngle = REST_ARC_START_ANGLE, sweepAngle = 360f * f)
}

/**
 * Below this fill the arc is a couple of degrees and a round cap degenerates into a dot.
 *
 * ### Parameters
 * - [fill]: the remaining fraction, `remaining / total`.
 *
 * ### Returns
 * `true` when there is enough arc to draw; `false` means draw nothing (notes 01). Pure, so the
 * guard is testable without a device.
 */
internal fun restArcIsDrawable(fill: Float): Boolean = fill > MIN_DRAWABLE_FRACTION

/**
 * The arc: the **remaining** time, eaten from its right-hand end only.
 *
 * One endpoint is fixed at [REST_ARC_START_ANGLE] (clock 7:30, the reference's measured left cap
 * centre); the other retreats counterclockwise, so the arc is not eaten from both ends toward 12
 * o'clock. At the reference moment (89 s left of 2:00, a 266.0 degree arc) the gap is centred on
 * clock 178.0 = 5.93 o'clock, the reference's own measurement.
 *
 * [Size] and [Offset] are given explicitly because `drawArc`'s stroke straddles the path it is given,
 * and the no-argument overload would inscribe that path in the full canvas, pushing half the stroke
 * off-screen.
 */
private fun DrawScope.drawRestArc(secondsRemaining: Int, secondsTotal: Int) {
  if (secondsTotal <= 0) return
  val fill = (secondsRemaining.toFloat() / secondsTotal.toFloat()).coerceIn(0f, 1f)
  if (!restArcIsDrawable(fill)) return

  val outerRadius = size.minDimension * F_RING_RADIUS
  val stroke = outerRadius * F_RING_STROKE_OF_RADIUS
  val pathRadius = outerRadius - stroke / 2f
  val arc = restArcAngles(fill)
  drawArc(
    color = RingBlue,
    startAngle = arc.startAngle,
    sweepAngle = arc.sweepAngle,
    useCenter = false,
    topLeft = Offset(center.x - pathRadius, center.y - pathRadius),
    size = Size(pathRadius * 2f, pathRadius * 2f),
    style = Stroke(width = stroke, cap = StrokeCap.Round),
  )
}

/**
 * Every dp and sp the screen uses, derived once from the screen width.
 *
 * [unit] is that width, so the constants above read as "this many of the reference display's
 * 305 px" and the px -> dp conversion happens exactly once, inside `unit * fraction`.
 */
private class RestMetrics(unit: Dp, typography: Typography) {
  /** Font sizes are a fraction of the width in dp, which equals sp at the default font scale. */
  private val width = unit.value

  val countdownStyle = typography.numeralLarge.tight(width * F_FONT_COUNTDOWN)
  val detailStyle = typography.bodySmall.tight(width * F_FONT_DETAIL)
  val pillStyle = typography.numeralSmall.tight(width * F_FONT_PILL)
  val skipStyle = typography.bodySmall.tight(width * F_FONT_SKIP)

  val countdownOffset = unit * F_DY_COUNTDOWN
  val nextOffset = unit * F_DY_NEXT
  val setOffset = unit * F_DY_SET
  val pillsOffset = unit * F_DY_PILLS
  val skipOffset = unit * F_DY_SKIP

  val pillWidth = unit * F_PILL_W
  val pillHeight = unit * F_PILL_H
  val pillGap = unit * F_PILL_GAP
  val pillBorder = unit * F_PILL_BORDER

  /*
   * U2a: the `Next:` label's per-line chords. The block's centre is offset by [nextOffset]; each
   * line's cap is the chord at that line's own box top (`detailLineHeight` = the tight line
   * height) — the guaranteed safe bound, since glyph ink never extends above the line box. Line 2
   * sits lower, so it is capped wider.
   */
  val detailLineHeight = unit * F_FONT_DETAIL
  private val nextLine1Top = unit / 2f + nextOffset - detailLineHeight / 2f
  val nextLine1Cap = safeWidth(unit, nextLine1Top / unit)
  val nextLine2Cap = safeWidth(unit, (nextLine1Top + detailLineHeight) / unit)

  fun nextLineCap(line: Int): Dp = if (line == 0) nextLine1Cap else nextLine2Cap

  /**
   * `Skip` is bare text, so its 48 dp touch target is padding rather than paint. The pill row's box
   * ends 0.1 dp above this one, so the two targets meet without overlapping.
   */
  val skipTouchPadding = (unit * (48f / 227f) - unit * F_FONT_SKIP) / 2f
}

/**
 * Pins a type role's size while keeping its family and its tabular figures. `lineHeight` is pinned
 * to the font size because these lines are positioned by their measured ink centre, and a 1.2 em
 * line box would add slack that varies from role to role.
 */
private fun TextStyle.tight(fontSizeSp: Float): TextStyle =
  copy(fontSize = fontSizeSp.sp, lineHeight = fontSizeSp.sp)

/** Moves an element's centre to the screen centre plus a measured fraction of the screen width. */
private fun Modifier.offsetY(offset: Dp): Modifier = this.then(Modifier.offset(y = offset))

/** A `-15` / `+15` control: a brightened stadium with a white tabular label and an outline edge. */
@Composable
private fun Pill(
  label: String,
  description: String,
  tag: String,
  metrics: RestMetrics,
  onTick: () -> Unit,
  onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(percent = 50)
  Box(
    modifier =
      Modifier.size(metrics.pillWidth, metrics.pillHeight)
        .clip(shape)
        .background(PillFill)
        .border(metrics.pillBorder, OpenGymOutline, shape)
        .clickable {
          onTick()
          onClick()
        }
        .semantics {
          role = Role.Button
          contentDescription = description
        }
        .testTag(tag),
    contentAlignment = Alignment.Center,
  ) {
    Text(text = label, style = metrics.pillStyle, color = OpenGymWhite, maxLines = 1)
  }
}
