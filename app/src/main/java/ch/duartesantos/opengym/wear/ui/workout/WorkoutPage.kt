package ch.duartesantos.opengym.wear.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.Typography
import ch.duartesantos.opengym.wear.data.WorkoutRow
import ch.duartesantos.opengym.wear.data.WorkoutState
import ch.duartesantos.opengym.wear.theme.OpenGymBlack
import ch.duartesantos.opengym.wear.theme.OpenGymBlue
import ch.duartesantos.opengym.wear.theme.OpenGymMuted
import ch.duartesantos.opengym.wear.theme.OpenGymOutline
import ch.duartesantos.opengym.wear.theme.OpenGymSurfaceRaised
import ch.duartesantos.opengym.wear.theme.OpenGymWhite
import ch.duartesantos.opengym.wear.ui.safeTextWidth
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/*
 *  — the workout page: every exercise in the session with its progress, the current one raised.
 *
 * Geometry: every dimension below is a fraction of the *display width*, measured from
 * `references/workout-page.png` with `tools/workout_page_measure.py`. The script's raw output and the
 * constant-by-constant derivation are in `` section 2. That
 * reference's watch disc is 305 px — the same disc as `references/rest-period-timer.png`, which
 * `RestTimer.kt` measures against with the same `REF_DISPLAY_PX = 305f`.
 *
 *   card            264 x 89 px, corner radius ~32 px, side inset 21 px (border now the outline
 *  token; the current row's fill the raised token — see 29. The reference's
 *                    one measured neutral `#1C1C1E` is why the page itself and the non-current rows
 *                    keep `OpenGymSurface`/transparent-over-black)
 *   card padding    13 px from the card's left edge to the first ink
 *   ink cap heights row name 15 px, row `n/N Sets` 16 px, workout name 21 px, elapsed timer 22 px
 *
 * Font sizes use `RestTimer.kt`'s form, not `ExercisePage.kt`'s: the cap height is a pure ratio of
 * the reference display, divided by Roboto's cap-to-em ratio (a number with no unit at all), and only
 * then multiplied by our width in dp — so the px -> dp conversion happens exactly once. The exercise
 * page's own header comment explains the bug that folding that factor into the fraction causes; this
 * page does not reintroduce it.
 */

/** The reference display's diameter in px. Every fraction below is measured against this. */
private const val REF_DISPLAY_PX = 305f

/** Roboto cap height per em. A digit's or a capital's ink box is exactly the cap height. */
private const val CAP_TO_EM = 0.711f

private const val F_CARD_W = 264f / REF_DISPLAY_PX
private const val F_CARD_H = 89f / REF_DISPLAY_PX
private const val F_CARD_RADIUS = 32f / REF_DISPLAY_PX
private const val F_CARD_PAD = 13f / REF_DISPLAY_PX

/*
 * The card's outline. The reference's card has no visible outline — it is a fill — so this is the
 * exercise page's measured 1.96 reference px, which its comment derives as the value that renders as
 * 3 device px after `Modifier.border` rounds the stroke width up. It exists here because the rows
 * that are *not* current are drawn as outlines on black, which is the theme's only other treatment.
 */
private const val F_CARD_BORDER = 1.96f / REF_DISPLAY_PX

/*
 * The header's vertical placement on the round disc. The reference's own name band starts at y90 of
 * its 305 px disc (timer at y123, card top y192), which puts the header around the vertical middle;
 *  moves it up so the list — the page's focus — gets the space. The new top (y38) is the
 * highest the name can sit without clipping at the bezel: measured with the real typeface, `Full Body A`
 * at cap 21 renders 118.7 dp on a 227 dp screen, and the chord at band-top fraction 38/305 is
 * 149.9 dp, minus the 8 dp per-side inset = 133.9 dp budget — so 38/305 is the first placement
 * with real headroom. The name is now drawn at cap 16, so it only has more room. The name→timer gap
 * keeps the reference's measured 13 px; the gap to the list is trimmed from 20 to 14 px because the
 * reference's third band (y158..180) that used to justify the extra air has no equivalent here:
 * that space is now the list's.
 *
 * With the header up, each row's band is a circle chord, not the card's measured 86.56 %d width —
 * a row scrolled to the top or bottom of the viewport would run under the bezel. Rows are therefore
 * capped at `safeWidth(...)` of their own band (computed per row from the list's scroll state);
 * the card keeps its full measured width anywhere near the middle of the disc.
 */
