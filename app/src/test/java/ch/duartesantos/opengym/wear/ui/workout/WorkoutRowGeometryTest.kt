package ch.duartesantos.opengym.wear.ui.workout

import androidx.compose.ui.unit.dp
import ch.duartesantos.opengym.wear.ui.safeWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the sign and stride conventions of [rowCentreYFraction] — the way `WearGeometryTest` pins
 * yFraction's direction. The function feeds every row's chord cap, so a flipped sign here would put
 * rows under the bezel in one half of the disc; these tests fail on a flipped sign, an off-by-one
 * in the `firstVisibleItemIndex` arithmetic, or a stride that ignores the row gap.
 */
class WorkoutRowGeometryTest {

  // Representative 227 dp screen (the Large Round emulator's width).
  private val displayWidth = 227f
  private val listTop = 102.4f // header on the polished page: name + gap + timer + gap
  private val rowHeight = 66f
  private val rowGap = 6f
  private val stride = rowHeight + rowGap

  private fun centre(rowIndex: Int, firstVisible: Int, offset: Float) =
    rowCentreYFraction(listTop, rowIndex, firstVisible, offset, stride, rowHeight, displayWidth)

  @Test
  fun rowZeroAtRestSitsJustBelowTheHeader() {
    assertEquals((listTop + rowHeight / 2f) / displayWidth, centre(0, 0, 0f), 1e-6f)
  }

  @Test
  fun eachRestingRowIsOneStrideBelowThePrevious() {
    for (i in 0..4) {
      assertEquals((listTop + i * stride + rowHeight / 2f) / displayWidth, centre(i, 0, 0f), 1e-6f)
    }
  }

  @Test
  fun scrollingUpByOneStrideMovesEveryBandUpByExactlyThatStride() {
    // After one full stride of scroll, item 1 is flush at the list top: its band centre must equal
    // (listTop + rowHeight/2), the position row 0 had at rest.
    assertEquals((listTop + rowHeight / 2f) / displayWidth, centre(1, 1, 0f), 1e-6f)
    assertEquals((listTop + stride + rowHeight / 2f) / displayWidth, centre(2, 1, 0f), 1e-6f)
    assertEquals((listTop - stride + rowHeight / 2f) / displayWidth, centre(0, 1, 0f), 1e-6f)
  }

  @Test
  fun aHalfScrolledItemIsHalfAStrideAheadOfItsRestPosition() {
    // Item 0 half out of the viewport top: its centre is half a stride above its rest centre.
    assertEquals((listTop - stride / 2f + rowHeight / 2f) / displayWidth, centre(0, 0, stride / 2f), 1e-6f)
    // Item 1 is then half a stride below where it would be at rest.
    assertEquals((listTop + stride / 2f + rowHeight / 2f) / displayWidth, centre(1, 0, stride / 2f), 1e-6f)
  }

  @Test
  fun twoStridesPlusTenPixelsIsAFirstVisibleOfTwoWithATenPixelOffset() {
    val offset = 10f
    // Item 2 is the first visible, 10 px past the viewport top; item 3 follows a stride later.
    assertEquals((listTop - offset + rowHeight / 2f) / displayWidth, centre(2, 2, offset), 1e-6f)
    assertEquals((listTop + stride - offset + rowHeight / 2f) / displayWidth, centre(3, 2, offset), 1e-6f)
  }

  /**
   * The page's own resting geometry, pinned: with the polished header and the list at rest on its
   * first row, the title's ellipsis budget at the row's band centre must keep the card's interior,
   * so short names never ellipsise and a long name ellipsises at the card, not at the bezel. If the
   * header ever drops back down — or a future agent feeds the rows a yFraction that ignores the
   * header — this fails, which is the point.
   */
  @Test
  fun theRestingRowTitleBudgetKeepsTheCardInterior() {
    val width = 227f
    val capToEm = 0.711f
    val nameFontFraction = (21f / 305f) / capToEm
    val timerFontFraction = (22f / 305f) / capToEm
    val headerTopDp = 38f / 305f * width
    val gapNameTimerDp = 13f / 305f * width
    val gapTimerListDp = 14f / 305f * width
    val nameLineDp = nameFontFraction * width * 1.2f
    val timerLineDp = timerFontFraction * width * 1.2f
    val listTopDp = headerTopDp + nameLineDp + gapNameTimerDp + timerLineDp + gapTimerListDp
    val rowH = 89f / 305f * width
    val strideDp = rowH + 8f / 305f * width

    val y = rowCentreYFraction(listTopDp, 0, 0, 0f, strideDp, rowH, width)
    val cardInteriorDp = width * 264f / 305f - 2f * (13f / 305f * width)
    val budget = safeWidth(width.dp, y)

    assertTrue(
      "resting title budget ${budget.value} dp must keep the card interior's $cardInteriorDp dp",
      budget.value >= cardInteriorDp,
    )
  }
}

/** The page's two string helpers, pinned the way the rest of the data layer is pinned. */
class WorkoutPageHelpersTest {

  @Test
  fun setsLabelSpeaksTheReferencesOwnWording() {
    assertEquals("3/3 Sets", setsLabel(WorkoutRowFixture(0, 3, 3)))
    assertEquals("0/4 Sets", setsLabel(WorkoutRowFixture(0, 0, 4)))
    // A zero-set exercise shows 0/0 rather than a blank line (a row's progress is never missing).
    assertEquals("0/0 Sets", setsLabel(WorkoutRowFixture(0, 0, 0)))
  }

  @Test
  fun theTimerIsMinutesAndSecondsUntilAnHour() {
    assertEquals("00:05", formatElapsed(5))
    assertEquals("45:12", formatElapsed(45 * 60L + 12))
    assertEquals("59:59", formatElapsed(3599))
  }

  @Test
  fun theTimerGainsTheHourFieldPastAnHour() {
    assertEquals("1:00:00", formatElapsed(3600))
    assertEquals("1:05:03", formatElapsed(3600 + 5 * 60L + 3))
    assertEquals("99:59:59", formatElapsed(99 * 3600L + 59 * 60L + 59))
  }

  @Test
  fun aNegativeElapsedNeverRendersNegative() {
    // Contracted to 0 by the helper; WorkoutState.elapsedSeconds answers null before this runs.
    assertEquals("00:00", formatElapsed(-3))
  }

  private fun WorkoutRowFixture(index: Int, done: Int, total: Int) =
    ch.duartesantos.opengym.wear.data.WorkoutRow(index, "exercise", done, total)
}