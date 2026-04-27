package com.ghost.app

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * ChatViewModel — Full-featured ViewModel.
 *
 * Phase 1: Delete local + delete-for-everyone
 * Phase 5: Read receipts, disappearing message expiry check
 * Lifecycle-aware polling with WhileSubscribed(5000)
 */

sealed class ConnectionState { object CONNECTED : ConnectionState(); object DISCONNECTED : ConnectionState() }
sealed class SendState { object Idle : SendState(); object Sending : SendState(); object Sent : SendState(); data class Error(val message: String) : SendState() }

class ChatViewModel(
    private val alias: String,
    private val key: String,
    private val tok: String,
    private val roomHash: String,
    private val aesKey: SecretKey
) : ViewModel() {

    private val repo = GhostRepository()
    private val aesSpec = aesKey as SecretKeySpec

    val roomDisplayName: String get() = "Room ${roomHash.take(6).uppercase()}"

    // ── StateFlows ──────────────────────────────────────────────────
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.CONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _onlineCount = MutableStateFlow("0")
    val onlineCount: StateFlow<String> = _onlineCount.asStateFlow()

    private val _typingUser = MutableStateFlow("")
    val typingUser: StateFlow<String> = _typingUser.asStateFlow()

    private val _loraStatus = MutableStateFlow("Offline")
    val loraStatus: StateFlow<String> = _loraStatus.asStateFlow()

    private val _loraUpdates = MutableStateFlow("Offline")
    val loraUpdates: StateFlow<String> = _loraUpdates.asStateFlow()

    // Local deletion set — loaded from persistent storage
    private val deletedIds = SecurePrefs.getDeletedIds().toMutableSet()

    /**
     * Lifecycle-aware polling.
     * WhileSubscribed(5000) — stops 5s after UI goes to STOPPED.
     */
    val messageUpdates: StateFlow<List<ChatMessage>> = flow<List<ChatMessage>> {
        while (true) {
            try {
                val msgResult = repo.readMessages(tok, aesSpec, alias)

                if (msgResult.isSuccess) {
                    val result = msgResult.getOrThrow()
                    _connectionState.value = ConnectionState.CONNECTED
                    _onlineCount.value = result.onlineCount

                    // Check LoRa status periodically
                    val lora = repo.checkLoraStatus()
                    _loraStatus.value = lora
                    _loraUpdates.value = lora

                    // Handle typing user
                    _typingUser.value = if (result.typingUser != alias) result.typingUser else ""

                    // Merge with local state: stars, deleted, receipts
                    val enriched = result.messages
                        .filter { it.id !in deletedIds }
                        .map { msg ->
                            msg.copy(
                                isStarred = SecurePrefs.isStarred(msg.id),
                                receiptStatus = if (msg.isSent) ReceiptStatus.DELIVERED else ReceiptStatus.NONE
                            )
                        }

                    _messages.value = enriched
                    emit(enriched)
                } else {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    emit(_messages.value)
                }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.DISCONNECTED
                emit(_messages.value)
            }
            delay(3000)
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList<ChatMessage>()
    )

    // ── Send Message ────────────────────────────────────────────────
    fun sendMessage(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _sendState.value = SendState.Sending
            try {
                // Phase 1: Burn-on-Read
                var payload = text
                if (payload.startsWith("/burn ", ignoreCase = true)) {
                    payload = "BURN:" + payload.substring(6)
                }

                // Add disappearing timer if enabled and not already a BURN message
                val timer = SecurePrefs.getDisappearTimer()
                if (timer > 0 && !payload.startsWith("BURN:")) {
                    val expiresAt = System.currentTimeMillis() + (timer * 1000)
                    payload = "EXP:$expiresAt|$payload"
                }

                val result = repo.sendMessage(tok, aesSpec, alias, payload)
                _sendState.value = if (result.isSuccess) SendState.Sent else SendState.Error("Send failed")
            } catch (e: Exception) {
                _sendState.value = SendState.Error(e.message ?: "Send failed")
            }
        }
    }

    // ── Send Image ──────────────────────────────────────────────────
    fun sendImage(cacheDir: File, uri: Uri, resolver: ContentResolver) {
        viewModelScope.launch(Dispatchers.IO) {
            _sendState.value = SendState.Sending
            try {
                val filename = "upload_${System.currentTimeMillis()}.jpg"
                val tempFile = File(cacheDir, filename)
                resolver.openInputStream(uri)?.use { input ->
                    val original = android.graphics.BitmapFactory.decodeStream(input)
                    if (original != null) {
                        val maxDim = 600f
                        val scale = kotlin.math.min(1f, kotlin.math.min(maxDim / original.width, maxDim / original.height))
                        val resized = if (scale < 1f) {
                            val matrix = android.graphics.Matrix()
                            matrix.postScale(scale, scale)
                            android.graphics.Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
                        } else {
                            original
                        }
                        tempFile.outputStream().use { output ->
                            resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, output)
                        }
                        if (resized != original) resized.recycle()
                        original.recycle()
                    } else {
                        throw Exception("Failed to decode image")
                    }
                } ?: throw Exception("Cannot read URI")
                val result = repo.uploadImage(tok, tempFile)
                tempFile.delete()
                
                if (result.isSuccess) {
                    // Verify upload (ESP32 returns 200 even if SPIFFS is full and write fails)
                    val verifyBytes = GhostApi.downloadBytes(GhostApi.downloadUrl(filename))
                    if (verifyBytes == null || verifyBytes.isEmpty()) {
                        _sendState.value = SendState.Error("ESP32 Storage is Full! Please wipe the Node in Settings.")
                        return@launch
                    }

                    val msgResult = repo.sendMessage(tok, aesSpec, alias, "IMG:$filename")
                    _sendState.value = if (msgResult.isSuccess) SendState.Sent else SendState.Error("Upload OK, message failed")
                } else {
                    _sendState.value = SendState.Error("Upload failed")
                }
            } catch (e: Exception) {
                _sendState.value = SendState.Error(e.message ?: "Upload failed")
            }
        }
    }

    // ── Phase 1: Delete ─────────────────────────────────────────────
    fun deleteMessageLocal(msgId: String) {
        deletedIds.add(msgId)
        SecurePrefs.addDeletedId(msgId)
        _messages.value = _messages.value.filter { it.id != msgId }
    }

    fun deleteForEveryone(msgId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try { repo.sendMessage(tok, aesSpec, alias, "CMD:DEL:$msgId") } catch (_: Exception) {}
        }
        deleteMessageLocal(msgId)
    }

    fun sendVoiceMemo(file: java.io.File) {
        viewModelScope.launch(Dispatchers.IO) {
            _sendState.value = SendState.Sending
            try {
                val filename = "vox_${System.currentTimeMillis()}.m4a"
                val tempFile = java.io.File(file.parentFile, filename)
                file.copyTo(tempFile, overwrite = true)
                
                val result = repo.uploadImage(tok, tempFile)
                tempFile.delete()
                
                if (result.isSuccess) {
                    val msgResult = repo.sendMessage(tok, aesSpec, alias, "VOX:$filename")
                    _sendState.value = if (msgResult.isSuccess) SendState.Sent else SendState.Error("Audio sent, msg failed")
                } else {
                    _sendState.value = SendState.Error("Audio upload failed")
                }
            } catch (e: Exception) {
                _sendState.value = SendState.Error(e.message ?: "Upload failed")
            }
        }
    }
    fun sendTyping() {
        viewModelScope.launch(Dispatchers.IO) {
            try { GhostApi.sendTyping(tok, alias) } catch (_: Exception) {}
        }
    }

    fun fetchMessagesOnce() {
        viewModelScope.launch(Dispatchers.IO) {
            try { repo.readMessages(tok, aesSpec, alias) } catch (_: Exception) {}
        }
    }

    fun leaveRoom() {
        viewModelScope.launch(Dispatchers.IO) {
            try { GhostApi.sendRaw("CMD:LEAVE:$alias") } catch (_: Exception) {}
        }
    }

    // ── Factory ─────────────────────────────────────────────────────
    class Factory(
        private val alias: String,
        private val key: String,
        private val tok: String,
        private val roomHash: String,
        private val aesKey: SecretKey
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(alias, key, tok, roomHash, aesKey) as T
        }
    }
}
