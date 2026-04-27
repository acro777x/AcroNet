package com.ghost.app

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.min

/**
 * SwipeReplyCallback — Telegram-style swipe-to-reply gesture.
 *
 * Swipe right on any message to reply. Draws a reply arrow icon
 * that grows as the user swipes further. Triggers callback at threshold.
 */
class SwipeReplyCallback(
    private val onSwipeReply: (Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {

    private val replyPaint = Paint().apply {
        color = 0xFF3b82f6.toInt()
        textSize = 48f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val bgPaint = Paint().apply {
        color = 0x20FFFFFF
        isAntiAlias = true
    }

    private val threshold = 200f // pixels to trigger reply

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        // Only allow swipe on non-system messages
        val adapter = recyclerView.adapter as? MessageAdapter ?: return 0
        val viewType = adapter.getItemViewType(viewHolder.adapterPosition)
        if (viewType == MessageAdapter.TYPE_SYS) return 0 // Don't swipe system messages
        return makeMovementFlags(0, ItemTouchHelper.RIGHT)
    }

    override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // Reset the item position (we don't want to dismiss it)
        val adapter = viewHolder.itemView.context.let {
            (viewHolder.bindingAdapter as? MessageAdapter)
        }
        adapter?.notifyItemChanged(viewHolder.adapterPosition)
        onSwipeReply(viewHolder.adapterPosition)
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.4f

    override fun getSwipeEscapeVelocity(defaultValue: Float): Float = defaultValue * 2f

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val itemView = viewHolder.itemView
        val clampedDx = min(dX, threshold * 1.2f)

        if (clampedDx > 0) {
            // Draw reply hint background
            val progress = min(clampedDx / threshold, 1f)

            // Background circle
            val centerX = 56f
            val centerY = itemView.top + itemView.height / 2f
            val radius = 24f * progress

            bgPaint.alpha = (progress * 80).toInt()
            c.drawCircle(centerX, centerY, radius, bgPaint)

            // Reply arrow "↩"
            replyPaint.alpha = (progress * 255).toInt()
            replyPaint.textSize = 28f + (20f * progress)
            c.drawText("↩", centerX, centerY + 12f, replyPaint)
        }

        // Move the item
        super.onChildDraw(c, recyclerView, viewHolder, clampedDx, dY, actionState, isCurrentlyActive)
    }
}
