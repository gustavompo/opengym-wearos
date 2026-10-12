package ch.duartesantos.opengym.wear.data

import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * The unit the phone's profile is set to, and the one conversion boundary in the watch app.
 *
 * **The wire is always kilograms.** `weightKg` / `previousWeightKg` in a `state` frame and the
 * `kg` / `deltaKg` arguments of `setWeight` / `adjustWeight` are kg for every profile, so a phone
 * that sends no `unit` at all behaves exactly as it did before this existed. This type exists only
 * to turn kg into the number the user reads and back.
 *
 * Where the values come from (openGym, read not guessed):
 *
 *  - `S.unit` is `'kg'` or `'lb'`, default `'kg'` (`lib/units.js`, `lib/plates.js`).
 *  - **Every stored weight is already in the profile's unit** — openGym's unit switch walks the
 *    whole document (`lib/units.js` header, `convertStateUnit`), so a document with `S.unit ===
 *    'lb'` holds pounds. The phone now converts to kg for the wire; this converts back.
 *  - `LB_PER_KG` is openGym's own constant, `lib/units.js`.
 *
 * [displayStep] is what one crown detent or one drag row moves. `Kg` keeps the watch's measured
 * **0.5 kg** step (`WEIGHT_STEP_KG`, from `references/exercise-page.png`). `Lb` uses **1 lb**: the
 * nearest round pound to 0.5 kg (0.5 kg = 1.10 lb), and exactly two cells of openGym's own lb
 * lattice. It is deliberately finer than openGym's own set-row stepper (5 lb / 10 lb heavy,
 * `lib/exercises.js:254`) because it is a watch spin control, not the phone's row control — see
 * notes 36 §3 for the alternatives and why they were not taken.
 */
enum class WeightUnit(
  /** What the `Kg` / `Reps` label row and the crown's accessibility description say. */
  val label: String,
  /** The value on the wire, in the `unit` field of a `state` frame's `workout` object. */
  val wireName: String,
  /** The lowercase spelling the `prev. …` hint uses, matching openGym's own rows. */
  val hintLabel: String,
  /** One crown detent / one drag row, in this unit. Always a multiple of [DISPLAY_STEP]. */
  val displayStep: Double,
) {
  Kilograms(label = "Kg", wireName = "kg", hintLabel = "kg", displayStep = WEIGHT_STEP_KG),
  Pounds(label = "Lb", wireName = "lb", hintLabel = "lb", displayStep = 1.0);

  /** kg on the wire -> the number the user reads, snapped to this unit's display lattice. */
  fun fromKg(kg: Double): Double = clampDisplay(if (this == Kilograms) kg else kg * LB_PER_KG)

  /**
   * A displayed value (or a displayed *difference*) -> kg for the wire. **Exact**, never snapped:
   * the phone owns the snap, and its grid is the profile's own unit, which is this one.
   */
  fun toKg(display: Double): Double =
    if (this == Kilograms) display else display / LB_PER_KG

  /** The largest displayable value, on the lattice and inside `0..MAX_WEIGHT_KG` kg. */
  val maxDisplay: Double
    get() = floor((if (this == Kilograms) MAX_WEIGHT_KG else MAX_WEIGHT_KG * LB_PER_KG) / DISPLAY_STEP) * DISPLAY_STEP

  /**
   * Snaps to this unit's display lattice and clamps. The lattice is **0.5** in both units: for kg
   * that is the watch's own `WEIGHT_STEP_KG`, and for lb it is openGym's own lb rounding
   * (`lib/units.js` `convertWeight`, `Math.round(v * LB * 2) / 2`), so every value the phone can
   * store is representable here and a round trip is stable.
   */
  fun clampDisplay(display: Double): Double {
    if (display.isNaN()) return 0.0
    val bounded = display.coerceIn(0.0, maxDisplay)
    return (bounded / DISPLAY_STEP).roundToLong() * DISPLAY_STEP
  }

  companion object {
    /** The lattice both units snap to: 0.5 kg, and openGym's own 0.5 lb. */
    const val DISPLAY_STEP: Double = 0.5

    /**
     * Strictly `"lb"` is pounds; **anything else, including absent, null and junk, is kilograms**.
     * A missing field must not change what the user sees (requirement 4).
     */
    fun fromWire(value: String?): WeightUnit = if (value == Pounds.wireName) Pounds else Kilograms
  }
}

/**
 * Pounds in a kilogram. openGym's `lib/units.js` `LB_PER_KG`, copied rather than re-derived so the
 * two sides cannot drift.
 */
const val LB_PER_KG: Double = 2.2046226218
