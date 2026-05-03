package com.acronet.ui.anim

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * AcroNetSwipeReply — Zero-Latency Spring Physics Reply Gesture
 *
 * Implements ItemTouchHelper with DynamicAnimation SpringForce.
 * When the user swipes a message bubble to reply:
 *
 * 1. The bubble STRETCHES like rubber based on thumb velocity
 * 2. A reply arrow icon renders proportionally to swipe distance
 * 3. At exactly 120dp threshold: haptic CLOCK_TICK fires
 * 4. On release: SpringAnimation snaps back (stiffness=800, damping=0.6)
 * 5. If threshold crossed: onReplyTriggered callback fires
 *
 * Physics: Spring constant 800 N/m, damping ratio 0.6 (underdamped = bounce)
 */
class AcroNetSwipeReply(
    private val onReplyTriggered: (position: Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {

    private val REPLY_THRESHOLD_PX = 300f  // ~120dp on mdpi
    private var hapticFired = false

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7C3AED") // Violet accent
        textSize = 64f
        textAlign = Paint.Align.CENTER
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#1A1A2E")
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // Spring back — don't actually remove the item
        // The reply is triggered via threshold detection in onChildDraw
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
        return Float.MAX_VALUE // Never complete the swipe — we handle it manually
    }

    override fun getSwipeEscapeVelocity(defaultValue: Float): Float {
        return Float.MAX_VALUE // Prevent fling-to-dismiss
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float, dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val itemView = viewHolder.itemView
        val clampedDX = dX.coerceAtMost(REPLY_THRESHOLD_PX * 1.5f)

        if (isCurrentlyActive) {
            // Draw reply indicator background
            val bg = RectF(
                itemView.left.toFloat(),
                itemView.top.toFloat(),
                itemView.left + clampedDX,
                itemView.bottom.toFloat()
            )
            c.drawRect(bg, bgPaint)

            // Draw reply arrow (scales with distance)
            val progress = (clampedDX / REPLY_THRESHOLD_PX).coerceIn(0f, 1f)
            arrowPaint.alpha = (progress * 255).toInt()
            val arrowX = itemView.left + clampedDX / 2f
            val arrowY = (itemView.top + itemView.bottom) / 2f + 20f
            c.drawText("↩", arrowX, arrowY, arrowPaint)

            // HAPTIC: Fire CLOCK_TICK at exact threshold crossing
            if (clampedDX >= REPLY_THRESHOLD_PX && !hapticFired) {
                itemView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                hapticFired = true
                onReplyTriggered(viewHolder.adapterPosition)
            }

            // Translate the item view (rubber band effect)
            itemView.translationX = clampedDX * 0.5f // Halved = rubber stretch feel
        } else {
            // RELEASE: Spring animation back to origin
            hapticFired = false
            springBack(itemView)
        }
    }

    private fun springBack(view: View) {
        SpringAnimation(view, SpringAnimation.TRANSLATION_X, 0f).apply {
            spring.stiffness = SpringForce.STIFFNESS_HIGH // 800 N/m
            spring.dampingRatio = 0.6f // Underdamped = slight bounce
            start()
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        viewHolder.itemView.translationX = 0f
        hapticFired = false
    }
}
