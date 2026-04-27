package com.ghost.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Ghost Network API Client — ESP32 Communication Layer
 *
 * All methods are suspend functions running on Dispatchers.IO.
 * This class is called ONLY by GhostRepository — never by Fragments.
 */
object GhostApi {

    private const val BASE = "http://192.168.4.1"

    // OkHttp client with tight timeouts for local mesh network
    private var client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Rebind OkHttp to a specific Network's SocketFactory (for WiFi lockdown) */
    fun rebindToNetwork(network: android.net.Network?) {
        val builder = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (network != null) {
            builder.socketFactory(network.socketFactory)
        }

        client = builder.build()
    }

    /** Login to ESP32 room. Returns auth token on success. */
    suspend fun login(roomHash: String, pass: String, name: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE/login?room=$roomHash&pass=${enc(pass)}&name=${enc(name)}"
                val req = Request.Builder().url(url).get().build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                resp.close()

                when {
                    body.startsWith("WIPED") -> Result.failure(Exception("☠ Security Wipe Triggered — Room destroyed"))
                    body.startsWith("TOK:") -> Result.success(body.substring(4))
                    else -> Result.failure(Exception("Invalid credentials — check room key"))
                }
            } catch (e: IOException) {
                Result.failure(Exception("⚠ Ghost Node unreachable. Connect to Ghost_Net WiFi first."))
            }
        }

    /** Read all messages from the current room */
    suspend fun readMessages(tok: String): Result<Triple<String, String, String>> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE/read?tok=$tok"
                val req = Request.Builder().url(url).get().build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                resp.close()

                val onlineParts = body.split("###ONLINE:")
                val onlineCount = if (onlineParts.size > 1) onlineParts[1].trim() else "0"
                
                val typeParts = onlineParts[0].split("###TYPE:")
                val messageData = typeParts[0]
                val typingUser = if (typeParts.size > 1) typeParts[1].split("|")[0] else ""

                Result.success(Triple(messageData, onlineCount, typingUser))
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    /** Send an encrypted message blob to the ESP32 */
    suspend fun sendMessage(tok: String, encryptedMsg: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE/send?tok=$tok&msg=${enc(encryptedMsg)}"
                val req = Request.Builder().url(url).get().build()
                val resp = client.newCall(req).execute()
                resp.close()
                Result.success(true)
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    /** Upload a file (image) to the ESP32 over multipart */
    suspend fun uploadFile(tok: String, file: File): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val mediaType = "image/jpeg".toMediaType()
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
                    .build()

                val req = Request.Builder()
                    .url("$BASE/upload?tok=$tok")
                    .post(body)
                    .build()

                val resp = client.newCall(req).execute()
                val ok = resp.isSuccessful
                resp.close()
                Result.success(ok)
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    /** Check LoRa radio status and signal strength */
    suspend fun loraStatus(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE/lorastatus").get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            val parts = body.split(":")
            if (parts[0] == "OK") Result.success("${parts[1]} dBm")
            else Result.success("Offline")
        } catch (e: IOException) {
            Result.success("Offline")
        }
    }

    /** Send typing notification */
    suspend fun sendTyping(tok: String, name: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE/typing?tok=$tok&name=${enc(name)}").get().build()
            client.newCall(req).execute().close()
            Result.success(true)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    fun downloadUrl(filename: String): String = "$BASE/download?name=$filename"

    /** Download raw bytes using the bound OkHttp client (forces WiFi instead of LTE) */
    suspend fun downloadBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).get().build()
            val resp = client.newCall(req).execute()
            val bytes = if (resp.isSuccessful) resp.body?.bytes() else null
            resp.close()
            bytes
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Send a raw command string to ESP32 (fire-and-forget).
     * Used for: DEL:<id>, CMD:WIPE, CMD:LEAVE
     * Lightweight — max 20 bytes over LoRa.
     */
    fun sendRaw(command: String) {
        try {
            val url = "$BASE/cmd?data=${enc(command)}"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().close()
        } catch (_: Exception) {}
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
