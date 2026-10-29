package com.navaneeth.filer.ui.util

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Thin wrapper over the platform haptic constants so feedback follows the user's system
 * "touch feedback" setting and the device's tuned waveforms, instead of raw vibrations.
 */
@Stable
class Haptics(private val view: View) {
    /** Light tick for selections: chips, segmented buttons, menu choices. */
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)

    /** Long-press that opens contextual actions. */
    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)

    /** A completed action: folder added, download finished. */
    fun confirm() = perform(HapticFeedbackConstants.CONFIRM)

    /** A rejected or failed action. */
    fun reject() = perform(HapticFeedbackConstants.REJECT)

    fun toggle(on: Boolean) = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
    )

    /** Crossing a threshold during a gesture, e.g. pull-to-refresh arming. */
    fun gestureThreshold() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        }
    )

    private fun perform(constant: Int) {
        view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
