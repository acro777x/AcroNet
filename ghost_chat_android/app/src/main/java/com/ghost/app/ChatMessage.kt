package com.ghost.app

/**
 * ChatMessage — Strict, immutable data class.
 *
 * The Repository parses ALL wire protocol (SD:, VO:, IMG:, CMD:)
 * and outputs a CLEAN ChatMessage. The View layer NEVER mutates.
 */
data class ChatMessage(
    val id: String = "",
    val sender: String = "",
    val content: String = "",       // CLEAN text or filename only — no protocol prefixes
    val time: String = "",
    val isSent: Boolean = false,
    val isSys: Boolean = false,
    val isImage: Boolean = false,
    val isAudio: Boolean = false,
    val isViewOnce: Boolean = false, // VO: bomb image — must NOT auto-load

    // Phase 1: Message Actions
    val isStarred: Boolean = false,
    val isSelected: Boolean = false,
    val isBurn: Boolean = false,

    // Phase 5: Read Receipts
    val receiptStatus: ReceiptStatus = ReceiptStatus.NONE,

    // Phase 5: Disappearing Messages
    val expiresAt: Long = 0L,       // Unix ms timestamp, 0 = never expires

    // Phase 6: Reply reference
    val replyToId: String = "",
    val replyToSender: String = "",
    val replyToPreview: String = "",

    // Phase 8: Reactions
    val reactions: MutableList<String> = mutableListOf()
) {
    val avatarInitials: String get() = sender.take(2).uppercase()

    /** Content is already clean — no prefix stripping needed */
    val displayContent: String get() = content

    fun isExpired(): Boolean = expiresAt > 0L && System.currentTimeMillis() > expiresAt

    val remainingSeconds: Long get() {
        if (expiresAt <= 0) return Long.MAX_VALUE
        val remaining = (expiresAt - System.currentTimeMillis()) / 1000
        return if (remaining > 0) remaining else 0
    }

    val isEphemeral: Boolean get() = expiresAt > 0
}

enum class ReceiptStatus {
    NONE,       // Not sent yet
    SENT,       // ✓  Sent to ESP32
    DELIVERED   // ✓✓ ESP32 confirmed (HTTP 200)
}
