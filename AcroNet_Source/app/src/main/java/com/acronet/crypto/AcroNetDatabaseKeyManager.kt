package com.acronet.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * AcroNetDatabaseKeyManager — SQLCipher Rolling-Key Protocol
 *
 * Every 24 hours, the database master key is rotated:
 *   1. Load current key from EncryptedSharedPreferences
 *   2. Generate new 256-bit key via SecureRandom
 *   3. Re-encrypt database via PRAGMA rekey
 *   4. Store new key, zero-fill old key bytes
 *
 * If the device is seized AFTER rotation, the previous day's
 * messages are protected by a key that no longer exists anywhere.
 */
object AcroNetDatabaseKeyManager {

    private const val PREFS_NAME = "acronet_db_keys"
    private const val KEY_DB_MASTER = "db_master_key"
    private const val KEY_LAST_ROTATION = "last_key_rotation"
    private const val ROTATION_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context, PREFS_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Get the current database master key.
     * If no key exists (first launch), generate one.
     */
    fun getCurrentKey(): ByteArray {
        val stored = prefs?.getString(KEY_DB_MASTER, null)
        if (stored != null) {
            return hexToBytes(stored)
        }
        // First launch: generate initial key
        val newKey = generateKey()
        storeKey(newKey)
        return newKey
    }

    /**
     * Get the current key as a hex string for SQLCipher PRAGMA key.
     */
    fun getCurrentKeyHex(): String {
        return bytesToHex(getCurrentKey())
    }

    /**
     * Check if 24-hour rotation is due and execute if needed.
     * Returns the PRAGMA rekey command if rotation occurred, null otherwise.
     *
     * Caller must execute: database.query("PRAGMA rekey = 'x\"<newKeyHex>\"'")
     */
    fun checkAndRotate(): RotationResult {
        val lastRotation = prefs?.getLong(KEY_LAST_ROTATION, 0L) ?: 0L
        val now = System.currentTimeMillis()

        if (now - lastRotation < ROTATION_INTERVAL_MS) {
            return RotationResult(rotated = false, newKeyHex = null)
        }

        // Generate new key
        val oldKeyBytes = getCurrentKey()
        val newKey = generateKey()
        val newKeyHex = bytesToHex(newKey)

        // Store new key (atomically replaces old)
        storeKey(newKey)
        prefs?.edit()?.putLong(KEY_LAST_ROTATION, now)?.apply()

        // Zero old key material
        oldKeyBytes.fill(0x00)

        return RotationResult(rotated = true, newKeyHex = newKeyHex)
    }

    /**
     * Nuclear wipe: destroy the database key entirely.
     * The database becomes permanently unrecoverable.
     */
    fun destroyKey() {
        prefs?.edit()?.clear()?.apply()
    }

    // ── Internal ─────────────────────────────────────────────────────

    private fun generateKey(): ByteArray {
        val key = ByteArray(32) // 256-bit
        SecureRandom().nextBytes(key)
        return key
    }

    private fun storeKey(key: ByteArray) {
        prefs?.edit()?.putString(KEY_DB_MASTER, bytesToHex(key))?.apply()
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    data class RotationResult(
        val rotated: Boolean,
        val newKeyHex: String?
    )
}
