package com.ghost.app

import android.util.Base64
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec

/**
 * AcroNet Crypto Red Team — Agent Gamma
 *
 * Target 1: AES-GCM Authentication Tag Tampering
 *   Verifies that flipping ANY byte in the ciphertext or auth tag
 *   causes decryptAES() to return Result.failure with AEADBadTagException.
 *
 * Target 2: Key Mismatch Detection
 *   Verifies that decrypting with the wrong key fails cleanly.
 */
class AcroNetCryptoTest {

    private val TEST_PASSWORD = "scrapyard_test_key_2026"
    private val TEST_MESSAGE = "GHO:|SYS|12:00|This is a classified test message for Team Scrapyard"

    // ── TARGET 1: Auth Tag Tampering ─────────────────────────────────

    @Test
    fun `tampered last byte of auth tag must return Result failure`() {
        val key = GhostCrypto.deriveKey(TEST_PASSWORD)
        val ciphertext = GhostCrypto.encryptAES(TEST_MESSAGE, key)

        // Decode, flip last byte (inside GCM auth tag), re-encode
        val raw = Base64.decode(ciphertext, Base64.DEFAULT)
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0xFF).toByte()
        val tampered = Base64.encodeToString(raw, Base64.NO_WRAP)

        // Must return Result.failure — NOT a valid plaintext string
        val result = GhostCrypto.decryptAES(tampered, key)
        assertTrue("Tampered ciphertext must fail decryption", result.isFailure)

        // The exception must be AEADBadTagException specifically
        val exception = result.exceptionOrNull()
        assertNotNull("Exception must not be null", exception)
        assertTrue(
            "Exception must be AEADBadTagException, got: ${exception?.javaClass?.name}",
            exception is AEADBadTagException
        )
    }

    @Test
    fun `tampered first byte of ciphertext must return Result failure`() {
        val key = GhostCrypto.deriveKey(TEST_PASSWORD)
        val ciphertext = GhostCrypto.encryptAES(TEST_MESSAGE, key)

        // Flip first byte of actual ciphertext (after 12-byte IV)
        val raw = Base64.decode(ciphertext, Base64.DEFAULT)
        raw[12] = (raw[12].toInt() xor 0xFF).toByte()
        val tampered = Base64.encodeToString(raw, Base64.NO_WRAP)

        val result = GhostCrypto.decryptAES(tampered, key)
        assertTrue("Tampered ciphertext body must fail", result.isFailure)
    }

    @Test
    fun `tampered IV must return Result failure`() {
        val key = GhostCrypto.deriveKey(TEST_PASSWORD)
        val ciphertext = GhostCrypto.encryptAES(TEST_MESSAGE, key)

        // Flip first byte of IV
        val raw = Base64.decode(ciphertext, Base64.DEFAULT)
        raw[0] = (raw[0].toInt() xor 0xFF).toByte()
        val tampered = Base64.encodeToString(raw, Base64.NO_WRAP)

        val result = GhostCrypto.decryptAES(tampered, key)
        assertTrue("Tampered IV must fail", result.isFailure)
    }

    // ── TARGET 2: Key Mismatch ───────────────────────────────────────

    @Test
    fun `wrong key must return Result failure`() {
        val correctKey = GhostCrypto.deriveKey(TEST_PASSWORD)
        val wrongKey = GhostCrypto.deriveKey("wrong_password_entirely")
        val ciphertext = GhostCrypto.encryptAES(TEST_MESSAGE, correctKey)

        val result = GhostCrypto.decryptAES(ciphertext, wrongKey)
        assertTrue("Wrong key must fail decryption", result.isFailure)
    }

    // ── TARGET 3: Valid Decryption Baseline ──────────────────────────

    @Test
    fun `valid ciphertext must return Result success with correct plaintext`() {
        val key = GhostCrypto.deriveKey(TEST_PASSWORD)
        val ciphertext = GhostCrypto.encryptAES(TEST_MESSAGE, key)

        val result = GhostCrypto.decryptAES(ciphertext, key)
        assertTrue("Valid decryption must succeed", result.isSuccess)
        assertEquals(TEST_MESSAGE, result.getOrNull())
    }

    @Test
    fun `truncated ciphertext must return Result failure`() {
        val key = GhostCrypto.deriveKey(TEST_PASSWORD)
        
        // Ciphertext too short (less than IV + tag = 28 bytes)
        val shortData = Base64.encodeToString(ByteArray(20), Base64.NO_WRAP)
        val result = GhostCrypto.decryptAES(shortData, key)
        assertTrue("Truncated ciphertext must fail", result.isFailure)
    }

    // ── TARGET 4: Room Hash Determinism ──────────────────────────────

    @Test
    fun `genHash must be deterministic`() {
        val hash1 = GhostCrypto.genHash("test_room_password")
        val hash2 = GhostCrypto.genHash("test_room_password")
        assertEquals("Hash must be deterministic", hash1, hash2)
        assertEquals("Hash must be 8 chars", 8, hash1.length)
    }

    @Test
    fun `different passwords must produce different hashes`() {
        val hash1 = GhostCrypto.genHash("password_alpha")
        val hash2 = GhostCrypto.genHash("password_bravo")
        assertNotEquals("Different passwords must hash differently", hash1, hash2)
    }

    // ── TARGET 5: Message ID Stability ───────────────────────────────

    @Test
    fun `message ID must be deterministic across invocations`() {
        val id1 = GhostCrypto.generateMessageId("Alice", "12:00", "Hello Bob")
        val id2 = GhostCrypto.generateMessageId("Alice", "12:00", "Hello Bob")
        assertEquals(id1, id2)
        assertEquals("Message ID must be 8 chars", 8, id1.length)
    }
}
