package com.acronet.crypto

/**
 * SecureMessage — Production-Ready Cryptographic Data Payload (V8)
 *
 * Structured as if holding real X25519/Kyber encrypted data.
 * Ready for Phase 3 SQLCipher integration and Double Ratchet injection.
 */
data class SecureMessage(
    val id: String,
    val senderId: String,
    val encryptedPayload: String,      // Base64 encoded Kyber/X25519 ciphertext
    val timestamp: Long,
    val isMine: Boolean,
    val messageType: MessageType = MessageType.TEXT,
    val isEphemeral: Boolean = false,  // VanishMode flag
    val expiresAt: Long = 0L,         // Unix timestamp for self-destruct
    val isVanishMode: Boolean = false  // Active VanishMode session
) {
    enum class MessageType {
        TEXT, IMAGE, AUDIO, SYSTEM
    }

    /**
     * Convert the payload to a byte array for RAM eviction.
     * In Phase 3, this will be the actual ciphertext bytes.
     */
    fun toBytePayload(): ByteArray = encryptedPayload.toByteArray(Charsets.UTF_8)
}
