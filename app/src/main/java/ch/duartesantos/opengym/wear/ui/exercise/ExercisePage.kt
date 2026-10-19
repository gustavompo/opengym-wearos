package ch.duartesantos.opengym.wear.ui.exercise

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.Typography
import ch.duartesantos.opengym.wear.data.MAX_REPS
import ch.duartesantos.opengym.wear.data.MIN_REPS
import ch.duartesantos.opengym.wear.data.WeightUnit
import ch.duartesantos.opengym.wear.data.WorkoutState
import ch.duartesantos.opengym.wear.data.clampReps
import ch.duartesantos.opengym.wear.theme.OpenGymBlack
import ch.duartesantos.opengym.wear.theme.OpenGymBlue
import ch.duartesantos.opengym.wear.theme.OpenGymMuted
import ch.duartesantos.opengym.wear.theme.OpenGymOutline
import ch.duartesantos.opengym.wear.theme.OpenGymSurface
import ch.duartesantos.opengym.wear.theme.OpenGymSurfaceHigh
import ch.duartesantos.opengym.wear.theme.OpenGymWhite
import ch.duartesantos.opengym.wear.ui.safeWidth
import java.util.Locale
import kotlin.math.abs

/*
 * Geometry: every dimension below is a fraction of the *screen width*, taken from
 * references/exercise-page.png by pixel measurement. The image's watch display is a 297 x 297 px
 * region, so a constant of `121f / 297f` means "121 of the reference display's 297 px". Expressing
 * them as fractions rather than fixed dp keeps the layout proportional on a 192 dp watch as well as
 * on the 227 dp (454 px @ 320 dpi) emulator, where they resolve to the measured reference values.
 *
 * The reference measurements that drove these numbers (disc centre (154, 173), diameter 297 px):
 *   two spinner cards  120 x 90 px, corner radius ~18 px, centres at x = 85.5 / 222.5
 *                      = +/-0.2306 of the display diameter
 *   card + button fill #1C1C1E (now the theme's OpenGymSurface; the research doc's #2C2C2C is 2.6x
 *                      brighter than anything in the reference)
 *   selected value     27 px cap height, centred on the card's centre (dcy -0.003 D)
 *   neighbour values   ~10 px cap height, centred at dcy -0.109 D / +0.106 D
 *   chevron buttons    51 x 51 circles, centres dcx +/-0.232 D, dcy +0.327 D
 *   `✓` button         75 x 50 stadium - measured; it is NOT the chevrons' diameter
 *   chevron / check    11 x 19 px / 23 x 16 px white ink, stroke ~3.5 px
 *   gaps               11 px title->counter, 14 counter->labels, 7 card->hint, 5 hint->buttons
 */

/** The reference display's diameter in px. Every fraction below is measured against this. */
private const val REF_DISPLAY_PX = 297f

/** Roboto cap height per em. A digit's or a capital's ink box is exactly the cap height. */
private const val CAP_TO_EM = 0.711f

private const val F_CARD_W = 121f / REF_DISPLAY_PX
private const val F_CARD_H = 90f / REF_DISPLAY_PX
private const val F_CARD_RADIUS = 18f / REF_DISPLAY_PX

/*
 * The card's outline. The reference draws 2 px of `#1C1C1E` on its 297 px display, which is 3.06 px
 * at 454 px. `Modifier.border` rounds the stroke width *up* to a whole pixel
 * (`androidx.compose.foundation.Border.kt`: `ceil(width.toPx())`), so the exact ratio 2/297 =
 * 1.53 dp renders as 4 px. 1.96 reference px = 1.50 dp is the nearest value that renders as the
 * reference's 3 px, and it stays a fraction of the display width like every other constant here.
 */
private const val F_CARD_BORDER = 1.96f / REF_DISPLAY_PX

private const val F_GAP_BETWEEN_CARDS = 17f / REF_DISPLAY_PX
private const val F_GAP_LABEL_CARD = 3f / REF_DISPLAY_PX

private const val F_CHEVRON_DIAMETER = 50f / REF_DISPLAY_PX
private const val F_PRIMARY_W = 75f / REF_DISPLAY_PX
private const val F_PRIMARY_H = 50f / REF_DISPLAY_PX
private const val F_GAP_BETWEEN_BUTTONS = 6.5f / REF_DISPLAY_PX

private const val F_CHEVRON_INK_W = 11f / REF_DISPLAY_PX
private const val F_CHEVRON_INK_H = 19f / REF_DISPLAY_PX
private const val F_CHECK_INK_W = 23f / REF_DISPLAY_PX
private const val F_CHECK_INK_H = 16f / REF_DISPLAY_PX
private const val F_ICON_STROKE = 3.5f / REF_DISPLAY_PX

private const val F_GAP_TITLE_COUNTER = 3.5f / REF_DISPLAY_PX
private const val F_GAP_SELECTORS_HINT = 4.4f / REF_DISPLAY_PX
private const val F_GAP_HINT_BUTTONS = 2.4f / REF_DISPLAY_PX

