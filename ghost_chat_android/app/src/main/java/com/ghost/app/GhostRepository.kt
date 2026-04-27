package com.ghost.app

import javax.crypto.spec.SecretKeySpec

/**
 * GhostRepository — Single Source of Truth (MVVM Data Layer)
 *
 * V2: Updated to handle Result<String> from GhostCrypto.decryptAES().
 * Tampered messages are silently dropped from the message list.
 */
class GhostRepository {

    /** Login to ESP32 and return auth token */
    suspend fun login(alias: String, key: String): Result<LoginResult> {
        val roomHash = GhostCrypto.genHash(key)
        val aesKey = GhostCrypto.deriveKey(key)

        val loginResult = GhostApi.login(roomHash, key, alias)

        return loginResult.map { tok ->
            // Send join notification (AES encrypted)
            val joinMsg = GhostCrypto.buildSysMsg("👤 $alias securely joined via Android")
            val encrypted = GhostCrypto.encryptAES(joinMsg, aesKey)
            GhostApi.sendMessage(tok, encrypted)

            LoginResult(
                token = tok,
                alias = alias,
                key = key,
                roomHash = roomHash,
                aesKey = aesKey
            )
        }
    }

    /** Fetch and decrypt messages from ESP32 */
    suspend fun readMessages(tok: String, aesKey: SecretKeySpec, myAlias: String): Result<MessageResult> {
        val result = GhostApi.readMessages(tok)
        return result.map { (messageData, onlineCount, typingUser) ->
            val rawMsgs = messageData.split("|||").filter { it.isNotEmpty() }
            val parsed = mutableListOf<ChatMessage>()
            val deletedIds = mutableSetOf<String>()

            rawMsgs.forEachIndexed { idx, m ->
                // V2: decryptAES now returns Result<String>
                val decryptResult = GhostCrypto.decryptAES(m, aesKey)
                if (decryptResult.isFailure) {
                    // Tampered or unreadable — silently destroy the payload
                    return@forEachIndexed
                }
                val decrypted = decryptResult.getOrThrow()

                if (!decrypted.startsWith(GhostCrypto.MSG_PREFIX)) return@forEachIndexed

                val parts = decrypted.split("|")
                if (parts.size < 4) return@forEachIndexed

                val sender = parts[1]
                val time = parts[2]
                val rawContent = parts.drop(3).joinToString("|")
                
                if (rawContent.startsWith("CMD:")) {
                    if (rawContent.startsWith("CMD:DEL:")) {
                        val delId = rawContent.removePrefix("CMD:DEL:")
                        if (delId.isNotEmpty()) deletedIds.add(delId)
                    }
                    return@forEachIndexed
                }

                // ── Shield Wall: Parse wire protocol into clean state ──
                var cleanContent = rawContent
                var expiresAt = 0L
                var isViewOnce = false
                var isImage = false
                var isAudio = false
                var isBurn = false
                var replyToId = ""
                var replyToSender = ""
                var replyToPreview = ""

                when {
                    rawContent.startsWith("SD:") -> {
                        val sdParts = rawContent.split(":", limit = 4)
                        if (sdParts.size == 4) {
                            val duration = sdParts[1].toLongOrNull() ?: 0L
                            val epoch = sdParts[2].toLongOrNull() ?: 0L
                            cleanContent = sdParts[3]
                            expiresAt = epoch + (duration * 1000)
                        } else {
                            cleanContent = rawContent
                        }
                    }
                    rawContent.startsWith("VO:") -> {
                        cleanContent = rawContent.removePrefix("VO:")
                        isImage = true
                        isViewOnce = true
                    }
                    rawContent.startsWith("IMG:") -> {
                        cleanContent = rawContent.removePrefix("IMG:")
                        isImage = true
                    }
                    rawContent.startsWith("BURN:") -> {
                        cleanContent = rawContent.removePrefix("BURN:")
                        isBurn = true
                    }
                    rawContent.startsWith("VOX:") -> {
                        cleanContent = rawContent.removePrefix("VOX:")
                        isAudio = true
                    }
                    rawContent.startsWith("REACT:") -> {
                        val payload = rawContent.removePrefix("REACT:")
                        val sepIdx = payload.lastIndexOf('§')
                        if (sepIdx >= 0) {
                            val targetHash = payload.substring(0, sepIdx)
                            val emoji = payload.substring(sepIdx + 1)
                            val targetMsg = parsed.find { it.id == targetHash }
                            if (targetMsg != null) {
                                targetMsg.reactions.add(emoji)
                            }
                        }
                        return@forEachIndexed
                    }
                    rawContent.startsWith("REPLY:") -> {
                        val payload = rawContent.removePrefix("REPLY:")
                        val sepIdx = payload.indexOf('§')
                        if (sepIdx >= 0) {
                            val meta = payload.substring(0, sepIdx).split("|", limit = 3)
                            if (meta.size == 3) {
                                replyToId = meta[0]
                                replyToSender = meta[1]
                                replyToPreview = meta[2]
                                cleanContent = payload.substring(sepIdx + 1)
                            }
                        }
                    }
                    rawContent.startsWith("EXP:") -> {
                        val expParts = rawContent.split("|", limit = 2)
                        if (expParts.size == 2) {
                            expiresAt = expParts[0].removePrefix("EXP:").toLongOrNull() ?: 0L
                            cleanContent = expParts[1]
                        }
                    }
                }

                val stableId = GhostCrypto.generateMessageId(sender, time, rawContent)

                parsed.add(
                    ChatMessage(
                        id = stableId,
                        sender = sender,
                        time = time,
                        content = cleanContent,
                        isSent = sender == myAlias,
                        isSys = sender == "SYS",
                        isImage = isImage,
                        isAudio = isAudio,
                        isViewOnce = isViewOnce,
                        isBurn = isBurn,
                        expiresAt = expiresAt,
                        replyToId = replyToId,
                        replyToSender = replyToSender,
                        replyToPreview = replyToPreview,
                        receiptStatus = if (sender == myAlias) ReceiptStatus.DELIVERED else ReceiptStatus.NONE
                    )
                )
            }

            MessageResult(messages = parsed.filter { it.id !in deletedIds }, onlineCount = onlineCount, typingUser = typingUser)
        }
    }

    /** Encrypt and send a text message */
    suspend fun sendMessage(tok: String, aesKey: SecretKeySpec, alias: String, text: String): Result<Boolean> {
        val raw = GhostCrypto.buildMsg(alias, text)
        val encrypted = GhostCrypto.encryptAES(raw, aesKey)
        return GhostApi.sendMessage(tok, encrypted)
    }

    /** Send a system notification message */
    suspend fun sendSystemMessage(tok: String, aesKey: SecretKeySpec, text: String): Result<Boolean> {
        val raw = GhostCrypto.buildSysMsg(text)
        val encrypted = GhostCrypto.encryptAES(raw, aesKey)
        return GhostApi.sendMessage(tok, encrypted)
    }

    /** Upload image file to ESP32 */
    suspend fun uploadImage(tok: String, file: java.io.File): Result<Boolean> {
        return GhostApi.uploadFile(tok, file)
    }

    /** Check LoRa radio status */
    suspend fun checkLoraStatus(): String {
        return GhostApi.loraStatus().getOrDefault("Offline")
    }
}

/** Login result container */
data class LoginResult(
    val token: String,
    val alias: String,
    val key: String,
    val roomHash: String,
    val aesKey: SecretKeySpec
)

/** Message fetch result container */
data class MessageResult(
    val messages: List<ChatMessage>,
    val onlineCount: String,
    val typingUser: String = "",
    val loraStatus: String = "Offline"
)
