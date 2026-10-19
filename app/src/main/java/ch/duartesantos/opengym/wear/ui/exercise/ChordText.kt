package ch.duartesantos.opengym.wear.ui.exercise

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.wear.compose.material3.Text

/*
 *  — a label that wraps to up to two lines, each line capped at its OWN safe width.
 *
 * Why this exists: a round display's usable width is the circle's chord, and the chord grows as a
 * line moves DOWN the screen. For a two-line label (the exercise title, the rest page's
 * `Next: <name>`) the first line's chord is the binding constraint and the second line's is
 * wider — a single `widthIn(max = first line's chord)` under-serves the second line, and a single
 * `widthIn(max = second line's chord)` lets the first line run under the bezel (measured: the
 * 290 px line of "barbell incline bench press" clipped its top row,). So the
 * two lines get their own caps.
 *
 * The split uses the platform [`TextMeasurer`]: the full string is measured once under the FIRST
 * line's cap, which yields exactly the wrap the rendered first line will have (same text, same
 * style, same width), and the second line is the remainder — rendered under ITS OWN cap. If the
 * string needs more than [maxLines] lines, or fits one, it degrades to a single [Text] with
 * ellipsis. Semantics merge so a screen reader and `onNodeWithText` still see the full string.
 */
@Composable
internal fun ChordWrappedText(
  text: String,
  style: TextStyle,
  color: Color,
  lineSafeWidth: (Int) -> Dp,
  textAlign: TextAlign = TextAlign.Center,
  maxLines: Int = 2,
  modifier: Modifier = Modifier,
) {
  val measurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val firstLineCap = lineSafeWidth(0)
  val fullText = text
  val measured =
    remember(text, style, firstLineCap, density) {
      measurer.measure(
        AnnotatedString(fullText),
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        constraints = Constraints(maxWidth = with(density) { firstLineCap.roundToPx() }),
      )
    }

  // One line, or something that will ellipsise anyway — a single Text is the honest render.
  if (measured.lineCount <= 1 || measured.lineCount > maxLines) {
    Text(
      text = text,
      style = style,
      color = color,
      maxLines = maxLines,
      overflow = TextOverflow.Ellipsis,
      textAlign = textAlign,
      modifier = modifier.widthIn(max = firstLineCap),
    )
    return
  }

  val endFirst = measured.getLineEnd(0, visibleEnd = true)
  val first = text.substring(0, endFirst)
  val second = text.substring(endFirst).trimStart()

  // One merged semantics node carrying the full string, so the caller's text matchers and the
  // screen reader see "the label", not two fragments. (Indexed assignment: the DSL form `text =`
  // would shadow this composable's own `text` parameter.)
  val merged = modifier.semantics(mergeDescendants = true) {
    this[SemanticsProperties.Text] = listOf(AnnotatedString(fullText))
  }

  if (second.isEmpty()) {
    Text(
      text = first,
      style = style,
      color = color,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = textAlign,
      modifier = merged.widthIn(max = firstLineCap),
    )
    return
  }

  Column(modifier = merged, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      text = first,
      style = style,
      color = color,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = textAlign,
      modifier = Modifier.widthIn(max = lineSafeWidth(0)),
    )
    Text(
      text = second,
      style = style,
      color = color,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = textAlign,
      modifier = Modifier.widthIn(max = lineSafeWidth(1)),
    )
  }
}