private const val F_HEADER_TOP = 38f / REF_DISPLAY_PX
private const val F_GAP_NAME_TIMER = 13f / REF_DISPLAY_PX
private const val F_GAP_TIMER_LIST = 14f / REF_DISPLAY_PX

/** Between two rows. **Interpolated, not measured**: the reference shows a single card. */
private const val F_ROW_GAP = 8f / REF_DISPLAY_PX

/** Bottom inset for the list, from the reference card's bottom at 92.13 %d. */
private const val F_LIST_BOTTOM_INSET = 24f / REF_DISPLAY_PX

// The reference drew the name at cap 21 and the timer at cap 22, which on a 227 dp disc left no room
// for ordinary workout names. Both are reduced (name 16, timer 14) so a name fits whole and the
// timer reads as a subtitle rather than a headline.
private const val F_FONT_WORKOUT_NAME = (16f / REF_DISPLAY_PX) / CAP_TO_EM
private const val F_FONT_TIMER = (14f / REF_DISPLAY_PX) / CAP_TO_EM
private const val F_FONT_ROW_NAME = (15f / REF_DISPLAY_PX) / CAP_TO_EM
private const val F_FONT_ROW_SETS = (16f / REF_DISPLAY_PX) / CAP_TO_EM

private const val LINE_HEIGHT_FACTOR = 1.2f

/** One rotary detent scrolls by this fraction of a row, so a detent is a visible nudge. */
private const val ROTARY_STEP_OF_ROW = 0.5f

const val WORKOUT_PAGE_TAG = "workout_page"
const val WORKOUT_PAGE_NAME_TAG = "workout_page.name"
const val WORKOUT_PAGE_TIMER_TAG = "workout_page.timer"
const val WORKOUT_PAGE_LIST_TAG = "workout_page.list"

/** The tag of row [index], so a dump can address one row without matching on its text. */
fun workoutRowTag(index: Int): String = "workout_page.row.$index"

/**
 * The workout page: the workout's name, the locally-derived elapsed timer, and every exercise in the
 * session with its `done/total` sets.
 *
 * Progress is sets only — no per-set ticks and no checkmarks. The current
 * exercise is the filled card and rows whose sets are all done are dimmed. The exercise thumbnail,
 * the `(Machine)` subtitle and the reference's `♥`/`🔥` stat row are deliberately absent: openGym has
 * no source for either stat, and the first two are ~140 MB of media this page has no job for ( * decision 2).
 *
 * The elapsed timer is derived **locally** from `startedAt` and the watch's own clock, so it keeps
 * moving with no phone traffic, and it is hidden entirely when the phone did not send a start
 * .
 *
 * [onSelectRow] is called with the row's `index` — its position in the session, which is exactly the
 * value `selectExercise` takes. Getting back to the list is the BACK gesture, which lives above this
 * page (`WearApp`), so there is no back button here.
 */
