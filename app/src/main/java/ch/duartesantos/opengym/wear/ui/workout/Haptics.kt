package ch.duartesantos.opengym.wear.ui.workout

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The clock-tick haptic — the same constant the system bezel's detents emit — gated on the view's
 * own haptic-feedback setting, so it is a no-op when the user turned haptics off (or on a device
 * with no actuator, like an emulator; `isHapticFeedbackEnabled` answers false there).
 *
 * Lives in this package, not in a shared file: another screen agent adds its own helper for its own
 * buttons, and the two must not collide over a file neither of them owns outright.
 *
 * This page's only haptic moment is a row tap; there are no other buttons on it (BACK lives above
 * the page, in `WearApp`).
 */
internal fun View.workoutRowTick() {
  if (isHapticFeedbackEnabled) {
    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
  }
}