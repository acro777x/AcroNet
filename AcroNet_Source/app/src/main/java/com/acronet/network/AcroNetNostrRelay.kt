package com.acronet.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * AcroNetNostrRelay — Decentralized NIP-01 Transport Layer
 *
 * Connects to disposable Nostr WebSocket relays for global message delivery.
 * All events are blinded: throwaway pubkeys, zeroed timestamps, encrypted content.
 * Rotates through multiple relays to prevent traffic profiling.
 *
 * Wire format (NIP-01):
 *   ["EVENT", { id, pubkey, created_at:0, kind:4, tags, content, sig }]
 *   ["REQ", subscription_id, { kinds:[4], "#p":[recipient_pubkey] }]
 */
object AcroNetNostrRelay {

    // Disposable community relays — rotated to prevent profiling
    private val RELAY_POOL = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.snort.social",
        "wss://relay.nostr.band",
        "wss://nostr.wine",
        "wss://relay.current.fyi",
        "wss://eden.nostr.land",
        "wss://nostr-pub.wellorder.net",
        "wss://relay.nostr.info",
        "wss://nostr.fmt.wiz.biz"
    )

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var activeSocket: WebSocket? = null
    private var activeRelayUrl: String? = null
    private val rng = SecureRandom()

    // Incoming events as a Flow
    private val _incomingEvents = MutableSharedFlow<NostrEvent>(replay = 0, extraBufferCapacity = 64)
    val incomingEvents: SharedFlow<NostrEvent> = _incomingEvents.asSharedFlow()

    private val _connectionState = MutableStateFlow(RelayState.DISCONNECTED)
    val connectionState: StateFlow<RelayState> = _connectionState.asStateFlow()

    // ── Connect to a random relay ───────────────────────────────────

    fun connect() {
        disconnect()
        val relayUrl = RELAY_POOL[rng.nextInt(RELAY_POOL.size)]
        activeRelayUrl = relayUrl

        val request = Request.Builder()
            .url(relayUrl)
            .header("User-Agent", "") // Strip user-agent to prevent fingerprinting
            .build()

        _connectionState.value = RelayState.CONNECTING

        activeSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = RelayState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseRelayMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = RelayState.DISCONNECTED
                // Auto-reconnect to a different relay after 5s
                CoroutineScope(Dispatchers.IO).launch {
                    delay(5000)
                    connect()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = RelayState.DISCONNECTED
            }
        })
    }

    fun disconnect() {
        activeSocket?.close(1000, "AcroNet shutdown")
        activeSocket = null
        _connectionState.value = RelayState.DISCONNECTED
    }

    // ── Publish an encrypted event ──────────────────────────────────

    /**
     * Publish a blinded, encrypted event to the active relay.
     *
     * @param senderPubkey Throwaway Ed25519 pubkey (hex, 64 chars)
     * @param recipientPubkey Blinded recipient pubkey (hex, 64 chars)
     * @param encryptedContent AES-256-GCM encrypted Base64 blob
     * @param signature Schnorr signature of the event (hex)
     */
    fun publishEvent(
        senderPubkey: String,
        recipientPubkey: String,
        encryptedContent: String,
        signature: String
    ): Boolean {
        val socket = activeSocket ?: return false

        val eventId = generateEventId(senderPubkey, encryptedContent)

        val event = JSONObject().apply {
            put("id", eventId)
            put("pubkey", senderPubkey)
            put("created_at", 0) // ZEROED — defeat timestamp analysis
            put("kind", 4) // NIP-04: encrypted direct message
            put("tags", JSONArray().apply {
                put(JSONArray().apply { put("p"); put(recipientPubkey) })
            })
            put("content", encryptedContent)
            put("sig", signature)
        }

        val message = JSONArray().apply {
            put("EVENT")
            put(event)
        }

        return socket.send(message.toString())
    }

    // ── Subscribe to events for our pubkey ───────────────────────────

    fun subscribe(myPubkey: String) {
        val socket = activeSocket ?: return

        val subId = "acro_" + ByteArray(8).also { rng.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }

        val filter = JSONObject().apply {
            put("kinds", JSONArray().apply { put(4) })
            put("#p", JSONArray().apply { put(myPubkey) })
        }

        val message = JSONArray().apply {
            put("REQ")
            put(subId)
            put(filter)
        }

        socket.send(message.toString())
    }

    // ── Parse incoming relay messages ────────────────────────────────

    private fun parseRelayMessage(text: String) {
        try {
            val arr = JSONArray(text)
            val type = arr.getString(0)

            when (type) {
                "EVENT" -> {
                    val event = arr.getJSONObject(2)
                    val nostrEvent = NostrEvent(
                        id = event.getString("id"),
                        pubkey = event.getString("pubkey"),
                        content = event.getString("content"),
                        kind = event.getInt("kind")
                    )
                    CoroutineScope(Dispatchers.IO).launch {
                        _incomingEvents.emit(nostrEvent)
                    }
                }
                "OK" -> { /* Event accepted by relay */ }
                "NOTICE" -> { /* Relay notice — log but ignore */ }
            }
        } catch (_: Exception) { /* Malformed relay message — drop silently */ }
    }

    // ── Event ID generation (SHA-256 of canonical JSON) ──────────────

    private fun generateEventId(pubkey: String, content: String): String {
        val canonical = "[0,\"$pubkey\",0,4,[],$content]"
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun getActiveRelay(): String = activeRelayUrl ?: "none"
}

data class NostrEvent(
    val id: String,
    val pubkey: String,
    val content: String,
    val kind: Int
)

enum class RelayState {
    DISCONNECTED, CONNECTING, CONNECTED
}
