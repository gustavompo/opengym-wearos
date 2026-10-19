package ch.duartesantos.opengym.wear.ui.exercise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * the drag wheel's arithmetic and its release rule, as plain JVM tests.
 *
 * Every one of these drives the **same** functions the composable calls — [wheelSteps],
 * [wheelWeightKg], [wheelReps] and [wheelGesture] — because the two things that can silently go
 * wrong here are arithmetic (a wheel that moves one step per half row, or past its clamp) and the
 * release rule (a command per drag event instead of one per drag). Neither needs a device, so
 * neither is left to the instrumented suite.
 *
 * A row is `cardHeight / 3`; on the 454 px emulator that is 45.9 px. The tests use a round 40 px so
 * the numbers in them are checkable by hand.
 */
class ExercisePageWheelTest {

  private val row = 40f

  @Test
  fun oneRowOfTravelIsOneStep() {
    // A finger moving UP is a step UP in value: the wheel's content follows the finger, so the row
    // that was below moves into the middle.
    assertEquals(1, wheelSteps(-row, row))
    assertEquals(2, wheelSteps(-2 * row, row))
    assertEquals(-1, wheelSteps(+row, row))
    assertEquals(-2, wheelSteps(+2 * row, row))
  }

  @Test
  fun theStepHappensAtTheHalfRowNotAtTheWholeRow() {
    assertEquals(0, wheelSteps(0f, row))
    assertEquals(0, wheelSteps(-19f, row)) // 0.475 rows up: still the same value
    assertEquals(1, wheelSteps(-20f, row)) // 0.5 rows up: the nearest value is the next one
    assertEquals(0, wheelSteps(+19f, row)) // and the same the other way
    assertEquals(-1, wheelSteps(+20f, row))
    assertEquals(1, wheelSteps(-59f, row)) // 1.475 rows up: one step
    assertEquals(2, wheelSteps(-79f, row))
    assertEquals(2, wheelSteps(-80f, row))
    // Not Kotlin's `round` (which rounds ties to even and would sleep through 2.5 rows):
    assertEquals(3, wheelSteps(-2.5f * row, row))
  }

  @Test
  fun stepsAccumulateAcrossUnevenDragEvents() {
    // A real drag arrives as many small deltas, not one big one. Six of them, 80 px total.
    val total = listOf(4f, 9f, 12f, 15f, 20f, 20f).sum()
    assertEquals(80f, total, 0f)
    assertEquals(2, wheelSteps(-total, row))
    // And a drag that wanders returns to zero rather than latching.
    assertEquals(0, wheelSteps(-total + total, row))
  }

  @Test
  fun aZeroRowHeightIsNotADivisionByZero() {
    assertEquals(0, wheelSteps(-123f, 0f))
  }

  @Test
  fun thePreviewUsesTheRepositorysOwnGridAndClamp() {
    // 0.5 kg per step, exactly like `adjustWeight`: 50.0 + 4 steps = 52.0.
    assertEquals(52.0, wheelWeightKg(50.0, 4), 0.0)
    assertEquals(51.0, wheelWeightKg(50.0, 2), 0.0)
    assertEquals(49.5, wheelWeightKg(50.0, -1), 0.0)
    // The ends of the range clamp instead of running away, which is what makes an over-drag past
    // an end harmless.
    assertEquals(0.0, wheelWeightKg(0.0, -3), 0.0)
    assertEquals(0.5, wheelWeightKg(0.0, 1), 0.0)
    assertEquals(500.0, wheelWeightKg(500.0, 3), 0.0)
  }

  @Test
  fun theRepsPreviewIsOnePerStepAndClamped() {
    assertEquals(12, wheelReps(10, 2))
    assertEquals(8, wheelReps(10, -2))
    assertEquals(99, wheelReps(10, 500))
    assertEquals(1, wheelReps(10, -500))
  }

  @Test
  fun aWheelDragPreviewsFromTheValueTheFingerStartedOn() {
    val drag = WheelDrag(base = 50.0, dragPx = -4 * row)
    assertEquals(4, drag.steps(row))
    // Four rows of travel is four steps, and a weight step is 0.5 kg: 50.0 + 4 x 0.5 = 52.0.
    assertEquals(52.0, drag.weightKg(row), 0.0)
    assertEquals(14, WheelDrag(base = 10.0, dragPx = -4 * row).reps(row))
  }

  /**
   * The release rule, driven through [wheelGesture] itself: a four-row drag commits **one**
   * absolute value, the final one, and nothing else.
   */
  @Test
  fun aMultiRowDragCommitsExactlyOneAbsoluteValue() {
    val commits = mutableListOf<Double>()
    var drag: WheelDrag? = null
    val gesture = weightGesture(base = 50.0, commits = commits, current = { drag }, set = { drag = it })

    gesture.onStart()
    listOf(4f, 9f, 12f, 15f, 20f, 20f).forEach { gesture.onDragBy(-it) }
    assertEquals(2, drag!!.steps(row))
    assertEquals(51.0, drag!!.weightKg(row), 0.0)
    assertEquals("no command while the finger is down", 0, commits.size)

    gesture.onEnd()
    assertEquals(listOf(51.0), commits)
    assertNull("the preview is released back to the projection", drag)
  }

