package com.acronet.ui.utils

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * TripleTapListener
 *
 * A highly precise touch listener that requires exactly 3 sequential taps
 * within a predefined 1000ms window. Exceeding the window resets the counter.
 *
 * This provides high stealth. A rapid triple tap on an app title looks
 * like an accidental touch or app frustration.
 */
class TripleTapListener(
    private val onTripleTap: () -> Unit
) : View.OnTouchListener {

    private val TAP_TIMEOUT_MS = 1000L
    private var tapCount = 0
    private var lastTapTime = 0L

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val currentTime = SystemClock.elapsedRealtime()

            // If the time since last tap is greater than the timeout, reset
            if (currentTime - lastTapTime > TAP_TIMEOUT_MS) {
                tapCount = 0
            }

            tapCount++
            lastTapTime = currentTime

            if (tapCount == 3) {
                // Execute stealth haptic feedback
                v.performHapticFeedback(
                    HapticFeedbackConstants.LONG_PRESS,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
                // Trigger the vault sequence
                onTripleTap()
                
                // Reset to prevent accidental re-triggers
                tapCount = 0
                return true
            }
        }
        return false
    }
}