/*
 * Font sizes: `fontSize_sp = capHeightRefPx / REF_DISPLAY_PX / CAP_TO_EM * screenWidthDp`. The cap
 * height is a pure ratio of the reference display, divided by Roboto's cap-to-em ratio (a
 * dimensionless number), and only then multiplied by our width in dp - so the px -> dp conversion
 * happens exactly once, inside `unit * fraction`. On the 227 dp emulator a cap of 27 reference px
 * becomes 29.0 sp and renders 41.3 px = 27 x 454/297.
 *
 * These constants used to read `(capHeightRefPx * 1.075) / 297`. The `1.075` is `0.7643 / 0.711`,
 * i.e. the px -> dp factor of THIS display divided by the cap-to-em ratio, and the `/ 297` then
 * applied that same factor a second time as a fraction of the width in dp: every size rendered at
 * 0.764x. RestTimer.kt does not have that bug and is the model for this form.
 *
 * Measured cap heights in the reference, from single-glyph ink boxes rather than whole bands:
 * title 16 (`L`), counter 13 (`S` of `Set 1/4`), labels 13.5, selected value 27 (`5`), neighbour
 * 10 (`4` of `49.5`), `prev.` hint 12 (its digits). The counter and neighbour were 14 and 12 in
 * the earlier transcription; 13 and 10 are what the pixels measure.
 *
 *  (Gus's report "the name of the exercise is always being cut"): the title moves from cap 16
 * to cap 14 and gains a second line, and the `Set n/N` counter drops from cap 13 to cap 12 (its
 * own step, one below the 13.5 labels, so the labels stay the row's anchors). Both follow
 * ``: cap 14 + 2 lines fits 15/15 real names at the
 * title's chord.
 */
private const val F_FONT_TITLE = (14f / REF_DISPLAY_PX) / CAP_TO_EM
private const val F_FONT_COUNTER = (12f / REF_DISPLAY_PX) / CAP_TO_EM
private const val F_FONT_LABEL = (13.5f / REF_DISPLAY_PX) / CAP_TO_EM
private const val F_FONT_VALUE = (27f / REF_DISPLAY_PX) / CAP_TO_EM
private const val F_FONT_NEIGHBOUR = (10f / REF_DISPLAY_PX) / CAP_TO_EM
private const val F_FONT_HINT = (12f / REF_DISPLAY_PX) / CAP_TO_EM

private const val LINE_HEIGHT_FACTOR = 1.2f

/*
 *  row: `Kg` · `Set n/N` · `Reps` on one line. The label slots are 60 reference px wide and are
 * centred on the spinner cards (the labels sat there before, above their cards), so the labels keep
 * their measured x positions and the counter lands dead centre between them. The reference display
 * is 297 px and the measured card centres sit at +/-0.2306 of the display width; with the counter
 * ~45 dp wide the slot centres resolve to ~122 / ~331 px on the 454 px emulator - the before
 * measurement (Kg [103,124][141,159] centre 122, Reps centre 333).
 */
/*
 *  row: `Kg` · `Set n/N` · `Reps` on one line. The label slots are 80 reference px wide so
 * their centres land on the spinner cards — the before measurement had the label ink at
 * x = 122 / 333 on the 454 px emulator, and the slots reproduce exactly that. The counter gets a
 * FIXED slot of 58 reference px (the measured width of `Set 1/4` at the cap-12 counter step), so
 * the labels stay put whatever the counter text is; a two-digit `Set 12/12` overflows the slot
 * symmetrically (~10 px each side) and still clears the label ink.
 */
private const val F_LABEL_SLOT = 80f / REF_DISPLAY_PX
private const val F_COUNTER_SLOT = 58f / REF_DISPLAY_PX

/*
 *  geometry for the title's chord: the title is
 * the first line of a centred column, so its vertical position is a function of the column's own
 * height. The metrics compute the line-1 centre in dp and hand that to `safeWidth`, which caps at
 * the circle's chord. `bandHeight = 0` intentionally: the cap must be the chord at the *line's*
 * centre (the row the glyphs' vertical middle actually sits on) - bandHeight would take the chord at
 * the line box's top edge, which is ~10 dp narrower than any ink row, over-constraining a two-line
 * title. The per-line margin to the bezel is verified by measurement, not by
 * arithmetic alone.
 */
/**
 *  geometry for the title's chord (see and
 * ): the title wraps to at most [TITLE_TWO_LINE_MAX] lines, and each line is
 * capped at the circle's chord at that line's INK row. The caps are not a fraction-of-reference-
 * constants: they are the measured positions of the ink (on the 454 px emulator, line-1 ink top
 * 46 px = 23 dp, line-2 ink top 82 px = 41 dp, documented in), expressed as
 * display fractions like every geometry value. The TWO-LINE position is used for line 1's cap
 * even when the title renders one line: a one-line title sits LOWER (its column is shorter), so
 * the two-line ink row is the tightest chord any title ink ever sees, and capping by it is always
 * safe. `ChordWrappedText` hands each line its own cap, so line 2 does not pay line 1's price.
 */
private const val TITLE_TWO_LINE_MAX = 2
private const val TITLE_LINE1_INK_TOP = 23f / 227f
private const val TITLE_LINE2_INK_TOP = 41f / 227f

