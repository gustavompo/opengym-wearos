package ch.duartesantos.opengym.wear.ui.exercise

import ch.duartesantos.opengym.wear.data.WeightUnit
import ch.duartesantos.opengym.wear.data.WEIGHT_STEP_KG
import ch.duartesantos.opengym.wear.data.clampReps
import ch.duartesantos.opengym.wear.data.clampWeightKg

/*
 * M7.2 — the drag wheel's arithmetic, deliberately outside the composable.
 *
 * The wheel is a cylinder: the card shows three values (above / selected / below) over
 * `cardHeight / 3` each, so **one row of travel is one step**. All of this file is pure — no
 * Compose, no dp, no repository — so the pixel -> step conversion, the accumulation and the
 * clamping are unit-testable on the JVM and cannot be broken by a layout change.
 */

/** The card shows exactly three values, so one row of travel is one third of the card. */
internal const val WHEEL_ROWS_PER_CARD = 3f

/**
 * Whole steps for a drag of [dragPx] in a wheel whose row is [rowHeightPx] tall.
 *
 * The sign: the wheel's content follows the finger, so a finger moving **up** (`dragPx` negative)
 * brings the row that was below into the middle, which is one step **up** in value. Therefore
 * `steps = -dragPx / rowHeight`.
 *
 * Rounded rather than truncated, so the step happens at the half row — the value the finger is
 * nearest to is the one shown. That is also what makes a drag that returns to its origin produce
 * zero steps, and therefore no command.
 *
 * The rounding is **half away from zero**, spelled out rather than left to `round`: `kotlin.math.round`
 * rounds ties towards the *even* integer (it is `Math.rint`), so a drag of exactly 2.5 rows would
 * sit still while 2.51 rows moved, and a drag of exactly 0.5 rows up would move while 0.5 rows down
 * would not. This form flips at exactly half a row in both directions, monotonically.
 */
internal fun wheelSteps(dragPx: Float, rowHeightPx: Float): Int {
  if (rowHeightPx <= 0f) return 0
  val rows = -dragPx / rowHeightPx
  return if (rows >= 0f) (rows + 0.5f).toInt() else -((-rows + 0.5f).toInt())
}

/**
 * The weight the wheel previews after [steps] from [baseKg].
 *
 * Clamped and snapped with the *repository's own* `clampWeightKg`, so the previewed value is
 * always a value the repository would accept: 0.5 kg grid, `MIN_WEIGHT_KG..MAX_WEIGHT_KG`.
 */
internal fun wheelWeightKg(baseKg: Double, steps: Int): Double =
  clampWeightKg(baseKg + steps * WEIGHT_STEP_KG)

/**
 * Task 2: the weight the wheel previews, in the **display unit**.
 *
 * [baseDisplay] is the value the user can read (kg for a kg profile, lb for an lb one) and one step
 * is [WeightUnit.displayStep] of that unit. The clamp is the unit's own, so the preview can never
 * show a value the phone would refuse. On a kg profile this is numerically identical to
 * [wheelWeightKg] — same base, same 0.5 step, same `0..500` bound — which is why the kg tests did
 * not have to change.
 */
internal fun wheelWeightDisplay(baseDisplay: Double, steps: Int, unit: WeightUnit): Double =
  unit.clampDisplay(baseDisplay + steps * unit.displayStep)

/** The reps the wheel previews after [steps] from [baseReps], clamped to `MIN_REPS..MAX_REPS`. */
internal fun wheelReps(baseReps: Int, steps: Int): Int = clampReps(baseReps + steps)

/**
 * One column's drag in progress: the value the finger started from, and the pixels it has
 * travelled since.
 *
 * [base] is a `Double` for both columns so one type covers weight and reps; the reps column stores
 * the integer count in it exactly.
 *
 * The base is snapshotted when the drag starts. That is what "ignore incoming projection for that
 * column until the drag ends" (plan §2.6) means mechanically: a `state` that arrives mid-drag
 * cannot re-base a moving wheel, so the digits never jump under the finger.
 */
internal data class WheelDrag(val base: Double, val dragPx: Float) {
  fun steps(rowHeightPx: Float): Int = wheelSteps(dragPx, rowHeightPx)

  /** The weight this drag previews, in kilograms (the wire unit). */
  fun weightKg(rowHeightPx: Float): Double = wheelWeightKg(base, steps(rowHeightPx))

  /**
   * Task 2: the weight this drag previews, in [unit] — what the card renders. [base] is then the
   * display value the drag started from, not kg (see `ExercisePage`).
   */
  fun weightDisplay(rowHeightPx: Float, unit: WeightUnit): Double =
    wheelWeightDisplay(base, steps(rowHeightPx), unit)

  /** The reps this drag previews. */
  fun reps(rowHeightPx: Float): Int = wheelReps(base.toInt(), steps(rowHeightPx))
}
