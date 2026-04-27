package com.ghost.app

import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * ChatItemAnimator — Smooth message bubble animations like Telegram/WhatsApp.
 *
 * - New messages slide up and fade in from below
 * - Sent messages scale in from the right
 * - Removed messages fade out and shrink
 */
class ChatItemAnimator : DefaultItemAnimator() {

    init {
        addDuration = 250
        removeDuration = 200
        moveDuration = 200
        changeDuration = 200
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        holder.itemView.apply {
            alpha = 0f
            translationY = 80f
            scaleX = 0.92f
            scaleY = 0.92f
        }

        holder.itemView.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(addDuration)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction { dispatchAddFinished(holder) }
            .start()

        return true
    }

    override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
        holder.itemView.animate()
            .alpha(0f)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .translationX(if (holder.itemViewType == MessageAdapter.TYPE_SENT) 100f else -100f)
            .setDuration(removeDuration)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                holder.itemView.alpha = 1f
                holder.itemView.scaleX = 1f
                holder.itemView.scaleY = 1f
                holder.itemView.translationX = 0f
                dispatchRemoveFinished(holder)
            }
            .start()

        return true
    }
}
