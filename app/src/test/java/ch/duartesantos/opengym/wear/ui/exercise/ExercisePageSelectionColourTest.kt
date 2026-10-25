package ch.duartesantos.opengym.wear.ui.exercise

import ch.duartesantos.opengym.wear.theme.OpenGymBlue
import ch.duartesantos.opengym.wear.theme.OpenGymMuted
import ch.duartesantos.opengym.wear.theme.OpenGymWhite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * the `kg` / `reps` label and value colours are pure functions of the rotary target, so the
 * colour a user sees can never disagree with the column the crown is turning. These are the JVM
 * half; the instrumented [ExercisePageSelectionColourTest] reads the actual pixels.
 *
 * `OpenGymBlue` is `#FF1D82EA` = (29,130,234), measured from the reference; `OpenGymMuted` is
 * `#FFAAAAAA` = (170,170,170).
 */
class ExercisePageSelectionColourTest {

  @Test
  fun theSelectedColumnWearsTheAccent() {
    assertEquals(OpenGymBlue, selectorLabelColor(RotaryTarget.Weight, RotaryTarget.Weight))
    assertEquals(OpenGymBlue, selectorLabelColor(RotaryTarget.Reps, RotaryTarget.Reps))
  }

  @Test
  fun theUnselectedColumnIsDeEmphasised() {
    assertEquals(OpenGymMuted, selectorLabelColor(RotaryTarget.Weight, RotaryTarget.Reps))
    assertEquals(OpenGymMuted, selectorLabelColor(RotaryTarget.Reps, RotaryTarget.Weight))
  }

  @Test
  fun exactlyOneColumnIsAccentedAtATime() {
    for (selected in RotaryTarget.entries) {
      val accented =
        RotaryTarget.entries.filter { selectorLabelColor(it, selected) == OpenGymBlue }
      assertEquals(listOf(selected), accented)
    }
  }

  @Test
  fun theWeightColumnStartsSelectedSoKgStartsBlue() {
    val initial = RotaryTarget.Weight
    assertEquals(OpenGymBlue, selectorLabelColor(RotaryTarget.Weight, initial))
    assertEquals(OpenGymMuted, selectorLabelColor(RotaryTarget.Reps, initial))
  }

  /**
   * The old behaviour must not come back silently: `kg` used to be [OpenGymWhite] whatever the
   * crown was doing, which is indistinguishable from "unselected" only because the user asked for
   * a muted unselected state and white is not muted.
   */
  @Test
  fun theLabelIsNeverTheOldStaticWhite() {
    for (selected in RotaryTarget.entries) {
      for (column in RotaryTarget.entries) {
        assertNotEquals(OpenGymWhite, selectorLabelColor(column, selected))
      }
    }
  }

  /**
   * 2026-10-25: the values used to keep the reference's static colours (weight white, reps blue)
   * whatever was selected, so tapping a card recoloured only its label. The value now follows the
   * same target as the label - accent when selected, white when idle.
   */
  @Test
  fun theSelectedValueWearsTheAccent() {
    assertEquals(OpenGymBlue, selectorValueColor(RotaryTarget.Weight, RotaryTarget.Weight))
    assertEquals(OpenGymBlue, selectorValueColor(RotaryTarget.Reps, RotaryTarget.Reps))
  }

  @Test
  fun theIdleValueStaysWhite() {
    assertEquals(OpenGymWhite, selectorValueColor(RotaryTarget.Weight, RotaryTarget.Reps))
    assertEquals(OpenGymWhite, selectorValueColor(RotaryTarget.Reps, RotaryTarget.Weight))
  }

  @Test
  fun exactlyOneValueIsAccentedAtATime() {
    for (selected in RotaryTarget.entries) {
      val accented =
        RotaryTarget.entries.filter { selectorValueColor(it, selected) == OpenGymBlue }
      assertEquals(listOf(selected), accented)
    }
  }
}
