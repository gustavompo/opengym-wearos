package ch.duartesantos.opengym.wear.ui.exercise

import ch.duartesantos.opengym.wear.data.LB_PER_KG
import ch.duartesantos.opengym.wear.data.WeightUnit
import ch.duartesantos.opengym.wear.data.WorkoutState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Task 2: the exercise page's numbers in the profile's unit. Pure functions only — the composable
 * itself is covered by the instrumented tests, and `WeightUnitTest` covers the conversion.
 */
class ExercisePageUnitTest {

  private val lb = WeightUnit.Pounds

  private fun state(previousWeightKg: Double?, previousReps: Int?, unit: WeightUnit) =
    WorkoutState(
      isWorkoutActive = true,
      exerciseName = "Lat Pulldown",
      exerciseIndex = 0,
      exerciseCount = 4,
      currentSetIndex = 1,
      totalSets = 4,
      weightKg = 50.0,
      reps = 10,
      previousWeightKg = previousWeightKg,
      previousReps = previousReps,
      weightUnit = unit,
      restSecondsRemaining = 0,
      restSecondsTotal = 90,
    )

  @Test
  fun thePreviousSetHintSpeaksTheProfilesUnit() {
    // 110 lb on the wire is 110 / LB_PER_KG kg.
    assertEquals(
      "prev. 110.0 lb x 10",
      previousSetHint(state(110.0 / LB_PER_KG, 10, WeightUnit.Pounds)),
    )
    // A kg profile is unchanged, to the character.
    assertEquals("prev. 50.0 kg x 10", previousSetHint(state(50.0, 10, WeightUnit.Kilograms)))
  }

  @Test
  fun theNeighbourRowsStepInTheProfilesUnit() {
    // Half a kilogram either side for kg; a whole pound either side for lb.
    assertEquals("50.5", weightNeighbour(50.0, +0.5, WeightUnit.Kilograms))
    assertEquals("109.0", weightNeighbour(110.0, -1.0, WeightUnit.Pounds))
    assertEquals("111.0", weightNeighbour(110.0, +1.0, WeightUnit.Pounds))
  }

  @Test
  fun theNeighbourRowsGoBlankAtTheProfilesOwnCeiling() {
    // 1102.0 lb is the last displayable value: 500 kg is 1102.31 lb.
    assertEquals("1102.0", weightNeighbour(1101.0, +1.0, WeightUnit.Pounds))
    assertNull(weightNeighbour(1102.0, +1.0, WeightUnit.Pounds))
    assertNull(weightNeighbour(500.0, +0.5, WeightUnit.Kilograms))
    // The floor is 0 in both units — a bodyweight set.
    assertEquals("1.0", weightNeighbour(0.0, +1.0, WeightUnit.Pounds))
    assertNull(weightNeighbour(0.0, -1.0, WeightUnit.Pounds))
  }

  @Test
  fun theWheelStepsInTheProfilesUnitAndStopsAtTheCeiling() {
    assertEquals(111.0, wheelWeightDisplay(110.0, +1, WeightUnit.Pounds), 0.0)
    assertEquals(105.0, wheelWeightDisplay(110.0, -5, WeightUnit.Pounds), 0.0)
    // Over-dragging past the end previews the end, not 1103 (it cannot leave the wire's range).
    assertEquals(1102.0, wheelWeightDisplay(1102.0, +4, WeightUnit.Pounds), 0.0)
    assertEquals(0.0, wheelWeightDisplay(0.0, -4, WeightUnit.Pounds), 0.0)
    // The kg path is untouched: same base, same 0.5 step, same 500 ceiling.
    assertEquals(50.5, wheelWeightDisplay(50.0, +1, WeightUnit.Kilograms), 0.0)
    assertEquals(50.0, wheelWeightDisplay(50.0, +1, WeightUnit.Kilograms), 0.5)
  }

  @Test
  fun onePoundStepIsTheValueThePhoneSnapsBackToOnePound() {
    // What the page hands `onAdjustWeight` for one lb detent, and what the phone's own lb lattice
    // does with it (see the fork's `wear-commands.test.js`). Both sides of the same contract.
    val deltaKg = lb.toKg(lb.displayStep)
    assertEquals(1.0 / LB_PER_KG, deltaKg, 1e-12)
    assertEquals(111.0, lb.fromKg((110.0 + deltaKg * LB_PER_KG) / LB_PER_KG), 0.0)
  }
}