/**
 * The reference's one dark neutral: `#1C1C1E` (28,28,30), measured on the chevron buttons' flat
 * fill (2757 exact pixels) and on the spinner cards' 2 px outline. It is now the theme's
 * [OpenGymSurface]: moved that token off the research doc's unmeasured `#2C2C2C`, so the page
 * no longer needs its own copy.
 *
 *  re-points the *button* fill at [OpenGymSurfaceHigh] (`#2C2C2E`, the palette's control rung)
 * and gives buttons an [OpenGymOutline] edge: before, the chevron discs were the same `#1C1C1E` as
 * everything behind them, so no button had a visible edge (measured: (28,28,30) on the disc). The
 * name is kept so the reference comment above stays true; only the value moved.
 */
private val SurfaceDark = OpenGymSurfaceHigh

/** A card has no fill of its own - its interior is plain black, like the page. Only the outline. */
private val CardOutline = OpenGymSurface

/**
 * The neighbours above and below the selected value are the selected colour at ~55 % brightness.
 * Measured: the weight column's neighbour peaks at 142 against a 255 selected (0.56); the reps
 * column's at (39,68,95) against (29,130,234). One rule covers both columns.
 */
private const val NEIGHBOUR_ALPHA = 0.55f

/*
 *  (2026-10-11): the `Kg` / `Reps` labels used to be two STATIC colours taken from the reference
 * screenshot (left white, right blue) with no relationship to which column the crown was driving.
 * They are now derived from the rotary target itself - see [selectorLabelColor] - so the colour
 * says what the bezel is currently turning, and the two can never disagree because there is only
 * one piece of state. Deliberate divergence from the reference, which has no such affordance.
 *
 * The *values* keep their own measured colours (weight white, reps blue): those were read off the
 * reference and are not a selection signal. See the recommendation at the foot of
 * 
 */

/**
 * The label colour for [column] given the current crown target: the selected column wears the
 * accent, the other is de-emphasised to [OpenGymMuted]. Pure, so the mapping is unit-testable
 * without a device.
 */
internal fun selectorLabelColor(column: RotaryTarget, selected: RotaryTarget): Color =
  if (column == selected) OpenGymBlue else OpenGymMuted

/** Tags. Distinct from `ExercisePagePlaceholder`'s, which is a different, still-live screen. */
const val EXERCISE_PAGE_TAG = "exercise_page"
const val EXERCISE_PAGE_PREV_TAG = "exercise_page.prev"
const val EXERCISE_PAGE_COMPLETE_TAG = "exercise_page.complete"
const val EXERCISE_PAGE_NEXT_TAG = "exercise_page.next"
const val EXERCISE_PAGE_WEIGHT_TAG = "exercise_page.weight"
const val EXERCISE_PAGE_REPS_TAG = "exercise_page.reps"
const val EXERCISE_PAGE_PREV_HINT_TAG = "exercise_page.prev_hint"

/** the `Set n/N` counter inside the label row (bounds-pinned by test). */
const val EXERCISE_PAGE_COUNTER_TAG = "exercise_page.counter"

/**
 * Which spinner owns the rotating crown. Tapping a card moves it.
 *
 * this is the ONE piece of state the page keeps for selection - the label colours (and the
 * selected card's outline) are derived from it, so "colour agrees with the crown target" is true
 * by construction rather than by two things being kept in sync. Internal rather than private only
 * so [selectorLabelColor] can be pinned by a JVM test.
 */
internal enum class RotaryTarget {
  Weight,
  Reps,
}

/**
 * The exercise page: what the user looks at between every set.
 *
 * Same vertical order as `references/exercise-page.png`: title, `Set n/N`, two vertical spinner
 * selectors (`Kg` / `Reps`), the `prev. …` hint, then `‹` / `✓` / `›`.
 *
 * The `♥` and `🔥` stat row from the reference is deliberately absent: openGym has no heart-rate or
 * calorie data for a live workout, and [WorkoutState] carries no field for either.
 *
 * both selectors are crown-driven. Rotary events are handled once at the root and routed to
 * whichever card was tapped last — a real crown emits one event per detent and the reference's
 * selector is a wheel, so one detent is one step (0.5 kg / 1 rep). The repository owns clamping, so
 * the displayed value cannot leave `0..500 kg` or `1..99` reps.
 *
 * `onAdjustWeight`, `onAdjustReps`, `canGoPrevious` and `canGoNext` are appended *after* `modifier`
 * on purpose: the five-parameter signature the integration step calls is unchanged, and both
 * positional and named calls written against it still bind to it.
 *
 * [canGoPrevious] / [canGoNext] now come from [WorkoutState.canGoPrevious] /
 * [WorkoutState.canGoNext] in `WearApp`, i.e. from the workout's `exerciseIndex` and
 * `exerciseCount`, so the chevrons grey out on the first and last exercise. The `true` defaults
 * below exist only for previews and for tests that mount the screen directly.
 *
 * both cards are also **finger-draggable** — a vertical drag moves the value, one row of
 * travel (`cardHeight / 3`) being one step, and the drag commits one absolute command on release
 * through [onSetWeight] / [onSetReps]. The drag belongs to the card, not to the page, and it
 * consumes **vertical motion only**: a horizontal swipe over a card is never consumed
 * here, so the swipe-right BACK gesture above the page still sees it. The arithmetic is in
 * `WheelDrag.kt`, outside Compose, so it is unit-testable. Rotary handling is unchanged.
 */
