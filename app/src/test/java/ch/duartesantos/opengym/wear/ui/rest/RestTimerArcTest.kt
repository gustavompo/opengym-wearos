package ch.duartesantos.opengym.wear.ui.rest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U3: the rest ring depletes from one end only - its right-hand end retreats counterclockwise like a
 * reverse clock - instead of shrinking from both ends toward 12 o'clock.
 *
 * U4: the ring no longer closes. Even at a full rest the two ends stay [REST_ARC_GAP_DEGREES] apart
 * and straddle clock 180 (6 o'clock), so the bottom of the circle is never inked.
 *
 * Pure JVM maths, no device and no Compose: [restArcAngles] and [restArcIsDrawable] are the whole of
 * the arc geometry that is not `DrawScope`. The angles are in Compose's convention (0 degrees at
 * 3 o'clock, growing clockwise), so a clock angle is `compose + 90` (0 = 12 o'clock, growing
 * clockwise).
 *
 * The load-bearing assertions are [oneEndpointIsStationary] and
 * [theSymmetricFormulaCannotComeBack]: the old `180(1-f) + 90` start angle depends on `fill`, and
 * those two tests fail the moment it is reintroduced.
 * [theReferenceFrameIsDeliberatelyOverridden] is the other load-bearing one: it fails if the ring
 * closes again at clock 225.
 */
class RestTimerArcTest {

  /** Fills from a full ring down to the near-empty sliver. */
  private val fills =
    listOf(1f, 0.9f, 0.75f, 0.5f, 0.25f, 0.1f, 0.01f)

  private fun clockOf(composeDegrees: Float): Float = (composeDegrees + 90f).mod(360f)

  /** Clock angle of the end that is supposed to move. */
  private fun movingCapClock(fill: Float): Float {
    val a = restArcAngles(fill)
    return clockOf(a.startAngle + a.sweepAngle)
  }

  /** Clock angle of the end that is supposed to stay put. */
  private fun fixedCapClock(fill: Float): Float = clockOf(restArcAngles(fill).startAngle)

  /** Clock angle of the midpoint of the gap (the complement of the swept arc). */
  private fun gapCentreClock(fill: Float): Float {
    val sweep = restArcAngles(fill).sweepAngle
    return (movingCapClock(fill) + (360f - sweep) / 2f).mod(360f)
  }

  /** Clock angle of the start of the gap, i.e. the moving cap, read as the gap's clockwise-in edge. */
  private fun gapWidth(fill: Float): Float = 360f - restArcAngles(fill).sweepAngle

  /** True when the clock angle [clock] falls in the undrawn opening for [fill]. */
  private fun isInGap(fill: Float, clock: Float): Boolean {
    val from = movingCapClock(fill)
    val width = gapWidth(fill)
    return (clock - from).mod(360f) <= width
  }

  // --- the acceptance test ----------------------------------------------------------------------

  /**
   * Acceptance 1 and 2: exactly one endpoint moves. The fixed end's clock angle is identical for
   * every fill (deviation 0, well inside the 1 degree the task allows), and the moving end moves
   * monotonically.
   *
   * The expected step is `REST_ARC_SWEEP_DEGREES * drop in fill`, not `360 * drop`: U4 reduced the
   * sweep budget from a full turn to a turn less the bottom opening, so the moving cap retreats 8
   * degrees less per rest than it did before. The direction and the linearity are unchanged, which
   * is what this test is for; the constant changed on purpose.
   */
  @Test
  fun oneEndpointIsStationary() {
    val fixed = fills.map(::fixedCapClock)
    val spread = fixed.max() - fixed.min()
    assertTrue("fixed cap must not move: $fixed", spread <= 1f)

    val moving = fills.map(::movingCapClock)
    // As fill decreases the moving cap's clock angle decreases: a reverse clock. It wraps once per
    // rest (a full counterclockwise lap), so compare each step as the clockwise-avoiding delta
    // `(previous - current) mod 360`, which the retreat makes positive and equal to the sweep drop.
    for (i in 1 until moving.size) {
      val delta = (moving[i - 1] - moving[i]).mod(360f)
      val expected = REST_ARC_SWEEP_DEGREES * (fills[i - 1] - fills[i])
      assertEquals(
        "moving cap must retreat counterclockwise; step $i of $moving",
        expected,
        delta,
        0.01f,
      )
    }
  }

  /**
   * Acceptance 2, stated structurally: the start angle does not depend on the fill, which is the one
   * property the symmetric formula lacked. The previous code's start angle was
   * `180(1-f) + 90` (90, 112.5, 135, 180, 225, ... for this fill list) and this test fails for it.
   */
  @Test
  fun theSymmetricFormulaCannotComeBack() {
    val starts = fills.map { restArcAngles(it).startAngle }
    assertEquals("start angle must be constant, not a function of fill: $starts", 1, starts.distinct().size)
    assertEquals(REST_ARC_START_ANGLE, restArcAngles(0.5f).startAngle, 0.001f)
    // Explicitly the value the old formula produced at f = 0.5, so the regression is pinned by a
    // number a reader can check: 180(1-0.5) + 90 = 180.
    assertTrue(
      "the symmetric 180(1-f)+90 start angle must not return",
      kotlin.math.abs(restArcAngles(0.5f).startAngle - 180f) > 1f,
    )
  }

