package ch.duartesantos.opengym.wear.ui.rest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U3: the rest ring depletes from one end only - its right-hand end retreats counterclockwise like a
 * reverse clock - instead of shrinking from both ends toward 12 o'clock.
 *
 * Pure JVM maths, no device and no Compose: [restArcAngles] and [restArcIsDrawable] are the whole of
 * the arc geometry that is not `DrawScope`. The angles are in Compose's convention (0 degrees at
 * 3 o'clock, growing clockwise), so a clock angle is `compose + 90` (0 = 12 o'clock, growing
 * clockwise).
 *
 * The load-bearing assertions are [oneEndpointIsStationary] and
 * [theSymmetricFormulaCannotComeBack]: the old `180(1-f) + 90` start angle depends on `fill`, and
 * those two tests fail the moment it is reintroduced.
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

  // --- the acceptance test ----------------------------------------------------------------------

  /**
   * Acceptance 1 and 2: exactly one endpoint moves. The fixed end's clock angle is identical for
   * every fill (deviation 0, well inside the 1 degree the task allows), and the moving end moves
   * monotonically.
   */
  @Test
  fun oneEndpointIsStationary() {
    val fixed = fills.map(::fixedCapClock)
    val spread = fixed.max() - fixed.min()
    assertTrue("fixed cap must not move: $fixed", spread <= 1f)

    val moving = fills.map(::movingCapClock)
    // As fill decreases the moving cap's clock angle decreases: a reverse clock. It wraps once per
    // rest (a full counterclockwise lap), so compare each step as the clockwise-avoiding delta
    // `(previous - current) mod 360`, which the retreat makes positive and equal to 360 * drop.
    for (i in 1 until moving.size) {
      val delta = (moving[i - 1] - moving[i]).mod(360f)
      val expected = 360f * (fills[i - 1] - fills[i])
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

  /** Acceptance 4: depletion stays linear in the remaining fraction (hence in elapsed time). */
  @Test
  fun theSweepIsLinearInTheRemainingFraction() {
    for (f in fills) {
      assertEquals(360f * f, restArcAngles(f).sweepAngle, 0.001f)
    }
    assertEquals(0f, restArcAngles(0f).sweepAngle, 0.001f)
    assertEquals(360f, restArcAngles(1f).sweepAngle, 0.001f)
    assertEquals(180f, restArcAngles(0.5f).sweepAngle, 0.001f)
  }

  /**
   * Acceptance 3: the geometry matches the reference's own measurement. Notes 01 measured a 266.0
   * degree arc at 89 s remaining, with cap centres at clock 224.98 and 131.00 and a gap centre at
   * 178.0 (5.93 o'clock). The one-sided arc reproduces all three; the symmetric formula is 2 degrees
   * off all three.
   */
  @Test
  fun theReferenceFrameIsReproduced() {
    val fill = 266.0f / 360f
    assertEquals("fixed cap at the reference's left cap", 225.0f, fixedCapClock(fill), 0.5f)
    assertEquals("moving cap at the reference's right cap", 131.0f, movingCapClock(fill), 0.5f)
    assertEquals("gap centred on 5.93 o'clock", 178.0f, gapCentreClock(fill), 0.5f)
  }

  /** Acceptance 2: the moving end is the right-hand one at the reference moment, not the left. */
  @Test
  fun theMovingEndIsTheRightHandOne() {
    val moving = movingCapClock(266.0f / 360f)
    assertTrue("moving cap %s must be on the right (clock < 180)".format(moving), moving < 180f)
    assertTrue("moving cap %s must be below the horizontal".format(moving), moving > 90f)
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
    assertEquals(360f, restArcAngles(1.5f).sweepAngle, 0.001f)
  }
}