  @Test
  fun aDragThatReturnsToItsStartCommitsNothing() {
    val commits = mutableListOf<Double>()
    var drag: WheelDrag? = null
    val gesture = weightGesture(base = 50.0, commits = commits, current = { drag }, set = { drag = it })

    gesture.onStart()
    gesture.onDragBy(-2 * row)
    gesture.onDragBy(+2 * row)
    gesture.onEnd()

    assertEquals(emptyList<Double>(), commits)
    assertNull(drag)
  }

  @Test
  fun anOverDragPastTheEndAndBackCommitsNothing() {
    val commits = mutableListOf<Double>()
    var drag: WheelDrag? = null
    val gesture = weightGesture(base = 500.0, commits = commits, current = { drag }, set = { drag = it })

    gesture.onStart()
    gesture.onDragBy(-5 * row) // the preview clamps, so the value does not move...
    gesture.onDragBy(+5 * row) // ...and coming back lands on the base
    gesture.onEnd()

    assertEquals(emptyList<Double>(), commits)
  }

  @Test
  fun anOverDragPastTheEndCommitsTheClampedEndExactlyOnce() {
    val commits = mutableListOf<Double>()
    var drag: WheelDrag? = null
    val gesture = weightGesture(base = 499.5, commits = commits, current = { drag }, set = { drag = it })

    gesture.onStart()
    gesture.onDragBy(-5 * row)
    gesture.onEnd()

    assertEquals(listOf(500.0), commits)
  }

  @Test
  fun aCancelledDragCommitsNothing() {
    val commits = mutableListOf<Double>()
    var drag: WheelDrag? = null
    val gesture = weightGesture(base = 50.0, commits = commits, current = { drag }, set = { drag = it })

    gesture.onStart()
    gesture.onDragBy(-2 * row)
    gesture.onCancel()

    assertEquals(emptyList<Double>(), commits)
    assertNull(drag)
  }

  /**
   * A `state` that arrives mid-drag must not re-base the wheel: the drag is measured
   * from the value on screen when the finger landed, so [wheelGesture]'s `base` is read once, at
   * the start, and never again.
   */
  @Test
  fun theBaseIsReadWhenTheDragStartsAndNotAtRelease() {
    val commits = mutableListOf<Double>()
    var drag: WheelDrag? = null
    var projectionWeightKg = 50.0
    val gesture =
      wheelGesture(
        currentDrag = { drag },
        setDrag = { drag = it },
        base = { projectionWeightKg },
        rowHeightPx = row,
        preview = { it.weightKg(row) },
        commit = { commits += it },
      )

    gesture.onStart()
    // The phone pushes a new weight while the finger is down; the base must not move.
    projectionWeightKg = 62.5
    gesture.onDragBy(-2 * row)
    gesture.onEnd()

    assertEquals(listOf(51.0), commits)
  }

  /**
   *  (haptics): [wheelGesture] reports the whole-step count after every drag event — 0 at drag
   * start, then the accumulated steps — so the screen can fire one CLOCK_TICK per step crossed,
   * and absolutely not per pixel.
   */
  @Test
  fun onStepReportsTheWholeStepsEveryDragEventFromZeroAtStart() {
    val seen = mutableListOf<Int>()
    var drag: WheelDrag? = null
    val gesture =
      wheelGesture(
        currentDrag = { drag },
        setDrag = { drag = it },
        base = { 50.0 },
        rowHeightPx = row,
        preview = { it.weightKg(row) },
        commit = {},
        onStep = { seen += it },
      )

    gesture.onStart()
    gesture.onDragBy(-row / 3f) // 0.33 rows: still step 0
    gesture.onDragBy(-row / 3f) // 0.66 rows: step 1
    gesture.onDragBy(-row) // 1.66 rows: step 2
    gesture.onDragBy(+row) // back to 0.66 rows: step 1

    assertEquals(listOf(0, 0, 1, 2, 1), seen)
  }

  @Test
  fun aDragThatStartsWithNoGestureDoesNotCrash() {
    val commits = mutableListOf<Double>()
    val gesture =
      weightGesture(base = 50.0, commits = commits, current = { null }, set = {}, )
    gesture.onDragBy(-2 * row)
    gesture.onEnd()
    gesture.onCancel()
    assertEquals(emptyList<Double>(), commits)
  }

  /** The weight column's release rule, with a fixed 40 px row. */
  private fun weightGesture(
    base: Double,
    commits: MutableList<Double>,
    current: () -> WheelDrag?,
    set: (WheelDrag?) -> Unit,
  ): WheelGesture =
    wheelGesture(
      currentDrag = current,
      setDrag = set,
      base = { base },
      rowHeightPx = row,
      preview = { it.weightKg(row) },
      commit = { commits += it },
    )
}
