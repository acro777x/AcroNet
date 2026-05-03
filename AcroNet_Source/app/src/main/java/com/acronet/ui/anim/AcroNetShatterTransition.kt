package com.acronet.ui.anim

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.*
import kotlin.random.Random

/**
 * AcroNetShatterTransition — Canvas Particle Explosion
 *
 * When the 3-finger stealth gesture triggers, the Stock Ticker decoy
 * doesn't "cut" to the chat. The candlestick chart physically SHATTERS
 * into particles that scatter with physics-based trajectories, revealing
 * the dark-web glassmorphism UI beneath.
 *
 * Physics:
 *   - Each particle has: position, velocity, rotation, angular velocity, alpha
 *   - Gravity pulls particles downward (9.8 * scale)
 *   - Particles fade out over 800ms
 *   - Once alpha reaches 0, the overlay is removed and the chat is fully visible
 */
class AcroNetShatterTransition {

    data class Particle(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var rotation: Float,
        var angularVelocity: Float,
        var alpha: Float,
        var size: Float,
        val color: Int
    )

    companion object {
        private const val PARTICLE_COUNT = 120
        private const val DURATION_MS = 900L
        private const val GRAVITY = 2400f // pixels/s^2

        /**
         * Execute the shatter transition on the given view.
         * The view is captured as a bitmap, shattered into particles,
         * and animated away. onComplete is called when done.
         */
        fun shatter(targetView: View, container: ViewGroup, onComplete: () -> Unit) {
            // Capture the current view as a bitmap
            val bitmap = captureView(targetView)
            if (bitmap == null) {
                onComplete()
                return
            }

            // Generate particles from the bitmap
            val particles = generateParticles(bitmap, targetView.width, targetView.height)

            // Create overlay for particle rendering
            val overlay = ParticleOverlay(targetView.context, particles)
            overlay.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            // Hide original view, show particle overlay
            targetView.visibility = View.INVISIBLE
            container.addView(overlay)

            // Animate
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DURATION_MS
                interpolator = AccelerateInterpolator(1.5f)
                addUpdateListener { anim ->
                    val progress = anim.animatedValue as Float
                    val dt = 1f / 60f // Approximate 60fps timestep

                    for (p in particles) {
                        p.x += p.vx * dt
                        p.y += p.vy * dt
                        p.vy += GRAVITY * dt
                        p.rotation += p.angularVelocity * dt
                        p.alpha = (1f - progress).coerceIn(0f, 1f)
                    }
                    overlay.invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        container.removeView(overlay)
                        bitmap.recycle()
                        onComplete()
                    }
                })
            }
            animator.start()
        }

        private fun captureView(view: View): Bitmap? {
            return try {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                view.draw(canvas)
                bitmap
            } catch (e: Exception) { null }
        }

        private fun generateParticles(bitmap: Bitmap, w: Int, h: Int): List<Particle> {
            val particles = mutableListOf<Particle>()
            val cellW = w / 12 // 12 columns
            val cellH = h / 10 // 10 rows
            val centerX = w / 2f
            val centerY = h / 2f

            for (row in 0 until 10) {
                for (col in 0 until 12) {
                    val px = col * cellW + cellW / 2f
                    val py = row * cellH + cellH / 2f

                    // Sample color from bitmap
                    val bx = (px).toInt().coerceIn(0, bitmap.width - 1)
                    val by = (py).toInt().coerceIn(0, bitmap.height - 1)
                    val color = bitmap.getPixel(bx, by)

                    // Velocity: radiate outward from center
                    val dx = px - centerX
                    val dy = py - centerY
                    val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                    val speed = 400f + Random.nextFloat() * 600f

                    particles.add(Particle(
                        x = px, y = py,
                        vx = (dx / dist) * speed + Random.nextFloat() * 100f - 50f,
                        vy = (dy / dist) * speed - 200f - Random.nextFloat() * 300f,
                        rotation = 0f,
                        angularVelocity = Random.nextFloat() * 720f - 360f,
                        alpha = 1f,
                        size = cellW.toFloat() * (0.8f + Random.nextFloat() * 0.4f),
                        color = color
                    ))
                }
            }
            return particles
        }
    }

    private class ParticleOverlay(
        context: android.content.Context,
        private val particles: List<Particle>
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (p in particles) {
                if (p.alpha <= 0.01f) continue
                paint.color = p.color
                paint.alpha = (p.alpha * 255).toInt()

                canvas.save()
                canvas.translate(p.x, p.y)
                canvas.rotate(p.rotation)
                canvas.drawRect(
                    -p.size / 2, -p.size / 2,
                    p.size / 2, p.size / 2,
                    paint
                )
                canvas.restore()
            }
        }
    }
}