  /**
   * U4's whole point, and the test a reader should check the change against: the opening exists at
   * **every** fill, is exactly [REST_ARC_GAP_DEGREES] wide when the rest starts, is symmetric about
   * clock 180 at that moment, and always covers the bottom of the circle.
   */
  @Test
  fun theBottomGapIsPermanentAndSymmetricAboutSixOClock() {
    for (f in fills) {
      val width = gapWidth(f)
      assertTrue("gap must never be closed at fill $f: $width", width >= REST_ARC_GAP_DEGREES - 0.001f)
      assertTrue("clock 180 (6 o'clock) must be inside the gap at fill $f", isInGap(f, 180f))
    }
    // At a full rest the two caps are the gap apart and straddle the bottom: clock 176 and 184.
    assertEquals("fixed cap at a full rest", 184f, fixedCapClock(1f), 0.001f)
    assertEquals("moving cap at a full rest", 176f, movingCapClock(1f), 0.001f)
    assertEquals("gap at a full rest", REST_ARC_GAP_DEGREES, gapWidth(1f), 0.001f)
    assertEquals("full-rest gap centre", 180f, gapCentreClock(1f), 0.001f)
    // The two ends are 180 -+ gap/2, i.e. symmetric about the bottom.
    assertEquals(
      "gap must straddle the bottom symmetrically",
      2f * (180f - movingCapClock(1f)),
      gapWidth(1f),
      0.001f,
    )
  }

  /** Acceptance 4: depletion stays linear in the remaining fraction (hence in elapsed time). */
  @Test
  fun theSweepIsLinearInTheRemainingFraction() {
    for (f in fills) {
      // U4: the budget is a turn less the bottom opening - 352, not 360.
      assertEquals(REST_ARC_SWEEP_DEGREES * f, restArcAngles(f).sweepAngle, 0.001f)
    }
    assertEquals(0f, restArcAngles(0f).sweepAngle, 0.001f)
    assertEquals(352f, restArcAngles(1f).sweepAngle, 0.001f)
    assertEquals(176f, restArcAngles(0.5f).sweepAngle, 0.001f)
  }

  /**
   * U4 **overrides the reference frame**, and this test is where that is pinned.
   *
   * Notes 01 measured the reference screenshot at 89 s remaining with cap centres at clock 224.98
   * and 131.00 and a gap centre of 178.0 (5.93 o'clock); set the fixed cap to 225.0 clock =
   * 135 Compose to reproduce it, which the old `theReferenceFrameIsReproduced` asserted. Gus has
   * since asked for a gap at the **bottom**, which is incompatible with a seam at 7:30, so the
   * reference is deliberately dropped: the fixed cap is now clock 184 = Compose 94, and the
   * assertion that it is 225 is inverted here rather than deleted. If someone re-fits the ring to
   * the screenshot, this test fails and says why.
   */
  @Test
  fun theReferenceFrameIsDeliberatelyOverridden() {
    assertEquals("fixed cap is the bottom-gap side, clock 184", 184f, fixedCapClock(1f), 0.001f)
    assertTrue(
      "the reference's 225 fixed cap must NOT be in force: the opening moved to the bottom",
      kotlin.math.abs(fixedCapClock(1f) - 225f) > 1f,
    )
    // And the override is the request, not an accident: the gap is centred on the bottom at t=0.
    assertEquals("the opening is centred on 6 o'clock, not on the reference's 5.93", 180f, gapCentreClock(1f), 0.001f)
  }

  /** Acceptance 2: the moving end is the right-hand one while the rest runs, not the left. */
  @Test
  fun theMovingEndIsTheRightHandOne() {
    // Half way through the rest the moving cap is at clock 184 + 176 = 360 = 0, i.e. 12 o'clock;
    // at the note-01 reference moment (266/360 of the rest) it is at clock 88. Either way it is the
    // right-hand end of the arc, which is the end the reference pair 224.98/131.00 has moving.
    val moving = movingCapClock(266.0f / 360f)
    assertTrue("moving cap %s must be on the right (clock < 180)".format(moving), moving < 180f)
    assertTrue("moving cap %s must be inside the right half".format(moving), moving > 0f)
    // The fixed end stays on the left-hand side of the bottom throughout.
    for (f in fills) {
      assertTrue("fixed cap %s must stay left of the bottom".format(fixedCapClock(f)), fixedCapClock(f) > 180f)
    }
  }

  /** Acceptance 4: the near-zero threshold is retained, and is applied to the fill. */
  @Test
  fun theNearZeroThresholdIsKept() {
    assertTrue(restArcIsDrawable(1f))
    assertTrue(restArcIsDrawable(0.007f))
    assertTrue(!restArcIsDrawable(0.006f))
    assertTrue(!restArcIsDrawable(0f))
  }

  /** Clamping: out-of-range fills cannot produce a negative or over-full sweep. */
  @Test
  fun fillsAreClamped() {
    assertEquals(0f, restArcAngles(-0.5f).sweepAngle, 0.001f)
    assertEquals(REST_ARC_SWEEP_DEGREES, restArcAngles(1.5f).sweepAngle, 0.001f)
  }
}