@Composable
fun ExercisePage(
  state: WorkoutState,
  onCompleteSet: () -> Unit,
  onPreviousExercise: () -> Unit,
  onNextExercise: () -> Unit,
  modifier: Modifier = Modifier,
  onAdjustWeight: (Double) -> Unit = {},
  onAdjustReps: (Int) -> Unit = {},
  canGoPrevious: Boolean = true,
  canGoNext: Boolean = true,
  /**
   * What to say when there is nothing to render. The default is right for a screen that has no
   * phone at all (previews, tests); `WearApp` passes `emptyWorkoutMessage(link)`, which says
   * "Phone not connected" instead when a link exists but is down — decision 6 of
   * ``.
   */
  emptyMessage: String = "No active workout",
  /**
   * the absolute commit of a wheel drag — sent **once**, on release, and only when the value
   * actually changed. Appended after
   * [emptyMessage] so no existing call site changes, and defaulted so a preview or a test that
   * mounts the screen directly still compiles; a drag whose release is not wired previews and sends
   * nothing.
   *
   * Absolute, not a delta: a drag is 1-20 steps in ~300 ms and `adjustWeight` is relative and
   * `baseRev`-gated, so a burst of deltas would be refused `stale` after the first one.
   */
  onSetWeight: (Double) -> Unit = {},
  /** the reps half of [onSetWeight]. Same one-command-on-release rule. */
  onSetReps: (Int) -> Unit = {},
) {
  if (!state.isWorkoutActive) {
    //  owns the real empty state. This exists so the screen cannot draw a spinner over a
    // workout that does not exist.
    Box(
      modifier = modifier.fillMaxSize().background(OpenGymBlack),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = emptyMessage,
        style = MaterialTheme.typography.bodySmall,
        color = OpenGymMuted,
        textAlign = TextAlign.Center,
      )
    }
    return
  }

  val focusRequester = remember { FocusRequester() }
  var rotaryTarget by remember { mutableStateOf(RotaryTarget.Weight) }

  // Task 2: the profile's unit and the weight the user can read. `state.weightKg` stays the wire
  // value (kg) everywhere; every weight below this line is in [unit]. Declared here, above
  // `BoxWithConstraints`, because the rotary handler in the modifier reads them.
  val unit = state.weightUnit
  val weightDisplay = unit.fromKg(state.weightKg)

  // the drag in progress in each column, or null while the column shows the projection.
  var weightDrag by remember { mutableStateOf<WheelDrag?>(null) }
  var repsDrag by remember { mutableStateOf<WheelDrag?>(null) }

  // one CLOCK_TICK per drag *step*. `wheelGesture` reports the whole-step count after every
  // drag event; the running total lives here (survives recompositions; the gesture objects do not)
  // and one tick fires per step crossed, in either direction. A drag that returns to its origin
  // ticks the same steps backwards — the dial has genuinely been turned both ways.
  var lastDragSteps by remember { mutableStateOf(0) }

  // the haptic ticker for this screen — the same CLOCK_TICK the system bezel emits, gated on
  // the system haptics setting. Fires on every button press, every rotary detent that lands a
  // step, and every drag step (below). NOT on a plain tap that only moves the crown's target.
  val tick = rememberHapticTick()

  // Rotary events reach the focused node and bubble up to its ancestors; nothing below this node
  // handles them, so the screen needs exactly one focus target.
  LaunchedEffect(focusRequester) { runCatching { focusRequester.requestFocus() } }

  val typography = MaterialTheme.typography

  BoxWithConstraints(
    modifier =
      modifier
        .fillMaxSize()
        .background(OpenGymBlack)
        .testTag(EXERCISE_PAGE_TAG)
        .onRotaryScrollEvent { event ->
          val step = if (event.verticalScrollPixels > 0f) 1 else -1
          when (rotaryTarget) {
            RotaryTarget.Weight -> {
              // Task 2: the detent moves one step of the DISPLAY unit, and what goes on the wire is
              // that step converted to kg. On a kg profile this is bit-for-bit the old
              // `step * WEIGHT_STEP_KG`, so a kg phone cannot tell the difference.
              // The repository clamps; a detent at an end lands no step, so it ticks nothing.
              val deltaDisplay = step * unit.displayStep
              if (unit.clampDisplay(weightDisplay + deltaDisplay) != weightDisplay) tick()
              onAdjustWeight(unit.toKg(deltaDisplay))
            }
            RotaryTarget.Reps -> {
              if (clampReps(state.reps + step) != state.reps) tick()
              onAdjustReps(step)
            }
          }
          true
        }
        .focusRequester(focusRequester)
        .focusable(),
    contentAlignment = Alignment.Center,
  ) {
    val metrics = remember(maxWidth, typography) { PageMetrics(maxWidth, typography) }

    // one row of travel is one step, and the card shows exactly three rows. In px, because
    // that is the unit the drag arrives in; the arithmetic lives in WheelDrag.kt.
    val wheelRowPx =
      with(LocalDensity.current) { (metrics.cardHeight / WHEEL_ROWS_PER_CARD).toPx() }

    // one CLOCK_TICK per crossed whole step, shared by both columns. `onStep` is invoked by
    // [wheelGesture] with 0 at drag start and with the new whole-step count after every drag
    // event; a 0 resets the total without ticking.
    val onDragStep: (Int) -> Unit = { newSteps ->
      if (newSteps == 0) {
        lastDragSteps = 0
      } else {
        val delta = newSteps - lastDragSteps
        lastDragSteps = newSteps
        repeat(abs(delta)) { tick() }
      }
    }

    // A column being dragged renders its own preview and ignores the projection until the drag
    // ends — an incoming `state` must not move the digits under the finger. The phone's
    // state wins again the moment the finger lifts.
    // Task 2: a drag's base is snapshotted in DISPLAY units, so the three rows on the card are
    // three clean steps of the unit the user reads; the release converts once, to kg.
    val weightShown = weightDrag?.weightDisplay(wheelRowPx, unit) ?: weightDisplay
    val reps = repsDrag?.reps(wheelRowPx) ?: state.reps

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      // the title is chord-capped per line (cap 14, up to two lines) — the fix for the
      // reported clipping. `metrics.titleLineCap(0)` is the chord at line-1's ink row; the
      // two-line label can use the wider chord of its own rows below.
      ChordWrappedText(
        text = state.exerciseName,
        style = metrics.titleStyle,
        color = OpenGymWhite,
        lineSafeWidth = { line -> metrics.titleLineCap(line) },
        maxLines = TITLE_TWO_LINE_MAX,
      )

      Spacer(Modifier.height(metrics.gapTitleCounter))

      // `Kg` · `Set n/N` · `Reps` on ONE row. The labels keep their measured positions (the
      // 60px slots are centred on the cards, so the label ink stays at ~122 / ~331 px on the 454
      // px emulator) while the counter slips into the space between them; the counter is a full
      // step smaller than the labels and muted, so the labels stay the row's anchors.
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.width(metrics.labelSlotWidth), contentAlignment = Alignment.Center) {
          Text(
            text = unit.label,
            style = metrics.labelStyle,
            color = selectorLabelColor(RotaryTarget.Weight, rotaryTarget),
            maxLines = 1,
          )
        }
        Text(
          text = "Set ${state.currentSetIndex}/${state.totalSets}",
          style = metrics.counterStyle,
          color = OpenGymMuted,
          maxLines = 1,
          textAlign = TextAlign.Center,
          // Fixed slot: pins the labels regardless of the counter text (see the row comment above).
          modifier =
            Modifier.width(metrics.counterSlotWidth)
              .testTag(EXERCISE_PAGE_COUNTER_TAG),
        )
        Box(Modifier.width(metrics.labelSlotWidth), contentAlignment = Alignment.Center) {
          Text(
            text = "Reps",
            style = metrics.labelStyle,
            color = selectorLabelColor(RotaryTarget.Reps, rotaryTarget),
            maxLines = 1,
          )
        }
      }

      Spacer(Modifier.height(metrics.gapLabelCard))

      Row(horizontalArrangement = Arrangement.spacedBy(metrics.gapBetweenCards)) {
        SpinnerSelector(
          valueText = formatWeight(weightShown),
          above = weightNeighbour(weightShown, -unit.displayStep, unit),
          below = weightNeighbour(weightShown, +unit.displayStep, unit),
          selectedColor = OpenGymWhite,
          crownLabel = unit.label,
          metrics = metrics,
          selected = rotaryTarget == RotaryTarget.Weight,
          onSelect = { rotaryTarget = RotaryTarget.Weight },
          tag = EXERCISE_PAGE_WEIGHT_TAG,
          gesture =
            wheelGesture(
              currentDrag = { weightDrag },
              setDrag = { weightDrag = it },
              base = { unit.fromKg(state.weightKg) },
              rowHeightPx = wheelRowPx,
              preview = { it.weightDisplay(wheelRowPx, unit) },
              // Task 2: the release is one conversion, display -> kg, on the wire.
              commit = { display -> onSetWeight(unit.toKg(display)) },
              onStep = onDragStep,
            ),
        )
        SpinnerSelector(
          valueText = reps.toString(),
          above = repsNeighbour(reps, -1),
          below = repsNeighbour(reps, +1),
          selectedColor = OpenGymBlue,
          crownLabel = "Reps",
          metrics = metrics,
          selected = rotaryTarget == RotaryTarget.Reps,
          onSelect = { rotaryTarget = RotaryTarget.Reps },
          tag = EXERCISE_PAGE_REPS_TAG,
          gesture =
            wheelGesture(
              currentDrag = { repsDrag },
              setDrag = { repsDrag = it },
              base = { state.reps.toDouble() },
              rowHeightPx = wheelRowPx,
              preview = { it.reps(wheelRowPx).toDouble() },
              commit = { onSetReps(it.toInt()) },
              onStep = onDragStep,
            ),
        )
      }

      Spacer(Modifier.height(metrics.gapSelectorsHint))

      // Always rendered, so losing the previous set does not shift the button row up. A single space
      // keeps the line box and paints nothing; an empty string can measure to zero height.
      Text(
        text = previousSetHint(state) ?: " ",
        style = metrics.hintStyle,
        color = OpenGymMuted,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag(EXERCISE_PAGE_PREV_HINT_TAG),
      )

      Spacer(Modifier.height(metrics.gapHintButtons))

      Row(
        horizontalArrangement = Arrangement.spacedBy(metrics.gapBetweenButtons),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        RoundButton(
          enabled = canGoPrevious,
          onClick = onPreviousExercise,
          onTick = tick,
          description = "Previous exercise",
          tag = EXERCISE_PAGE_PREV_TAG,
          metrics = metrics,
        ) {
          ChevronIcon(pointsRight = false, metrics = metrics)
        }
        RoundButton(
          enabled = true,
          onClick = onCompleteSet,
          onTick = tick,
          description = "Complete set",
          tag = EXERCISE_PAGE_COMPLETE_TAG,
          metrics = metrics,
          fill = OpenGymBlue,
          width = metrics.primaryWidth,
          height = metrics.primaryHeight,
        ) {
          CheckIcon(metrics = metrics)
        }
        RoundButton(
          enabled = canGoNext,
          onClick = onNextExercise,
          onTick = tick,
          description = "Next exercise",
          tag = EXERCISE_PAGE_NEXT_TAG,
          metrics = metrics,
        ) {
          ChevronIcon(pointsRight = true, metrics = metrics)
        }
      }
    }
  }
}

