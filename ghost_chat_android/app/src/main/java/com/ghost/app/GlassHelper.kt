package com.ghost.app

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View

/**
 * GlassHelper — Hardware-accelerated Glassmorphism.
 *
 * API 31+: Uses RenderEffect.createBlurEffect() (GPU-accelerated)
 * API 26-30: Falls back to translucent color overlay (zero GPU cost)
 */
object GlassHelper {

    /** Apply frosted glass blur to a View */
    fun applyGlassEffect(view: View, blurRadius: Float = 20f) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val effect = RenderEffect.createBlurEffect(
                    blurRadius, blurRadius, Shader.TileMode.CLAMP
                )
                view.setRenderEffect(effect)
            } catch (e: Exception) {
                // Fallback if GPU can't handle it
                applyFallback(view)
            }
        } else {
            applyFallback(view)
        }
    }

    /** Remove glass effect from a View */
    fun removeGlassEffect(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }

    /** Fallback for older devices — translucent overlay */
    private fun applyFallback(view: View) {
        view.alpha = 0.95f
    }
}
