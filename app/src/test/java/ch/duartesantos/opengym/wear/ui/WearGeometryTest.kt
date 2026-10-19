package ch.duartesantos.opengym.wear.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The circle-aware width maths, pinned in both directions.
 *
 * These are plain JVM tests: `WearGeometry.kt` is pure `Dp` arithmetic with no Compose runtime and no
 * Android, so the convention can be established here instead of on a device. The one thing these
 * cannot cover is the `Modifier.safeTextWidth` wrapper — it is a single `widthIn(max = …)` call over
 * [safeWidth], which is what everything below tests.
 *
 * The measurement that matters: on the emulator (454 px @ 320 dpi = 227 dp) and on Gus's real watch
 * (450 px @ 320 dpi = 225 dp), text laid out to a percentage of the width is clipped by the bezel
 * above and below the middle of the screen. `chordWidth(W, 0.1)` is 0.60 W, not W.
 */
class WearGeometryTest {

  /** The emulator's display, in dp: 454 px @ 320 dpi. The reference device here. */
  private val w = 227.dp

  /** A real round watch a hair wider than the emulator, to prove nothing is hard-coded to 227. */
  private val watch = 225.dp

  private val epsilon = 1e-3f

  // --- the convention itself -------------------------------------------------

  /**
   * **This test is the one that fails if the convention is inverted.** It pins both ends of the axis
   * at once: 0.0 is the *top* edge (no usable width) and 0.5 is the *centre line* (the full
   * diameter). Flip the sign of `2y - 1` and the first two assertions still pass, but write
   * `yFraction` as an angle-from-the-centre, or read `0` as the centre, and one of them does not.
   */
  @Test
  fun topEdgeHasNoUsableWidthAndTheCentreLineHasAllOfIt() {
    assertEquals(0f, chordWidth(w, 0f).value, epsilon)
    assertEquals(0f, chordWidth(w, 1f).value, epsilon)
    assertEquals(w.value, chordWidth(w, 0.5f).value, epsilon)
    // Not the diameter anywhere but the centre line — the bug this file exists to kill.
    assertTrue(chordWidth(w, 0.25f) < w)
  }

  @Test
  fun usableWidthGrowsMonotonicallyFromTheTopEdgeToTheCentreLine() {
    val ys = listOf(0.01f, 0.05f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f)
    val widths = ys.map { chordWidth(w, it).value }
    widths.zipWithNext().forEach { (narrower, wider) ->
      assertTrue("expected $widths to increase, got $narrower then $wider", narrower < wider)
    }
  }

  @Test
  fun theChordIsTheSameDistanceAboveAndBelowTheCentreLine() {
    listOf(0f, 0.05f, 0.1f, 0.25f, 0.4f).forEach { offset ->
      assertEquals(
        chordWidth(w, 0.5f - offset).value,
        chordWidth(w, 0.5f + offset).value,
        epsilon,
      )
    }
  }

  // --- the arithmetic --------------------------------------------------------

  @Test
  fun chordWidthMatchesTheHandComputedCircleAtKnownHeights() {
    // fraction = sqrt(1 - (2y - 1)^2)
    assertEquals(136.2f, chordWidth(w, 0.1f).value, epsilon) // sqrt(0.36) = 0.6
    assertEquals(196.5878f, chordWidth(w, 0.25f).value, epsilon) // sqrt(0.75) = 0.8660254
    assertEquals(98.9470f, chordWidth(w, 0.05f).value, epsilon) // sqrt(0.19) = 0.4358899
    assertEquals(222.4137f, chordWidth(w, 0.4f).value, epsilon) // sqrt(0.96) = 0.9797959
    // A different diameter, so nothing is pinned to 227 by accident.
    assertEquals(135.0f, chordWidth(watch, 0.1f).value, epsilon)
  }

  @Test
  fun theTopTenthOfTheScreenLosesFortyPercentOfTheDiameter() {
    // This is the size of the clipping Gus reported, expressed as a number.
    assertEquals(0.6f, chordWidth(w, 0.1f).value / w.value, epsilon)
    assertEquals(0.4359f, chordWidth(w, 0.05f).value / w.value, epsilon)
  }

  // --- safeWidth: the inset --------------------------------------------------

  @Test
  fun insetIsTakenOffEachSideOfTheChord() {
    assertEquals(211f, safeWidth(w, 0.5f, inset = 8.dp).value, epsilon) // 227 - 2*8
    assertEquals(120.2f, safeWidth(w, 0.1f, inset = 8.dp).value, epsilon) // 136.2 - 16
    assertEquals(w.value, safeWidth(w, 0.5f).value, epsilon) // no inset, no change
  }

  @Test
  fun anInsetWiderThanTheChordClampsToZeroRatherThanGoingNegative() {
    assertEquals(0f, safeWidth(w, 0.02f, inset = 100.dp).value, epsilon)
    assertEquals(0f, safeWidth(w, 0.5f, inset = 200.dp).value, epsilon)
  }

  @Test
  fun aNegativeInsetIsClampedRatherThanWideningTheLine() {
    assertEquals(w.value, safeWidth(w, 0.5f, inset = (-8).dp).value, epsilon)
  }

  // --- safeWidth: the band height --------------------------------------------

