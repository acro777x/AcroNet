package com.ghost.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * MessageAdapter — Full-featured RecyclerView adapter.
 *
 * Phase 1: Long-press context menu
 * Phase 4: Search text highlighting
 * Phase 5: Read receipts + disappearing timer
 */
class MessageAdapter(
    private val onImageClick: (String) -> Unit = {},
    private val onAudioClick: (String) -> Unit = {},
    private val onLongClick: (ChatMessage) -> Unit = {},
    private val onBurnRevealed: (String) -> Unit = {}
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF) {

    var searchQuery: String = ""
        set(value) { field = value; notifyDataSetChanged() }

    companion object {
        const val TYPE_SENT = 0
        const val TYPE_RECV = 1
        const val TYPE_SYS = 2

        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a.id == b.id
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) = a == b
        }
    }

    override fun getItemViewType(position: Int): Int {
        val msg = getItem(position)
        return when {
            msg.isSys -> TYPE_SYS
            msg.isSent -> TYPE_SENT
            else -> TYPE_RECV
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SENT -> SentVH(inflater.inflate(R.layout.item_msg_sent, parent, false))
            TYPE_RECV -> RecvVH(inflater.inflate(R.layout.item_msg_recv, parent, false))
            else -> SysVH(inflater.inflate(R.layout.item_msg_sys, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = getItem(position)
        when (holder) {
            is SentVH -> holder.bind(msg)
            is RecvVH -> holder.bind(msg)
            is SysVH -> holder.bind(msg)
        }
    }

    private fun highlightText(text: String): CharSequence {
        if (searchQuery.isEmpty()) return text
        val spannable = SpannableString(text)
        val lower = text.lowercase()
        val queryLower = searchQuery.lowercase()
        var start = lower.indexOf(queryLower)
        while (start >= 0) {
            spannable.setSpan(
                BackgroundColorSpan(0xCCf59e0b.toInt()),
                start, start + queryLower.length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            start = lower.indexOf(queryLower, start + 1)
        }
        return spannable
    }

    private fun receiptIcon(status: ReceiptStatus): String {
        return when (status) {
            ReceiptStatus.NONE -> ""
            ReceiptStatus.SENT -> " ✓"
            ReceiptStatus.DELIVERED -> " ✓✓"
        }
    }

    private fun timerText(msg: ChatMessage): String {
        if (!msg.isEphemeral) return ""
        val sec = msg.remainingSeconds
        return when {
            sec <= 0 -> " ⏱ expired"
            sec < 60 -> " ⏱ ${sec}s"
            sec < 3600 -> " ⏱ ${sec / 60}m"
            else -> " ⏱ ${sec / 3600}h"
        }
    }

    // ── Sent Message ViewHolder ─────────────────────────────────────
    inner class SentVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvText: TextView = v.findViewById(R.id.tv_msg_text)
        private val tvTime: TextView = v.findViewById(R.id.tv_msg_time)
        private val ivImage: ImageView = v.findViewById(R.id.iv_msg_image)
        private val llReplyQuote: View? = v.findViewById(R.id.ll_reply_quote)
        private val tvReplySender: TextView? = v.findViewById(R.id.tv_reply_sender)
        private val tvReplyText: TextView? = v.findViewById(R.id.tv_reply_text)
        private val tvReactionPill: TextView? = v.findViewById(R.id.tv_reaction_pill)
        private var burnRevealed = false

        fun bind(msg: ChatMessage) {
            // ALWAYS clear old listeners and state first
            tvText.setOnClickListener(null)
            tvText.setOnLongClickListener(null)
            tvText.isClickable = false
            tvText.isLongClickable = false
            
            ivImage.setOnClickListener(null)
            ivImage.setOnLongClickListener(null)
            ivImage.isClickable = false
            ivImage.isLongClickable = false

            burnRevealed = false

            val onLongPress = View.OnLongClickListener { onLongClick(msg); true }
            itemView.setOnLongClickListener(onLongPress)
            tvText.setOnLongClickListener(onLongPress)
            ivImage.setOnLongClickListener(onLongPress)

            // Reply UI
            if (msg.replyToId.isNotEmpty() && llReplyQuote != null) {
                llReplyQuote.visibility = View.VISIBLE
                tvReplySender?.text = msg.replyToSender
                tvReplyText?.text = msg.replyToPreview
            } else {
                llReplyQuote?.visibility = View.GONE
            }

            // Reactions UI
            if (msg.reactions.isNotEmpty() && tvReactionPill != null) {
                tvReactionPill.visibility = View.VISIBLE
                tvReactionPill.text = msg.reactions.joinToString(" ")
            } else {
                tvReactionPill?.visibility = View.GONE
            }

            if (msg.isBurn) {
                tvText.visibility = View.VISIBLE
                ivImage.visibility = View.GONE
                tvText.text = "🔥 Tap to Reveal"
                tvText.setOnClickListener {
                    if (!burnRevealed) {
                        burnRevealed = true
                        tvText.text = highlightText(msg.content)
                        tvText.postDelayed({
                            tvText.text = "🔥 Destroyed"
                            tvText.setOnClickListener(null)
                            tvText.isClickable = false
                            onBurnRevealed(msg.id)
                        }, 3000)
                    }
                }
            } else if (msg.isImage) {
                tvText.visibility = View.GONE
                ivImage.visibility = View.VISIBLE
                if (msg.isViewOnce) {
                    ivImage.setImageResource(android.R.drawable.ic_dialog_alert)
                    ivImage.scaleType = android.widget.ImageView.ScaleType.CENTER
                    var bombRevealed = false
                    ivImage.setOnClickListener {
                        if (!bombRevealed) {
                            bombRevealed = true
                            ivImage.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            ImageLoader.load(ivImage, GhostApi.downloadUrl(msg.content))
                            ivImage.postDelayed({
                                ivImage.setImageResource(android.R.drawable.ic_delete)
                                ivImage.scaleType = android.widget.ImageView.ScaleType.CENTER
                                ivImage.setOnClickListener(null)
                                ivImage.isClickable = false
                                onBurnRevealed(msg.id)
                            }, 5000)
                        }
                    }
                } else {
                    ImageLoader.load(ivImage, GhostApi.downloadUrl(msg.content))
                    ivImage.setOnClickListener { onImageClick(msg.content) }
                }
            } else if (msg.isAudio) {
                tvText.visibility = View.VISIBLE
                ivImage.visibility = View.GONE
                tvText.text = "🎤 Play Voice Memo"
                tvText.setOnClickListener { onAudioClick(msg.content) }
            } else {
                tvText.visibility = View.VISIBLE
                ivImage.visibility = View.GONE
                tvText.text = highlightText(msg.content)
            }

            val star = if (msg.isStarred) " ⭐" else ""
            tvTime.text = "${msg.time}${receiptIcon(msg.receiptStatus)}${timerText(msg)}$star"

            if (msg.isEphemeral && msg.remainingSeconds < 10) {
                itemView.alpha = 0.5f
            } else {
                itemView.alpha = 1f
            }
        }
    }

    // ── Received Message ViewHolder ─────────────────────────────────
    inner class RecvVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvText: TextView = v.findViewById(R.id.tv_msg_text)
        private val tvTime: TextView = v.findViewById(R.id.tv_msg_time)
        private val tvName: TextView = v.findViewById(R.id.tv_sender_name)
        private val tvAvatar: TextView = v.findViewById(R.id.tv_avatar)
        private val ivImage: ImageView = v.findViewById(R.id.iv_msg_image)
        private val llReplyQuote: View? = v.findViewById(R.id.ll_reply_quote)
        private val tvReplySender: TextView? = v.findViewById(R.id.tv_reply_sender)
        private val tvReplyText: TextView? = v.findViewById(R.id.tv_reply_text)
        private val tvReactionPill: TextView? = v.findViewById(R.id.tv_reaction_pill)

        fun bind(msg: ChatMessage) {
            val color = GhostCrypto.avatarColor(msg.sender)
            tvName.text = msg.sender
            tvName.setTextColor(color)
            tvAvatar.text = msg.avatarInitials

            val avatarBg = GradientDrawable()
            avatarBg.shape = GradientDrawable.OVAL
            avatarBg.setColor(color)
            tvAvatar.background = avatarBg

            // ALWAYS clear old listeners and state first
            tvText.setOnClickListener(null)
            tvText.setOnLongClickListener(null)
            tvText.isClickable = false
            tvText.isLongClickable = false

            ivImage.setOnClickListener(null)
            ivImage.setOnLongClickListener(null)
            ivImage.isClickable = false
            ivImage.isLongClickable = false

            val onLongPress = View.OnLongClickListener { onLongClick(msg); true }
            itemView.setOnLongClickListener(onLongPress)
            tvText.setOnLongClickListener(onLongPress)
            ivImage.setOnLongClickListener(onLongPress)

            // Reply UI
            if (msg.replyToId.isNotEmpty() && llReplyQuote != null) {
                llReplyQuote.visibility = View.VISIBLE
                tvReplySender?.text = msg.replyToSender
                tvReplyText?.text = msg.replyToPreview
            } else {
                llReplyQuote?.visibility = View.GONE
            }

            // Reactions UI
            if (msg.reactions.isNotEmpty() && tvReactionPill != null) {
                tvReactionPill.visibility = View.VISIBLE
                tvReactionPill.text = msg.reactions.joinToString(" ")
            } else {
                tvReactionPill?.visibility = View.GONE
            }

            if (msg.isBurn) {
                tvText.visibility = View.VISIBLE
                ivImage.visibility = View.GONE
                tvText.text = "🔥 Tap to Reveal"
                var burnRevealed = false
                tvText.setOnClickListener {
                    if (!burnRevealed) {
                        burnRevealed = true
                        tvText.text = highlightText(msg.content)
                        tvText.postDelayed({
                            tvText.text = "🔥 Destroyed"
                            tvText.setOnClickListener(null)
                            tvText.isClickable = false
                            onBurnRevealed(msg.id)
                        }, 3000)
                    }
                }
            } else if (msg.isImage) {
                tvText.visibility = View.GONE
                ivImage.visibility = View.VISIBLE
                if (msg.isViewOnce) {
                    ivImage.setImageResource(android.R.drawable.ic_dialog_alert)
                    ivImage.scaleType = android.widget.ImageView.ScaleType.CENTER
                    var bombRevealed = false
                    ivImage.setOnClickListener {
                        if (!bombRevealed) {
                            bombRevealed = true
                            ivImage.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            ImageLoader.load(ivImage, GhostApi.downloadUrl(msg.content))
                            ivImage.postDelayed({
                                ivImage.setImageResource(android.R.drawable.ic_delete)
                                ivImage.scaleType = android.widget.ImageView.ScaleType.CENTER
                                ivImage.setOnClickListener(null)
                                ivImage.isClickable = false
                                onBurnRevealed(msg.id)
                            }, 5000)
                        }
                    }
                } else {
                    ImageLoader.load(ivImage, GhostApi.downloadUrl(msg.content))
                    ivImage.setOnClickListener { onImageClick(msg.content) }
                }
            } else if (msg.isAudio) {
                tvText.visibility = View.VISIBLE
                ivImage.visibility = View.GONE
                tvText.text = "🎤 Play Voice Memo"
                tvText.setOnClickListener { onAudioClick(msg.content) }
            } else {
                tvText.visibility = View.VISIBLE
                ivImage.visibility = View.GONE
                tvText.text = highlightText(msg.content)
            }

            val star = if (msg.isStarred) " ⭐" else ""
            tvTime.text = "${msg.time}${timerText(msg)}$star"

            if (msg.isEphemeral && msg.remainingSeconds < 10) {
                itemView.alpha = 0.5f
            } else {
                itemView.alpha = 1f
            }
        }
    }

    // ── System Message ViewHolder ───────────────────────────────────
    inner class SysVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvSys: TextView = v.findViewById(R.id.tv_sys_text)
        fun bind(msg: ChatMessage) { tvSys.text = msg.content }
    }
}