/**
 * `50.0` / `0.0` / `500.0`: one decimal always, and never in the device's locale.
 *
 * Task 2: the argument is the weight in the profile's own unit. One decimal covers both lattices
 * (0.5 kg and openGym's 0.5 lb) without any value ever needing a second one.
 */
internal fun formatWeight(weight: Double): String = String.format(Locale.US, "%.1f", weight)

/**
 * The value shown above or below the selected weight, or null past the ends of the range.
 *
 * Null rather than a clamped duplicate: a wheel picker shows a blank row past its last entry, and
 * `0.0` twice would read as a stuck spinner.
 *
 * Task 2: the argument and the delta are in [unit] (the display unit). The default keeps every
 * kg-profile call site — and the pre-existing tests — exactly as they were.
 */
internal fun weightNeighbour(
  weightDisplay: Double,
  deltaDisplay: Double,
  unit: WeightUnit = WeightUnit.Kilograms,
): String? {
  val neighbour = weightDisplay + deltaDisplay
  if (neighbour < 0.0 || neighbour > unit.maxDisplay) return null
  return formatWeight(neighbour)
}

/** The value shown above or below the selected reps, or null past `1..99`. */
internal fun repsNeighbour(reps: Int, delta: Int): String? {
  val neighbour = reps + delta
  if (neighbour < MIN_REPS || neighbour > MAX_REPS) return null
  return neighbour.toString()
}

