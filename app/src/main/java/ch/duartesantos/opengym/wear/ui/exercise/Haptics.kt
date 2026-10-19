package ch.duartesantos.opengym.wear.ui.exercise

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

/*
 *  — the one haptics helper both screens use.
 *
 * The mechanism is chosen, not invented: `HapticFeedbackConstants.CLOCK_TICK` is the same tick the
 * system bezel emits on a rotary detent, so a watch that already ticks on lists, the settings
 * crown, etc., will tick identically on this app's controls. It is delivered through the view the
 * composable is mounted on (`performHapticFeedback`, which on Wear goes to the hardware vibrator)
 * and is gated on the system setting the same way every other haptic in the OS is — a user who
 * turned haptics off in the watch settings gets nothing, exactly like the bezel.
 *
 * It lives here (in the exercise package) because a shared haptics file outside the two screens is
 * not this agent's to create; RestTimer.kt already imports from this package (`formatWeight`).
 */

/**
 * A `() -> Unit` that fires one CLOCK_TICK on the current view, if the system haptics setting is
 * enabled. Cheap to call; the lambda is rebuilt on recomposition but holds no state.
 *
 * Honest note for the evidence file: an emulator has no vibration motor (`dumpsys vibrator` ->
 * `Can't find service`), so neither the tone nor the amplitude of the tick is verifiable here —
 * what is verified is that every wired control calls this exactly once per interaction step, and
 * that the call is gated on `View.isHapticFeedbackEnabled`.
 */
@Composable
internal fun rememberHapticTick(): () -> Unit {
  val view = LocalView.current
  return {
    if (view.isHapticFeedbackEnabled) {
      view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
  }
}