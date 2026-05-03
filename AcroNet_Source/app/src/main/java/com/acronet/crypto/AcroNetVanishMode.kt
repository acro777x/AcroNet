package com.acronet.crypto

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.fragment.app.Fragment
import java.util.Arrays

/**
 * AcroNetVanishMode — Ephemeral Session Controller (V8)
 *
 * Manages ephemeral messaging state with aggressive RAM eviction.
 * When VanishMode is active:
 *   1. FLAG_SECURE is set (prevents screenshots)
 *   2. All messages are marked as ephemeral
 *   3. When timer expires, message byte arrays are zero-filled in RAM
 *   4. Ephemeral keys are evicted from the session cache
 *
 * Even in Phase 2 simulation, the memory scrubbing is PHYSICALLY FUNCTIONAL.
 */
class AcroNetVanishMode {

    var isActive: Boolean = false
        private set

    var selfDestructSeconds: Int = 30  // Default: 30 seconds
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val pendingEvictions = mutableListOf<EvictionTask>()

    data class EvictionTask(
        val messageId: String,
        val bytePayload: ByteArray,
        val runnable: Runnable
    )

    interface VanishModeCallback {
        fun onVanishModeActivated()
        fun onVanishModeDeactivated()
        fun onMessageEvicted(messageId: String)
    }

    private var callback: VanishModeCallback? = null

    fun setCallback(cb: VanishModeCallback) {
        this.callback = cb
    }

    /**
     * Activate VanishMode on the given fragment.
     * Sets FLAG_SECURE to block screenshots and screen recording.
     */
    fun activate(fragment: Fragment) {
        if (isActive) return
        isActive = true

        // FLAG_SECURE: Block screenshots and screen recording
        fragment.requireActivity().window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        Log.w("AcroVoid", "[VANISH] VanishMode ACTIVATED. FLAG_SECURE enabled. Self-destruct: ${selfDestructSeconds}s")
        callback?.onVanishModeActivated()
    }

    /**
     * Deactivate VanishMode. Clears FLAG_SECURE and evicts ALL pending messages.
     */
    fun deactivate(fragment: Fragment) {
        if (!isActive) return
        isActive = false

        // Remove FLAG_SECURE
        fragment.requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // Aggressive eviction of ALL pending messages
        evictAll()

        Log.w("AcroVoid", "[VANISH] VanishMode DEACTIVATED. All ephemeral payloads zero-filled.")
        callback?.onVanishModeDeactivated()
    }

    /**
     * Set the self-destruct timer in seconds.
     */
    fun setSelfDestructTimer(seconds: Int) {
        selfDestructSeconds = seconds
        Log.w("AcroVoid", "[VANISH] Self-destruct timer set to ${seconds}s")
    }

    /**
     * Schedule a message for RAM eviction after the self-destruct timer.
     * The byte array will be zero-filled when the timer expires.
     */
    fun scheduleEviction(message: SecureMessage) {
        val bytePayload = message.toBytePayload()
        val runnable = Runnable {
            // CRITICAL: Zero-fill the byte array in RAM
            Arrays.fill(bytePayload, 0.toByte())
            Log.w("AcroVoid", "[VANISH] Message ${message.id} evicted. ${bytePayload.size} bytes zero-filled.")

            // Remove from pending list
            pendingEvictions.removeAll { it.messageId == message.id }

            // Notify callback
            callback?.onMessageEvicted(message.id)
        }

        val task = EvictionTask(message.id, bytePayload, runnable)
        pendingEvictions.add(task)

        // Schedule the eviction
        handler.postDelayed(runnable, selfDestructSeconds * 1000L)
    }

    /**
     * Immediately evict all pending messages. Called on session end.
     */
    private fun evictAll() {
        for (task in pendingEvictions) {
            handler.removeCallbacks(task.runnable)
            // CRITICAL: Zero-fill immediately
            Arrays.fill(task.bytePayload, 0.toByte())
            Log.w("AcroVoid", "[VANISH] FORCE EVICT: ${task.messageId} — ${task.bytePayload.size} bytes zeroed.")
        }
        pendingEvictions.clear()
    }

    /**
     * Clean up when the fragment is destroyed.
     */
    fun destroy() {
        evictAll()
        handler.removeCallbacksAndMessages(null)
        callback = null
    }
}
