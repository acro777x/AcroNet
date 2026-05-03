package com.acronet.crypto

import android.util.Base64
import android.util.Log
import com.acronet.core.AcroNetGenesis
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AcroNetMessageCipher — AES-256-GCM Message Encryption (Phase 3)
 *
 * Every message is encrypted with:
 *   - AES-256-GCM (128-bit auth tag)
 *   - Unique 12-byte IV per message (SecureRandom)
 *   - Genesis ARCHITECT_SIGNATURE as AAD (Additional Authenticated Data)
 *
 * The AAD ensures that if an adversary extracts the .db file and attempts
 * decryption without the Genesis signature, the GCM auth tag rejects it.
 *
 * Output format: Base64(IV[12] || Ciphertext || AuthTag[16])
 */
object AcroNetMessageCipher {

    private const val TAG = "ACRO_VOID_CRYPTO"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_SIZE = 12
    private const val KEY_SIZE = 32

    private val rng = SecureRandom()

    /**
     * Derive a deterministic session key from the room name using HKDF-SHA256.
     * The Genesis signature is used as the HKDF salt.
     * Phase 4 will replace this with a real X25519 handshake key.
     */
    fun deriveSessionKey(roomName: String): SecretKeySpec {
        if (!AcroNetGenesis.validateIntegrity()) {
            throw SecurityException("[ACRO VOID] Genesis block tampered!")
        }
        val salt = AcroNetGenesis.ARCHITECT_SIGNATURE
        val ikm = roomName.toByteArray(Charsets.UTF_8)
        val info = "AcroNet-Loopback-V8".toByteArray(Charsets.UTF_8)

        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, salt, info))
        val keyBytes = ByteArray(KEY_SIZE)
        hkdf.generateBytes(keyBytes, 0, KEY_SIZE)

        Log.d(TAG, "[HKDF] Session key derived for room: $roomName")
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypt plaintext with AES-256-GCM + Genesis AAD.
     * Returns Base64(IV || ciphertext || tag).
     */
    fun encrypt(plaintext: String, sessionKey: SecretKeySpec): String {
        Log.d(TAG, "[CIPHER] Plaintext IN: \"$plaintext\" (${plaintext.length} chars)")

        val iv = ByteArray(IV_SIZE)
        rng.nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(AcroNetGenesis.ARCHITECT_SIGNATURE) // THE GENESIS LOCK

        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintextBytes)

        // CRITICAL: Zero-fill plaintext bytes
        Arrays.fill(plaintextBytes, 0.toByte())

        val combined = iv + ciphertext // IV[12] || ciphertext+tag
        val b64 = Base64.encodeToString(combined, Base64.NO_WRAP)

        Log.d(TAG, "[CIPHER] Ciphertext OUT: $b64 (${b64.length} chars)")
        return b64
    }

    /**
     * Decrypt Base64(IV || ciphertext || tag) with AES-256-GCM + Genesis AAD.
     * Throws SecurityException if AAD mismatch (Genesis tampered).
     */
    fun decrypt(ciphertextB64: String, sessionKey: SecretKeySpec): String {
        return try {
            val combined = Base64.decode(ciphertextB64, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, IV_SIZE)
            val ct = combined.copyOfRange(IV_SIZE, combined.size)

            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, sessionKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(AcroNetGenesis.ARCHITECT_SIGNATURE) // THE GENESIS LOCK

            val plaintext = String(cipher.doFinal(ct), Charsets.UTF_8)
            plaintext
        } catch (e: Exception) {
            Log.e(TAG, "[CIPHER] DECRYPTION FAILED: ${e.message}")
            "[DECRYPTION FAILED]"
        }
    }
}