/**
 * `prev. 50.0 kg x 10`, or null when there is no previous set to describe.
 *
 * Both fields must be present: the projection can hold one without the other, and
 * `prev. null kg x null` is the failure this guards against.
 */
internal fun previousSetHint(state: WorkoutState): String? {
  val weight = state.previousWeightKg ?: return null
  val reps = state.previousReps ?: return null
  // Task 2: the hint is in the profile's unit too — a `prev. 110.0 lb x 10` under a `Lb` label is
  // the same set the user just did, and `prev. 49.9 kg` under it would not be.
  val unit = state.weightUnit
  return "prev. ${formatWeight(unit.fromKg(weight))} ${unit.hintLabel} x $reps"
}

/**
 * Every dp and sp the page uses, derived once from the screen width. [unit] is that width, so the
 * constants above are read as "this many of the reference display's 297 px".
 */
private class PageMetrics(unit: Dp, typography: Typography) {
  /** Font sizes are a fraction of the width in dp, which equals sp at the default font scale. */
  private val width = unit.value

  val cardWidth = unit * F_CARD_W
  val cardHeight = unit * F_CARD_H
  val cardRadius = unit * F_CARD_RADIUS
  val cardBorder = unit * F_CARD_BORDER
  val buttonBorder = unit * F_CARD_BORDER
  val gapBetweenCards = unit * F_GAP_BETWEEN_CARDS
  val gapLabelCard = unit * F_GAP_LABEL_CARD

  val chevronDiameter = unit * F_CHEVRON_DIAMETER
  val primaryWidth = unit * F_PRIMARY_W
  val primaryHeight = unit * F_PRIMARY_H
  val gapBetweenButtons = unit * F_GAP_BETWEEN_BUTTONS

  val chevronInkWidth = unit * F_CHEVRON_INK_W
  val chevronInkHeight = unit * F_CHEVRON_INK_H
  val checkInkWidth = unit * F_CHECK_INK_W
  val checkInkHeight = unit * F_CHECK_INK_H
  val iconStroke = unit * F_ICON_STROKE