  @Test
  fun bandHeightZeroIsExactlyTheCentreChord() {
    listOf(0.05f, 0.1f, 0.3f, 0.5f, 0.7f).forEach { y ->
      assertEquals(chordWidth(w, y).value, safeWidth(w, y, bandHeight = 0.dp).value, epsilon)
      assertEquals(chordWidth(w, y).value, safeWidth(w, y).value, epsilon)
    }
  }

  @Test
  fun aTallBandIsConstrainedByItsNarrowerEdgeNotItsCentre() {
    // A 40 dp line box centred on the centre line reaches 20 dp above it, where the chord is
    // sqrt(1 - (40/227)^2) = ... of the diameter. Narrower than the centre chord, and symmetric.
    val expected = chordWidth(w, 0.5f - 20f / w.value)
    assertEquals(expected.value, safeWidth(w, 0.5f, bandHeight = 40.dp).value, epsilon)
    assertTrue(safeWidth(w, 0.5f, bandHeight = 40.dp) < chordWidth(w, 0.5f))
    // Same answer for a band whose centre is above the middle, and for one below it.
    assertEquals(
      safeWidth(w, 0.4f, bandHeight = 40.dp).value,
      safeWidth(w, 0.6f, bandHeight = 40.dp).value,
      epsilon,
    )
  }

  @Test
  fun aBandTallerThanTheDisplayHasNoUsableWidth() {
    assertEquals(0f, safeWidth(w, 0.5f, bandHeight = 227.dp).value, epsilon)
    assertEquals(0f, safeWidth(w, 0.5f, bandHeight = 400.dp).value, epsilon)
  }

  @Test
  fun bandHeightAndInsetApplyTogether() {
    val expected = chordWidth(w, 0.5f - 20f / w.value).value - 16f
    assertEquals(expected, safeWidth(w, 0.5f, inset = 8.dp, bandHeight = 40.dp).value, epsilon)
  }

  @Test
  fun aNegativeBandHeightIsClampedToZero() {
    assertEquals(w.value, safeWidth(w, 0.5f, bandHeight = (-40).dp).value, epsilon)
  }

  // --- defined behaviour on nonsense ----------------------------------------

  @Test
  fun outOfRangeAndNonFiniteInputsReturnZeroInsteadOfThrowing() {
    assertEquals(0f, chordWidth(w, -0.5f).value, epsilon)
    assertEquals(0f, chordWidth(w, 1.5f).value, epsilon)
    assertEquals(0f, chordWidth(w, Float.NaN).value, epsilon)
    assertEquals(0f, chordWidth(w, Float.POSITIVE_INFINITY).value, epsilon)
    assertEquals(0f, chordWidth(w, Float.NEGATIVE_INFINITY).value, epsilon)
    assertEquals(0f, chordWidth(0.dp, 0.5f).value, epsilon)
    assertEquals(0f, chordWidth((-100).dp, 0.5f).value, epsilon)
    assertEquals(0f, safeWidth(w, Float.NaN).value, epsilon)
    assertEquals(0f, safeWidth(0.dp, 0.5f).value, epsilon)
  }

  @Test
  fun theResultNeverExceedsTheDisplayItWasGiven() {
    val ys = generateSequence(-0.2f) { it + 0.05f }.takeWhile { it <= 1.2f }.toList()
    listOf<Dp>(w, watch, 2270.dp).forEach { screen ->
      ys.forEach { y ->
        val one = chordWidth(screen, y)
        val safe = safeWidth(screen, y, inset = 8.dp, bandHeight = 24.dp)
        assertTrue("chordWidth($screen, $y) = $one exceeds the display", one <= screen)
        assertTrue("safeWidth($screen, $y) = $safe is negative", safe >= 0.dp)
        assertTrue("safeWidth($screen, $y) = $safe exceeds the chord", safe <= one)
      }
    }
  }

  /**
   * The measurement of section 5 of ``, turned into an
   * assertion. The exercise page's title ink was measured on the emulator at rows y 42..67 of a 454 px
   * display, so its band centre is yFraction 0.1200 and its ink band is 26 px (13 dp) tall.
   *
   * `ExercisePage` lays that text out with `maxWidth` = the whole 454 px square (measured: the title
   * box is the full display width). This is the budget it may actually have.
   */
  @Test
  fun theExerciseTitleHasOnlyFiftyEightPercentOfTheDiameterToWorkWith() {
    val titleBand = 0.12f
    val usable = safeWidth(w, titleBand, inset = 0.dp, bandHeight = 13.dp)
    assertEquals(130.81f, usable.value, epsilon)
    assertEquals(0.5763f, usable.value / w.value, epsilon)
    // i.e. the page offers 227 dp where the display shows 130.9 dp, and 96.1 dp of a long name is
    // laid out where nobody can see it. The title's own chord at the top row of its ink box:
    assertEquals(131.5f, chordWidth(w, 42f / 454f).value, 0.1f)
    // With a per-side inset it is still comfortably more than half the diameter, so the fix does not
    // need the screen agents to shrink the type.
    assertTrue(safeWidth(w, titleBand, inset = 8.dp, bandHeight = 13.dp) > w * 0.5f)
  }
}
