package ch.duartesantos.opengym.wear.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/*
 * Circle-aware layout maths for a round Wear display.
 *
 * The bug this exists to fix
 * --------------------------
 * Every dimension on every screen in this app is a fraction of the **screen width**, which on a
 * round watch is the *diameter* of the display. That is correct at the vertical centre of the
 * screen and progressively wrong everywhere else, because the display is a circle: the usable width
 * of a horizontal band at height `y` is that circle's **chord**, not its diameter. At yFraction 0.1
 * (a tenth of the way down from the top) the chord is 60 % of the diameter; at yFraction 0.05 it is
 * 44 %. Text centred near the top or the bottom therefore runs under the bezel and is clipped - which
 * is exactly what Gus reported twice: "the name of the exercise is always being cut by the borders
 * of the watch" and "the 'next: exercise-name' is also hitting the sides and being cut".
 *
 * The convention, precisely
 * -------------------------
 * `yFraction` is a fraction of the display's **width** (the box is square on every round Wear
 * device, so the width is also the height), measured **down from the top edge of that box**:
 *
 *     yFraction = 0.0  -> the top edge of the display box       (usable width 0)
 *     yFraction = 0.5  -> the horizontal centre line            (usable width = screenWidth)
 *     yFraction = 1.0  -> the bottom edge of the display box    (usable width 0)
 *     yFraction = yPx / screenWidthPx
 *
 * or, for a band: `yFraction` is the vertical centre of that band, **not** its top or its bottom.
 * `WearGeometryTest` pins this direction in both halves - see
 * `topEdgeHasNoUsableWidthAndTheCentreLineHasAllOfIt`.
 *
 * Out-of-domain input is defined rather than left to chance: a non-finite or out-of-range
 * `yFraction`, or a non-positive `screenWidth`, yields `0.dp`; a negative `inset` or `bandHeight` is
 * clamped to 0. Nothing here throws, so no screen needs a guard.
 *
 * What this is NOT
 * ----------------
 * This gives a **rectangular** safe box per band. It is not circular text: a line laid out to
 * `safeWidth` is guaranteed to fit *if it is no taller than `bandHeight`* and its ink stays inside
 * that band, because the box's corners are inside the circle only when the box's own top and bottom
 * edges are inside it - which is what `bandHeight` accounts for. Glyphs that overhang the band, a
 * box that is deliberately pushed past the chord, and non-horizontal text are outside its scope.
 * It also says nothing about the display's physical edge: a screen cut round at the very boundary
 * still needs the visual inset that each screen's own metrics carry.
 */

/**
 * The usable width of a circle of diameter [screenWidth] on the horizontal line at [yFraction].
 *
 * This is the chord of the circle: `screenWidth * sqrt(1 - (2y - 1)^2)`. It is `screenWidth` at
 * [yFraction] 0.5 (the centre line, i.e. the full diameter) and 0 at 0.0 and 1.0 (the top and bottom
 * edges of the bounding box), and it is symmetric about 0.5.
 *
 * Pure arithmetic on [Dp] - no density, no Compose runtime, no Android - so it is exercised by the
 * JVM unit suite (`WearGeometryTest`) rather than by a device test.
 */
fun chordWidth(screenWidth: Dp, yFraction: Float): Dp {
  if (screenWidth.value <= 0f || !yFraction.isFinite()) return 0.dp
  if (yFraction <= 0f || yFraction >= 1f) return 0.dp
  val halfBand = 2f * yFraction - 1f // -1 at the top edge, 0 at the centre line, +1 at the bottom
  val fraction = sqrt(1f - halfBand * halfBand) // never negative: |halfBand| < 1 in here
  return screenWidth * fraction
}

/**
 * How wide a text line (or any other box) may be at height [yFraction] without leaving the circle.
 *
 * This is the one to call for "how wide may this text be at this height". It is
 * `chordWidth` minus `2 * inset` - [inset] is applied **horizontally, per side**, not as a shrink of
 * the circle, so it reads as "keep this much clear of the bezel on each side". The result is floored
 * at `0.dp` and never negative, which matters near the top and bottom where the chord is small.
 *
 * @param yFraction the **centre** of the band, as a fraction of the display width measured down from
 *   the top of the display box (see the file header for the full convention).
 * @param inset horizontal breathing room taken off **each** side of the chord. Optional; use it for
 *   the bezel reveal or a concentric-margin look, not for ordinary padding - padding inside a
 *   correctly-sized box does not need this.
 * @param bandHeight the height of the band whose centre is [yFraction]. The binding constraint is the
 *   chord at whichever edge of that band sits **further from the centre line** of the display, which
 *   is the narrower of the two chords - so a tall band is correctly reported as narrower than it
 *   would be at its own centre. Leave it at `0.dp` for a single measured line and the centre chord
 *   is returned exactly.
 */
fun safeWidth(
  screenWidth: Dp,
  yFraction: Float,
  inset: Dp = 0.dp,
  bandHeight: Dp = 0.dp,
): Dp {
  if (screenWidth.value <= 0f || !yFraction.isFinite()) return 0.dp
  val halfBandFraction = (bandHeight.value.coerceAtLeast(0f) / 2f) / screenWidth.value
  // Both edges of the band; the narrower chord wins. With bandHeight 0 these are the same line.
  val topFraction = yFraction - halfBandFraction
  val bottomFraction = yFraction + halfBandFraction
  val top = chordWidth(screenWidth, topFraction)
  val bottom = chordWidth(screenWidth, bottomFraction)
  val chord = minOf(top.value, bottom.value)
  val horizontalInset = 2f * inset.value.coerceAtLeast(0f)
  return (chord - horizontalInset).coerceAtLeast(0f).dp
}

/**
 * Caps a text's width at what the display allows on its line: the [modifier] form of [safeWidth].
 *
 * Applies `widthIn(max = ...)` rather than `width(...)`, so short text still sizes to its own content
 * and any surrounding centring keeps working - only the overflow is constrained. Pair it with
 * `maxLines = 1` and `TextOverflow.Ellipsis` and the text ellipsises *before* it reaches the bezel
 * instead of being cut by it.
 *
 * ```kotlin
 * Text(
 *   text = state.exerciseName,
 *   maxLines = 1,
 *   overflow = TextOverflow.Ellipsis,
 *   textAlign = TextAlign.Center,
 *   modifier = Modifier.safeTextWidth(screenWidth = maxWidth, yFraction = F_DY_NAME, inset = 8.dp),
 * )
 * ```
 *
 * @param screenWidth the display's width in dp - the `maxWidth` already available from the screen's
 *   `BoxWithConstraints`.
 * @param yFraction the **centre** of the text's line box, as a fraction of the display width measured
 *   down from the top of the display box. Every geometry constant in this app is already expressed
 *   that way, so a constant that already means "this far down the display" needs no conversion.
 * @param inset see [safeWidth].
 * @param bandHeight the line box's height in dp, if it is known; see [safeWidth].
 */
fun Modifier.safeTextWidth(
  screenWidth: Dp,
  yFraction: Float,
  inset: Dp = 0.dp,
  bandHeight: Dp = 0.dp,
): Modifier = widthIn(max = safeWidth(screenWidth, yFraction, inset, bandHeight))