  val gapTitleCounter = unit * F_GAP_TITLE_COUNTER
  val gapSelectorsHint = unit * F_GAP_SELECTORS_HINT
  val gapHintButtons = unit * F_GAP_HINT_BUTTONS

  // the `Kg`/`Reps` slots bracket the counter. The slot width is chosen so the slots land
  // on the spinner cards (measured 122 / 333 px on the 454 px emulator — the before value); the
  // counter's slot is the measured width of `Set 1/4` at its cap-12 step, so the labels stay put.
  val labelSlotWidth = unit * F_LABEL_SLOT
  val counterSlotWidth = unit * F_COUNTER_SLOT

  // The numeral roles carry tabular figures, which is what a spinner whose digits change needs.
  val titleStyle = typography.titleMedium.scaled(width * F_FONT_TITLE)
  val counterStyle = typography.labelSmall.scaled(width * F_FONT_COUNTER)
  val labelStyle = typography.labelSmall.scaled(width * F_FONT_LABEL)
  val valueStyle = typography.numeralLarge.scaled(width * F_FONT_VALUE)
  val neighbourStyle = typography.numeralSmall.scaled(width * F_FONT_NEIGHBOUR)
  val hintStyle = typography.bodySmall.scaled(width * F_FONT_HINT)

  /*
   * the title's per-line chords. The constants are the measured ink rows of a TWO-LINE title
   * (the uppermost position, so the tightest) — see the constant block up top. `ChordWrappedText`
   * will never let a line exceed its own chord, and since glyph ink never sits above the line box
   * the chord at the ink row is a true (if hair-tight) bound; the screencap measurement
   *  verifies the actual clearance.
   */
  val titleLine1Cap = safeWidth(unit, TITLE_LINE1_INK_TOP)
  val titleLine2Cap = safeWidth(unit, TITLE_LINE2_INK_TOP)

  fun titleLineCap(line: Int): Dp = if (line == 0) titleLine1Cap else titleLine2Cap
}

/** Pins a type role's size while keeping its family and tabular figures. */
private fun TextStyle.scaled(fontSizeSp: Float): TextStyle =
  copy(fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp * LINE_HEIGHT_FACTOR).sp)

/**
 * The drag callbacks for one spinner column: one object rather than four lambdas, so the card can
 * hold the newest instance with a single `rememberUpdatedState`. A `pointerInput` block is started
 * once and would otherwise keep the callbacks — and the values they close over — from the
 * composition that mounted the card, so `base()` would read a stale value at drag start.
 *
 * Internal, and built by [wheelGesture] rather than inline, so the JVM test can drive the exact
 * release logic the composable uses without a device.
 */
internal class WheelGesture(
  val onStart: () -> Unit,
  val onDragBy: (Float) -> Unit,
  val onEnd: () -> Unit,
  val onCancel: () -> Unit,
  /** reports the whole-step count after every drag event; 0 at drag start. One CLOCK_TICK
   *  per step crossed is the caller's job. */
  val onStep: (Int) -> Unit,
)

/**
 * Wires one column's drag to that column's [WheelDrag] slot.
 *
 * [base] is read when the drag starts, so the wheel is measured from the value the user saw touch
 * the glass. [commit] is called **once**, on release, and only when the released value differs from
 * [base] — which is also what makes a drag that returns to its origin send nothing at all, and an
 * over-drag past an end (`preview` clamps) send nothing.
 *
 * [onStep] is called with 0 at drag start and with the new whole-step count after every drag
 * event, so the caller can fire one tick per *step* crossed — never per pixel.
 */
internal fun wheelGesture(
  currentDrag: () -> WheelDrag?,
  setDrag: (WheelDrag?) -> Unit,
  base: () -> Double,
  rowHeightPx: Float,
  preview: (WheelDrag) -> Double,
  commit: (Double) -> Unit,
  onStep: (Int) -> Unit = {},
): WheelGesture =
  WheelGesture(
    onStart = {
      setDrag(WheelDrag(base = base(), dragPx = 0f))
      onStep(0)
    },
    onDragBy = { deltaPx ->
      setDrag(currentDrag()?.let { it.copy(dragPx = it.dragPx + deltaPx) })
      onStep(currentDrag()?.steps(rowHeightPx) ?: 0)
    },
    onEnd = {
      val drag = currentDrag()
      setDrag(null)
      if (drag != null) {
        val target = preview(drag)
        if (target != drag.base) commit(target)
      }
    },
    onCancel = { setDrag(null) },
    onStep = onStep,
  )

/**
 * One spinner column: a rounded card holding the previous value, the selected value and the next
 * value, evenly spaced so the selected one sits at the card's centre. The `Kg` / `Reps` label that
 * used to live here moved into the page's label row so `Set n/N` can share it.
 *
 * the card is also a vertical drag target — see [wheelGesture].
 */
