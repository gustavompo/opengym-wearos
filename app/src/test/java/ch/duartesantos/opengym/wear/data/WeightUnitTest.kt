package ch.duartesantos.opengym.wear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 2, the conversion boundary. All of it is pure, so a wrong number here is caught on the JVM
 * and never reaches a device.
 *
 * openGym's own constants, for comparison: `LB_PER_KG = 2.2046226218`, its lb lattice is 0.5 lb
 * and its kg one 0.25 kg (`lib/units.js` `convertWeight`).
 */
class WeightUnitTest {

  private val kg = WeightUnit.Kilograms
  private val lb = WeightUnit.Pounds

  // --- the missing-field rule (requirement 4) ---------------------------------------------------

  @Test
  fun onlyTheExactStringLbIsPounds() {
    assertEquals(lb, WeightUnit.fromWire("lb"))
    assertEquals(kg, WeightUnit.fromWire("kg"))
  }

  @Test
  fun absentNullOrJunkIsKilograms() {
    assertEquals(kg, WeightUnit.fromWire(null))
    assertEquals(kg, WeightUnit.fromWire(""))
    assertEquals(kg, WeightUnit.fromWire("LB"))
    assertEquals(kg, WeightUnit.fromWire("pounds"))
    assertEquals(kg, WeightUnit.fromWire("lbs"))
    assertEquals(kg, WeightUnit.fromWire("{\"unit\":\"lb\"}"))
  }

  @Test
  fun theDefaultStateIsAKgState() {
    assertEquals(kg, WorkoutState.None.weightUnit)
    assertEquals(WeightUnit.Kilograms, WeightUnit.Kilograms)
  }

  // --- kg is untouched --------------------------------------------------------------------------

  @Test
  fun akilogramProfileRoundsToTheWatchsOwnHalfKilogramGrid() {
    assertEquals(50.0, kg.fromKg(50.0), 0.0)
    assertEquals(49.5, kg.fromKg(49.5), 0.0)
    assertEquals(50.0, kg.fromKg(50.000000000000004), 0.0)
    assertEquals(50.5, kg.fromKg(50.3), 0.0)
    assertEquals(49.5, kg.fromKg(49.7), 0.0)
  }

  @Test
  fun akilogramDisplayRoundTripsExactly() {
    for (value in listOf(0.0, 0.5, 50.0, 62.5, 500.0)) {
      assertEquals(value, kg.fromKg(kg.toKg(value)), 0.0)
    }
  }

  @Test
  fun theDisplayStepIsUnchangedForKilograms() {
    assertEquals(0.5, kg.displayStep, 0.0)
    assertEquals("kg", kg.label)
    assertEquals("kg", kg.wireName)
    assertEquals("kg", kg.hintLabel)
    assertEquals(500.0, kg.maxDisplay, 0.0)
  }

  // --- lb ---------------------------------------------------------------------------------------

  @Test
  fun poundsAreTheStoredNumberOnAnLbProfile() {
    // openGym stores the profile's own unit, so 110 lb is stored as 110 and must display as 110.
    assertEquals(110.0, lb.fromKg(110.0 / LB_PER_KG), 0.0)
    assertEquals(110.5, lb.fromKg(110.5 / LB_PER_KG), 0.0)
    assertEquals(45.0, lb.fromKg(45.0 / LB_PER_KG), 0.0)
  }

  @Test
  fun anLbDisplayRoundTripsExactly() {
    for (value in listOf(0.0, 0.5, 45.0, 110.0, 111.5, 1102.0)) {
      assertEquals(value, lb.fromKg(lb.toKg(value)), 1e-9)
    }
  }

  @Test
  fun poundsSnapToOpenGymsOwnHalfPoundLattice() {
    // 110.2 lb is not a value openGym can store (it rounds lb to the nearest 0.5), so the display
    // must not invent one.
    assertEquals(110.0, lb.fromKg(110.2 / LB_PER_KG), 0.0)
    assertEquals(110.5, lb.fromKg(110.3 / LB_PER_KG), 0.0)
  }

  @Test
  fun poundsStepIsACleanWholePound() {
    assertEquals(1.0, lb.displayStep, 0.0)
    assertEquals("lb", lb.label)
    assertEquals("lb", lb.wireName)
    assertEquals("lb", lb.hintLabel)
  }

  @Test
  fun thePoundsCeilingIsOnTheLatticeAndInsideTheWireBound() {
    // 500 kg is 1102.31 lb; the largest whole half-pound under it is 1102.0.
    assertEquals(1102.0, lb.maxDisplay, 0.0)
    assertTrue(lb.maxDisplay * (1.0 / LB_PER_KG) <= MAX_WEIGHT_KG + 1e-9)
    assertEquals(1102.0, lb.clampDisplay(9999.0), 0.0)
    assertEquals(0.0, lb.clampDisplay(-5.0), 0.0)
  }

  @Test
  fun aPoundStepIsLessThanHalfAKilogram() {
    // 1 lb = 0.45359237 kg, which the phone's old 0.5 kg snap would have eaten — the reason the
    // phone's snap had to move into the profile's unit.
    val onePoundKg = lb.toKg(1.0)
    assertEquals(0.45359237, onePoundKg, 1e-8)
    assertTrue(onePoundKg < 0.5)
  }
}
