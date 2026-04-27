package com.acronet.system

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AcroNetMeshSync — Multi-Device PC Pairing (Zero Internet)
 *
 * Desktop AcroNet client generates a QR code containing:
 *   - Ephemeral Ed25519 public key
 *   - Local IP:Port
 *
 * Android scans QR → establishes encrypted Bluetooth or Wi-Fi Direct tunnel:
 *   1. Scan QR with CameraX (ephemeral pubkey + IP/Port)
 *   2. Derive shared secret via X25519 DH with QR pubkey
 *   3. Open encrypted TCP tunnel (LAN) or BluetoothServerSocket
 *   4. Transfer: SQLCipher keys, seed phrase derivations, message history
 *   5. Devices form a closed dark-web intranet
 *
 * NO internet traffic. Everything stays on the local network.
 */
object AcroNetMeshSync {

    private const val SYNC_SERVICE_TYPE = "_acronetsync._tcp."
    private const val SYNC_SERVICE_NAME = "AcroSync"
    private const val SYNC_PORT = 9191
    private const val BT_UUID_STRING = "a7f3c1d4-2b9e-4f08-8d5a-6e7f1c3b2a90"

    private val rng = SecureRandom()

    data class PairingQR(
        val ephemeralPubkey: ByteArray, // 32-byte X25519 public key
        val ipAddress: String,
        val port: Int,
        val transportType: TransportType
    )

    enum class TransportType { TCP_LAN, BLUETOOTH }

    data class SyncPayload(
        val dbMasterKeyHex: String,          // SQLCipher master key
        val seedPhrase: List<String>,        // BIP39 12 words
        val nostrPrivateKeyHex: String,      // Nostr signing key
        val messageHistory: ByteArray        // Encrypted DB export
    )

    // ── QR Code Parsing ─────────────────────────────────────────────

    fun parseQRPayload(qrData: String): PairingQR? {
        return try {
            // Format: "ACROSYNC|<pubkey_hex>|<ip>|<port>|<transport>"
            val parts = qrData.split("|")
            if (parts.size != 5 || parts[0] != "ACROSYNC") return null

            PairingQR(
                ephemeralPubkey = hexToBytes(parts[1]),
                ipAddress = parts[2],
                port = parts[3].toInt(),
                transportType = TransportType.valueOf(parts[4])
            )
        } catch (e: Exception) { null }
    }

    /** Generate a QR payload string for the Android device to display */
    fun generateQRPayload(pubkey: ByteArray, ip: String, port: Int, transport: TransportType): String {
        return "ACROSYNC|${bytesToHex(pubkey)}|$ip|$port|${transport.name}"
    }

    // ── TCP/LAN Sync ────────────────────────────────────────────────

    suspend fun syncViaTCP(
        pairingInfo: PairingQR,
        sessionKey: ByteArray,
        payload: SyncPayload
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val socket = Socket(pairingInfo.ipAddress, pairingInfo.port)
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            // Serialize and encrypt the sync payload
            val payloadBytes = serializePayload(payload)
            val encrypted = encryptPayload(payloadBytes, sessionKey)

            // Send length + encrypted data
            output.writeInt(encrypted.size)
            output.write(encrypted)
            output.flush()

            // Zero sensitive data
            payloadBytes.fill(0x00)

            socket.close()
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Listen for incoming TCP sync connections from a desktop client */
    suspend fun listenForTCPSync(
        sessionKey: ByteArray,
        onPayloadReceived: (SyncPayload) -> Unit
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val server = ServerSocket(SYNC_PORT)
            val socket = server.accept()
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

            val size = input.readInt()
            val encrypted = ByteArray(size)
            input.readFully(encrypted)

            val decrypted = decryptPayload(encrypted, sessionKey)
            val payload = deserializePayload(decrypted)
            decrypted.fill(0x00)

            socket.close()
            server.close()

            onPayloadReceived(payload)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Bluetooth Sync ──────────────────────────────────────────────

    @Suppress("MissingPermission")
    suspend fun syncViaBluetooth(
        sessionKey: ByteArray,
        payload: SyncPayload
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val btAdapter = BluetoothAdapter.getDefaultAdapter()
                ?: return@withContext Result.failure(Exception("Bluetooth unavailable"))

            val uuid = java.util.UUID.fromString(BT_UUID_STRING)
            val serverSocket: BluetoothServerSocket =
                btAdapter.listenUsingInsecureRfcommWithServiceRecord(SYNC_SERVICE_NAME, uuid)

            val socket: BluetoothSocket = serverSocket.accept(30_000) // 30s timeout
            serverSocket.close()

            val output = DataOutputStream(BufferedOutputStream(socket.outputStream))
            val payloadBytes = serializePayload(payload)
            val encrypted = encryptPayload(payloadBytes, sessionKey)

            output.writeInt(encrypted.size)
            output.write(encrypted)
            output.flush()

            payloadBytes.fill(0x00)
            socket.close()

            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Serialization ───────────────────────────────────────────────

    private fun serializePayload(payload: SyncPayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeUTF(payload.dbMasterKeyHex)
        dos.writeInt(payload.seedPhrase.size)
        payload.seedPhrase.forEach { dos.writeUTF(it) }
        dos.writeUTF(payload.nostrPrivateKeyHex)
        dos.writeInt(payload.messageHistory.size)
        dos.write(payload.messageHistory)

        return baos.toByteArray()
    }

    private fun deserializePayload(data: ByteArray): SyncPayload {
        val dis = DataInputStream(ByteArrayInputStream(data))

        val dbKey = dis.readUTF()
        val wordCount = dis.readInt()
        val words = (0 until wordCount).map { dis.readUTF() }
        val nostrKey = dis.readUTF()
        val historySize = dis.readInt()
        val history = ByteArray(historySize)
        dis.readFully(history)

        return SyncPayload(dbKey, words, nostrKey, history)
    }

    // ── Crypto ──────────────────────────────────────────────────────

    private fun encryptPayload(data: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(data)
    }

    private fun decryptPayload(data: ByteArray, key: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, 12)
        val ct = data.copyOfRange(12, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