@Composable
fun WorkoutPage(
  state: WorkoutState,
  onSelectRow: (Int) -> Unit,
  modifier: Modifier = Modifier,
  pendingExerciseIndex: Int? = null,
  emptyMessage: String = "No active workout",
  /** The watch's own clock. Injected only so a preview or a test can pin the timer. */
  nowMillis: () -> Long = { System.currentTimeMillis() },
) {
  if (!state.isWorkoutActive) {
    //  rule 5: with no workout the chosen screen is forced to the exercise page, which owns
    // `emptyWorkoutMessage(link)`. This guard exists so a stray call can never draw an empty list; it
    // is not a second empty state.
    Box(modifier = modifier.fillMaxSize().background(OpenGymBlack), contentAlignment = Alignment.Center) {
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
  // One focus target at the page root: rotary events reach the focused node and bubble up to it.
  LaunchedEffect(focusRequester) { runCatching { focusRequester.requestFocus() } }

  val typography = MaterialTheme.typography

  // The rotary step, from the same fraction of the screen width the row height uses, computed here
  // rather than inside `BoxWithConstraints` so that the focus target and the rotary handler can live
  // on the outermost node - the shape `ExercisePage` uses, and the one rotary delivery needs: the
  // event goes to the focused node and bubbles *up*, so a handler below the focus target never sees
  // one.
  val rotaryStepPx =
    with(LocalDensity.current) {
      (LocalConfiguration.current.screenWidthDp.dp * F_CARD_H * ROTARY_STEP_OF_ROW).toPx()
    }

  /** The list's position, shared by the rotary handler above and the list itself below. */
  val listState = rememberLazyListState()
  val scrollScope = rememberCoroutineScope()

  // JANK FIX: the tick itself lives inside [WorkoutTimer] so that one second passing
  // recomposes the timer `Text` and nothing else. When the tick state lived here, in the page body,
  // every tick invalidated this composable and the whole `Column` below - header and list included -
  // and Compose re-laid-out the page's text from `AndroidComposeView.dispatchDraw` (measured
  // ~34 ms per tick on the benchmark build,). The page only needs to know whether the
  // timer should exist at all, and that follows from the absolute start, not from the ticking clock:
  // it is read once, from the same clock function, and answers null when the phone sent no start.
  val startedAtMillis = state.startedAtMillis
  val timerVisible = startedAtMillis != null && state.elapsedSeconds(nowMillis()) != null

  BoxWithConstraints(
    modifier =
      modifier
        .fillMaxSize()
        .background(OpenGymBlack)
        .testTag(WORKOUT_PAGE_TAG)
        .onRotaryScrollEvent { event ->
          // One event per detent, so one detent is one visible nudge down the list.
          val delta = if (event.verticalScrollPixels > 0f) rotaryStepPx else -rotaryStepPx
          scrollScope.launch { listState.scrollBy(delta) }
          true
        }
        .focusRequester(focusRequester)
        .focusable(),
  ) {
    val metrics = remember(maxWidth, typography) { WorkoutMetrics(maxWidth, typography) }
    val density = LocalDensity.current
    // Captured once, at the top: inside the `Column`/`LazyColumn`/`with` lambdas below the
    // BoxWithConstraints receiver is not in scope for implicit resolution, so `maxWidth` must not
    // be referred to there.
    val displayWidth = maxWidth
    val rows = state.listRows
    val currentIndex = rows.indexOfFirst { it.index == state.exerciseIndex }

    // Opens centred on the current exercise. Runs once per visit: the destination change in
    // `WearApp` re-creates this page, and re-centring on every projection would fight the user's own
    // scrolling. The viewport's height is only known after the first layout pass, hence the flow.
    LaunchedEffect(Unit) {
      if (currentIndex < 0) return@LaunchedEffect
      val viewport = snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
      val rowHeightPx = with(density) { metrics.rowHeight.toPx() }
      val centring = -(viewport / 2f - rowHeightPx / 2f)
      runCatching { listState.scrollToItem(currentIndex, centring.toInt()) }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
      Spacer(Modifier.height(metrics.headerTop))

      // Capped at the display's chord at this line's height: a long workout name ellipsises before
      // it can run under the bezel.
      Text(
        text = state.workoutName,
        style = metrics.workoutNameStyle,
        color = OpenGymWhite,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier =
          Modifier
            .testTag(WORKOUT_PAGE_NAME_TAG)
            .safeTextWidth(
              screenWidth = displayWidth,
              yFraction = metrics.nameCentreFraction,
              inset = 8.dp,
              bandHeight = metrics.nameLineHeight,
            ),
      )

      // Hidden entirely — not shown as 00:00 — when the phone sent no `startedAt`, and while the
      // start is in the future (`elapsedSeconds` answers null for both).
      // With the timer hidden the list starts higher; the row capping below must know the real top.
      val listTop = if (timerVisible) metrics.listTop else metrics.listTopNoTimer
      if (timerVisible && startedAtMillis != null) {
        WorkoutTimer(
          state = state,
          startedAtMillis = startedAtMillis,
          metrics = metrics,
          displayWidth = displayWidth,
          nowMillis = nowMillis,
        )
      }

      Spacer(Modifier.height(metrics.gapTimerList))

      LazyColumn(
        state = listState,
        // A plain scrollable: a finger drag works with no extra code, and the rotary handler above
        // nudges this same state, so the two cannot fight over where the list is.
        modifier = Modifier.fillMaxWidth().testTag(WORKOUT_PAGE_LIST_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(metrics.rowGap),
        contentPadding = PaddingValues(bottom = metrics.listBottomInset),
      ) {
        items(rows, key = { it.index }) { row ->
          // The row's band centre in display space, derived from the list's own scroll state so the
          // chord cap tracks the row as it scrolls. Pure function — the signs and the stride
          // convention are pinned by its JVM tests, the same way WearGeometryTest pins yFraction.
          val rowCentreFraction =
            rowCentreYFraction(
              listTopPx = with(density) { listTop.toPx() },
              rowIndex = row.index,
              firstVisibleItemIndex = listState.firstVisibleItemIndex,
              firstVisibleItemScrollOffsetPx = listState.firstVisibleItemScrollOffset.toFloat(),
              stridePx = with(density) { metrics.rowStride.toPx() },
              rowHeightPx = with(density) { metrics.rowHeight.toPx() },
              screenWidthPx = with(density) { displayWidth.toPx() },
            )
          ExerciseRow(
            row = row,
            isCurrent = row.index == state.exerciseIndex,
            isPending = row.index == pendingExerciseIndex,
            metrics = metrics,
            displayWidth = displayWidth,
            rowCentreFraction = rowCentreFraction,
            onTap = { onSelectRow(row.index) },
          )
        }
      }
    }
  }
}

/**
 * The elapsed timer, and the only composable on this page that reads the clock.
 *
 * The tick is isolated here on purpose: `tickMillis` is read only in this scope, so one
 * second passing recomposes this `Text` and nothing above or below it. The tick lands on the second
 * boundary of the elapsed time rather than on a naive 1 s delay, so a late frame cannot make the
 * display drift.
 */
@Composable
private fun WorkoutTimer(
  state: WorkoutState,
  startedAtMillis: Long,
  metrics: WorkoutMetrics,
  displayWidth: Dp,
  nowMillis: () -> Long,
) {
  var tickMillis by remember { mutableLongStateOf(nowMillis()) }
  LaunchedEffect(startedAtMillis) {
    while (true) {
      tickMillis = nowMillis()
      delay(1_000L - Math.floorMod(tickMillis - startedAtMillis, 1_000L))
    }
  }
  val elapsed = state.elapsedSeconds(tickMillis) ?: return
  Spacer(Modifier.height(metrics.gapNameTimer))
  Text(
    text = formatElapsed(elapsed),
    style = metrics.timerStyle,
    color = OpenGymMuted,
    maxLines = 1,
    textAlign = TextAlign.Center,
    modifier =
      Modifier
        .testTag(WORKOUT_PAGE_TIMER_TAG)
        .safeTextWidth(
          screenWidth = displayWidth,
          yFraction = metrics.timerCentreFraction,
          inset = 8.dp,
          bandHeight = metrics.timerLineHeight,
        ),
  )
}

/** One exercise of the session: its name and its `n/N Sets` progress. */
@Composable
private fun ExerciseRow(
  row: WorkoutRow,
  isCurrent: Boolean,
  isPending: Boolean,
  metrics: WorkoutMetrics,
  displayWidth: Dp,
  rowCentreFraction: Float,
  onTap: () -> Unit,
) {
  val shape = RoundedCornerShape(metrics.cardRadius)
  val completed = row.setsTotal > 0 && row.setsDone >= row.setsTotal

  // Palette: the *current* row is the raised filled card, every other row is the same
  // outline over black the exercise page already uses; the border is the outline token. The old
  // #1C1C1E-on-#1C1C1E border was the measured "no visible edge" complaint.
  // A finished row's name stays muted — no ticks.
  val fill = if (isCurrent) OpenGymSurfaceRaised else Color.Transparent
  val nameColor =
    when {
      // The one state the reference has no equivalent for. rule 6 requires the row to be
      // *pending*, and a pending state the user cannot see is not a state. Blue is the theme's
      // existing signal colour and the only token that reads as "happening now". The pending row's
      // card is left at the outline treatment — it becomes the raised card when the state arrives.
      isPending -> OpenGymBlue
      isCurrent -> OpenGymWhite
      completed -> OpenGymMuted
      else -> OpenGymWhite
    }

  val view = LocalView.current

  Box(
    modifier =
      // The card keeps its measured 86.56 %d width. Rows near the top/bottom of the viewport are
      // shaved by the round mask like any Wear list row — the platform norm; the *text* inside is
      // what must never be laid out to the rectangle, and it is capped below.
      Modifier.fillMaxWidth(metrics.cardWidthFraction)
        .height(metrics.rowHeight)
        .clip(shape)
        .background(fill, shape)
        .border(metrics.cardBorder, OpenGymOutline, shape)
        .clickable(
          onClick = {
            // The system bezel's own clock tick, gated on the view's haptic setting — see
            // `Haptics.kt`. On this emulator it is a deliberate no-op (no motor); the call and the
            // gate are what is verified.
            view.workoutRowTick()
            onTap()
          },
        )
        .testTag(workoutRowTag(row.index))
        // One merged node per row: the name and the progress are two Texts, and a row is one thing
        // to a screen reader (and to `uiautomator dump`).
        .semantics(mergeDescendants = true) {
          role = Role.Button
          contentDescription = "${row.name}, ${row.setsDone} of ${row.setsTotal} sets"
        },
    contentAlignment = Alignment.CenterStart,
  ) {
    Column(Modifier.padding(horizontal = metrics.cardPad)) {
      Text(
        text = row.name,
        style = metrics.rowNameStyle,
        color = nameColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // The title's own ellipsis budget is the chord at the row's band centre (single line, no
        // band: a row half below the disc edge simply shows its visible part, like any Wear list).
        // Where the chord is wide — the resting current row — the card's interior (the column's
        // padding) is the binding cap, so short names never ellipsise.
        modifier = Modifier.safeTextWidth(displayWidth, rowCentreFraction),
      )
      Text(
        text = setsLabel(row),
        style = metrics.rowSetsStyle,
        color = OpenGymMuted,
        maxLines = 1,
      )
    }
  }
}

/**
 * `3/3 Sets` — the reference's own wording. A zero-set exercise reads `0/0 Sets` rather than a blank
 * line, so a row's progress is never missing.
 */
internal fun setsLabel(row: WorkoutRow): String = "${row.setsDone}/${row.setsTotal} Sets"

/**
 * `45:12`, or `1:05:03` past an hour. Digits only, so the string's width does not jump about as the
 * clock runs. Never negative: [WorkoutState.elapsedSeconds] answers null instead.
 */
internal fun formatElapsed(elapsedSeconds: Long): String {
  val total = elapsedSeconds.coerceAtLeast(0L)
  val hours = total / 3_600L
  val minutes = (total % 3_600L) / 60L
  val seconds = total % 60L
  return if (hours > 0L) {
    String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
  } else {
    String.format(Locale.US, "%02d:%02d", minutes, seconds)
  }
}

/**
 * Every dp and sp the page uses, derived once from the screen width. [unit] is that width, so the
 * constants above are read as "this many of the reference display's 305 px".
 *
 * The row is the reference card's full measured height. That height contained the exercise thumbnail
 * (which is dropped) as well as the two text lines, so a row is airier than its content needs; it is
 * kept because it is the measurement, and it leaves the reference's generous touch target intact.
 * One consequence is recorded rather than hidden: with the header above it, the list shows about one
 * row at a time, so the page scrolls.
 */
private class WorkoutMetrics(unit: Dp, typography: Typography) {
  /** Font sizes are a fraction of the width in dp, which equals sp at the default font scale. */
  private val width = unit.value

  val cardWidthFraction = F_CARD_W
  val cardWidth = unit * F_CARD_W
  val rowHeight = unit * F_CARD_H
  val cardRadius = unit * F_CARD_RADIUS
  val cardBorder = unit * F_CARD_BORDER
  val cardPad = unit * F_CARD_PAD
  val rowGap = unit * F_ROW_GAP
  /** One row's top to the next's — `rowHeight + rowGap`, the LazyColumn's stride. */
  val rowStride = rowHeight + rowGap
  val listBottomInset = unit * F_LIST_BOTTOM_INSET

  val headerTop = unit * F_HEADER_TOP
  val gapNameTimer = unit * F_GAP_NAME_TIMER
  val gapTimerList = unit * F_GAP_TIMER_LIST

  /** The line boxes the chord caps are measured against. */
  val nameLineHeight = unit * F_FONT_WORKOUT_NAME * LINE_HEIGHT_FACTOR
  val timerLineHeight = unit * F_FONT_TIMER * LINE_HEIGHT_FACTOR

  /** The list viewport's top edge (header + name + gap + timer + gap), with the timer shown. */
  val listTop = headerTop + nameLineHeight + gapNameTimer + timerLineHeight + gapTimerList
  /** …and when the timer is hidden (`startedAt` null): the rows sit a timer-band higher up. */
  val listTopNoTimer = listTop - gapNameTimer - timerLineHeight

  /** The name band's centre as a yFraction (centre = top + half its line box, over display width). */
  val nameCentreFraction = (headerTop.value + nameLineHeight.value / 2f) / width
  /** The timer band's centre as a yFraction. */
  val timerCentreFraction =
    (headerTop.value + nameLineHeight.value + gapNameTimer.value + timerLineHeight.value / 2f) / width

  val workoutNameStyle = typography.titleMedium.scaled(width * F_FONT_WORKOUT_NAME)
  val timerStyle = typography.numeralLarge.scaled(width * F_FONT_TIMER)
  val rowNameStyle = typography.labelLarge.scaled(width * F_FONT_ROW_NAME)
  val rowSetsStyle = typography.labelSmall.scaled(width * F_FONT_ROW_SETS)
}

/**
 * The vertical centre of row [rowIndex]'s band as a yFraction, from the list's scroll state.
 *
 * The convention, pinned: with the list at rest on its first item (`firstVisibleItemIndex = 0`,
 * offset 0), row 0's centre sits `listTopPx + rowHeightPx / 2` below the display box's top.
 * Scrolling by a full row stride moves every band up by that stride; the scroll offset is the part
 * of the first visible item already past the viewport's top. Pure so the signs are pinned by a JVM
 * test — the same idea as `WearGeometryTest` pinning yFraction's direction. `screenWidthPx` is the
 * display box's width (square on Wear), matching the helper's convention of y-over-width.
 */
internal fun rowCentreYFraction(
  listTopPx: Float,
  rowIndex: Int,
  firstVisibleItemIndex: Int,
  firstVisibleItemScrollOffsetPx: Float,
  stridePx: Float,
  rowHeightPx: Float,
  screenWidthPx: Float,
): Float =
  (listTopPx + (rowIndex - firstVisibleItemIndex) * stridePx - firstVisibleItemScrollOffsetPx + rowHeightPx / 2f) /
    screenWidthPx

/** Pins a type role's size while keeping its family and tabular figures. */
private fun TextStyle.scaled(fontSizeSp: Float): TextStyle =
  copy(fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp * LINE_HEIGHT_FACTOR).sp)
