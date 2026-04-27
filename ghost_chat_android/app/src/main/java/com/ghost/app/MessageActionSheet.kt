package com.ghost.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * MessageActionSheet — Long-press context menu for messages.
 *
 * Phase 1: Copy, Reply, Star, Delete-for-Me, Delete-for-Everyone, Forward
 * Phase 5A: Emoji quick-react
 */
class MessageActionSheet(
    private val message: ChatMessage,
    private val onAction: (Action) -> Unit,
    private val onReact: (String) -> Unit = {}
) : BottomSheetDialogFragment() {

    enum class Action { COPY, REPLY, STAR, UNSTAR, DELETE_LOCAL, DELETE_EVERYONE, FORWARD }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.bottom_sheet_actions, container, false)

        // Preview
        val tvPreview = root.findViewById<TextView>(R.id.tv_action_preview)
        tvPreview.text = if (message.isImage) "📷 Photo" else message.content.take(80)

        val tvSender = root.findViewById<TextView>(R.id.tv_action_sender)
        tvSender.text = if (message.isSent) "You" else message.sender

        // ── Emoji Reactions ─────────────────────────────────────────
        val emojis = listOf(
            R.id.react_thumbs to "👍",
            R.id.react_heart to "❤️",
            R.id.react_laugh to "😂",
            R.id.react_wow to "😮",
            R.id.react_sad to "😢",
            R.id.react_fire to "🔥"
        )
        emojis.forEach { (id, emoji) ->
            root.findViewById<TextView>(id).setOnClickListener { v ->
                // Bounce animation on tap
                v.animate().scaleX(1.4f).scaleY(1.4f).setDuration(100).withEndAction {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }.start()
                onReact(emoji)
                dismiss()
            }
        }

        // Action buttons
        setupAction(root, R.id.btn_copy, "📋  Copy Text") {
            copyToClipboard(message.content)
            onAction(Action.COPY)
        }

        setupAction(root, R.id.btn_reply, "↩  Reply") {
            onAction(Action.REPLY)
        }

        val starBtn = root.findViewById<TextView>(R.id.btn_star)
        if (message.isStarred) {
            starBtn.text = "★  Unstar Message"
            starBtn.setOnClickListener { onAction(Action.UNSTAR); dismiss() }
        } else {
            starBtn.text = "☆  Star Message"
            starBtn.setOnClickListener { onAction(Action.STAR); dismiss() }
        }

        setupAction(root, R.id.btn_delete_local, "🗑  Delete for Me") {
            onAction(Action.DELETE_LOCAL)
        }

        setupAction(root, R.id.btn_delete_everyone, "💣  Delete for Everyone") {
            onAction(Action.DELETE_EVERYONE)
        }

        setupAction(root, R.id.btn_forward, "📤  Forward") {
            onAction(Action.FORWARD)
        }

        // Hide "Delete for Everyone" on received messages
        if (!message.isSent) {
            root.findViewById<View>(R.id.btn_delete_everyone).visibility = View.GONE
        }

        // Hide copy on image messages
        if (message.isImage) {
            root.findViewById<View>(R.id.btn_copy).visibility = View.GONE
        }

        return root
    }

    private fun setupAction(root: View, id: Int, text: String, action: () -> Unit) {
        val btn = root.findViewById<TextView>(id)
        btn.text = text
        btn.setOnClickListener { action(); dismiss() }
    }

    private fun copyToClipboard(text: String) {
        val ctx = requireContext()
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Ghost Chat", text))
        Toast.makeText(ctx, "🔒 Copied — auto-clears in 30s", Toast.LENGTH_SHORT).show()

        // Auto-clear clipboard after 30 seconds (security)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            } catch (_: Exception) {}
        }, 30_000)
    }
}