@Composable
private fun SpinnerSelector(
  valueText: String,
  above: String?,
  below: String?,
  selectedColor: Color,
  crownLabel: String,
  metrics: PageMetrics,
  /** is this the column the crown is driving? Brightens the card's outline. */
  selected: Boolean,
  onSelect: () -> Unit,
  tag: String,
  gesture: WheelGesture,
) {
  val shape = RoundedCornerShape(metrics.cardRadius)
  // Read through the holder, not captured: the pointer-input block below is started once.
  val wheel by rememberUpdatedState(gesture)
  // the selected card's edge is the palette's outline rung, the other stays the measured
  // card outline. Existing tokens only - nothing in theme/** changed.
  val outline = if (selected) OpenGymOutline else CardOutline
  Box(
    modifier =
      Modifier.size(metrics.cardWidth, metrics.cardHeight)
        .clip(shape)
        .border(metrics.cardBorder, outline, shape)
        .clickable(onClick = onSelect)
        .pointerInput(Unit) {
          // VERTICAL drags only. `detectDragGestures` consumes both axes and
          // would kill the swipe-right BACK gesture. With an explicit vertical orientation lock
          // the slop accumulator only ever looks at `|total y|`, so a horizontal drag is never
          // consumed here and propagates to whatever handles BACK above this page.
          detectVerticalDragGestures(
            onDragStart = { wheel.onStart() },
            onDragEnd = { wheel.onEnd() },
            onDragCancel = { wheel.onCancel() },
          ) { _, dragAmount ->
            wheel.onDragBy(dragAmount)
          }
        }
        .semantics {
          role = Role.Button
          contentDescription = "Select $crownLabel for the crown"
        }
        .testTag(tag),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.SpaceEvenly,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      SpinnerValue(above, selectedColor, metrics)
      Text(
        text = valueText,
        style = metrics.valueStyle,
        color = selectedColor,
        maxLines = 1,
        textAlign = TextAlign.Center,
      )
      SpinnerValue(below, selectedColor, metrics)
    }
  }
}

/** A neighbour value, or a blank line of the same height when the wheel ends here. */
@Composable
private fun SpinnerValue(text: String?, selectedColor: Color, metrics: PageMetrics) {
  Text(
    text = text ?: " ",
    style = metrics.neighbourStyle,
    color = selectedColor.copy(alpha = NEIGHBOUR_ALPHA),
    maxLines = 1,
    textAlign = TextAlign.Center,
  )
}

/**
 * A filled button: a circle by default, a stadium when [width] and [height] differ (the reference's
 * `✓`). Emphasis is a fill change — the chevrons are [SurfaceDark] (now [OpenGymSurfaceHigh], the
 * palette's button rung) with a white glyph, `✓` is [OpenGymBlue] with a white glyph.
 *
 * buttons carry an [OpenGymOutline] edge so their extent is visible against the page — before,
 * a chevron disc filled `OpenGymSurface` was the same value as the card outline behind it. Every
 * press also fires [onTick] (one CLOCK_TICK, gated on the system haptics setting).
 */
@Composable
private fun RoundButton(
  enabled: Boolean,
  onClick: () -> Unit,
  onTick: () -> Unit,
  description: String,
  tag: String,
  metrics: PageMetrics,
  fill: Color = SurfaceDark,
  width: Dp = metrics.chevronDiameter,
  height: Dp = metrics.chevronDiameter,
  icon: @Composable () -> Unit,
) {
  val shape = if (width == height) CircleShape else RoundedCornerShape(percent = 50)
  Box(
    modifier =
      Modifier.size(width, height)
        .clip(shape)
        .background(if (enabled) fill else fill.copy(alpha = 0.5f))
        .border(metrics.buttonBorder, OpenGymOutline, shape)
        .clickable(
          enabled = enabled,
          onClick = {
            onTick()
            onClick()
          },
        )
        .semantics {
          role = Role.Button
          contentDescription = description
        }
        .testTag(tag),
    contentAlignment = Alignment.Center,
  ) {
    icon()
  }
}

/** An open chevron, `‹` or `›`. The ink box is the reference's 11 x 19 px. */
@Composable
private fun ChevronIcon(pointsRight: Boolean, metrics: PageMetrics) {
  Box(
    Modifier.size(metrics.chevronInkWidth, metrics.chevronInkHeight).drawBehind {
      val stroke =
        Stroke(width = metrics.iconStroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
      val inset = stroke.width / 2f
      val path =
        Path().apply {
          if (pointsRight) {
            moveTo(inset, inset)
            lineTo(size.width - inset, size.height / 2f)
            lineTo(inset, size.height - inset)
          } else {
            moveTo(size.width - inset, inset)
            lineTo(inset, size.height / 2f)
            lineTo(size.width - inset, size.height - inset)
          }
        }
      drawPath(path = path, color = OpenGymWhite, style = stroke)
    }
  )
}

/** The checkmark. The ink box is the reference's 23 x 16 px, with the vertex at 36 % of the width. */
@Composable
private fun CheckIcon(metrics: PageMetrics) {
  Box(
    Modifier.size(metrics.checkInkWidth, metrics.checkInkHeight).drawBehind {
      val stroke =
        Stroke(width = metrics.iconStroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
      val inset = stroke.width / 2f
      val path =
        Path().apply {
          moveTo(inset, size.height * 0.36f)
          lineTo(size.width * 0.36f, size.height - inset)
          lineTo(size.width - inset, inset)
        }
      drawPath(path = path, color = OpenGymWhite, style = stroke)
    }
  )
}
