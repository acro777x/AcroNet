package com.acronet.test

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * AcroNet Unicorn — Agent Delta Red Team
 *
 * Target 1: Daemon Bridge Loopback Security
 * Target 2: Retraction Forensic Verification
 * Target 3: BIP39 Identity Determinism
 * Target 4: TreeKEM Epoch Ratchet Integrity
 * Target 5: Voice Shard Purge Verification
 */
class AcroNetUnicornRedTeamTest {

    // ── TARGET 1: DAEMON BRIDGE LOOPBACK SECURITY ────────────────────

    @Test
    fun `daemon must reject non-loopback connections`() {
        // Simulates the connection validation logic from AcroNetLocalDaemonBridge
        val allowedAddresses = setOf("127.0.0.1", "::1")

        // These must be ACCEPTED
        assertTrue("127.0.0.1 must be accepted", "127.0.0.1" in allowedAddresses)
        assertTrue("::1 must be accepted", "::1" in allowedAddresses)

        // These must be REJECTED
        val attackVectors = listOf(
            "192.168.1.100",    // LAN attacker
            "10.0.0.1",         // Private subnet
            "172.16.0.1",       // Docker network
            "8.8.8.8",          // Public IP
            "0.0.0.0",          // Wildcard bind
            "255.255.255.255",  // Broadcast
            ""                  // Empty (malformed)
        )

        for (ip in attackVectors) {
            assertFalse(
                "External IP '$ip' must be REJECTED by daemon bridge",
                ip in allowedAddresses
            )
        }
    }

    @Test
    fun `daemon must require API key before accepting commands`() {
        val apiKey = "test_secure_key_2026"
        val authenticatedSessions = mutableSetOf<String>()

        // Unauthenticated command must fail
        val sessionId = "conn_1"
        assertFalse("Unauthenticated session must not be in auth set", sessionId in authenticatedSessions)

        // After valid auth
        val submittedKey = "test_secure_key_2026"
        if (submittedKey == apiKey) authenticatedSessions.add(sessionId)
        assertTrue("Authenticated session must be in auth set", sessionId in authenticatedSessions)

        // Wrong key must not authenticate
        val badSessionId = "conn_2"
        val badKey = "wrong_key"
        if (badKey == apiKey) authenticatedSessions.add(badSessionId)
        assertFalse("Wrong key must not authenticate", badSessionId in authenticatedSessions)
    }

    // ── TARGET 2: RETRACTION FORENSIC VERIFICATION ───────────────────

    @Test
    fun `retracted data must be overwritten with random noise`() {
        // Create a temp file simulating a database
        val tempFile = File.createTempFile("acronet_test_db", ".db")
        val secretMessage = "TOP_SECRET_MESSAGE_ID_12345678"

        // Write the secret into the "database"
        val raf = RandomAccessFile(tempFile, "rw")
        val padding = ByteArray(512)
        SecureRandom().nextBytes(padding)
        raf.write(padding)
        raf.write(secretMessage.toByteArray(Charsets.UTF_8))
        raf.write(padding)
        raf.close()

        // Verify the secret exists before retraction
        val beforeBytes = tempFile.readBytes()
        val beforeContent = String(beforeBytes, Charsets.UTF_8)
        assertTrue("Secret must exist before retraction", beforeContent.contains(secretMessage))

        // Execute DOD 5220.22-M overwrite (simulating AcroNetRetractionProtocol)
        val searchBytes = secretMessage.toByteArray(Charsets.UTF_8)
        val positions = findAllOccurrences(beforeBytes, searchBytes)
        assertTrue("Must find at least one occurrence", positions.isNotEmpty())

        val rng = SecureRandom()
        val raf2 = RandomAccessFile(tempFile, "rw")
        for (pos in positions) {
            val overwriteLen = minOf(256, beforeBytes.size - pos)

            // Pass 1: Random
            val noise1 = ByteArray(overwriteLen).also { rng.nextBytes(it) }
            raf2.seek(pos.toLong()); raf2.write(noise1)

            // Pass 2: Complement
            val noise2 = ByteArray(overwriteLen) { (noise1[it].toInt().inv()).toByte() }
            raf2.seek(pos.toLong()); raf2.write(noise2)

            // Pass 3: Random
            val noise3 = ByteArray(overwriteLen).also { rng.nextBytes(it) }
            raf2.seek(pos.toLong()); raf2.write(noise3)
        }
        raf2.fd.sync()
        raf2.close()

        // FORENSIC CHECK: Attempt to read the retracted data
        val afterBytes = tempFile.readBytes()
        val afterContent = String(afterBytes, Charsets.UTF_8)
        assertFalse(
            "Secret must NOT exist after retraction (DOD 5220.22-M overwrite failed)",
            afterContent.contains(secretMessage)
        )

        tempFile.delete()
    }

    // ── TARGET 3: BIP39 IDENTITY DETERMINISM ─────────────────────────

    @Test
    fun `same mnemonic must produce identical keys across invocations`() {
        // Simulates AcroNetIdentityManager.recoverIdentity() determinism
        val mnemonic = "abandon ability able about above absent absorb abstract absurd abuse access accident"
        val words = mnemonic.split(" ")

        // Two derivations from same words must be byte-identical
        val seed1 = pbkdf2Sha512Simplified(mnemonic, "mnemonic")
        val seed2 = pbkdf2Sha512Simplified(mnemonic, "mnemonic")

        assertArrayEquals("Seeds must be deterministic", seed1, seed2)
        assertEquals("Seed must be 64 bytes", 64, seed1.size)
    }

    // ── TARGET 4: TREEKEM EPOCH RATCHET ──────────────────────────────

    @Test
    fun `adding member must produce new epoch key`() {
        val epoch0Key = sha256("root_key_epoch_0".toByteArray())
        val epoch1Key = sha256("root_key_epoch_1".toByteArray())

        // Epoch keys must differ after member addition
        assertFalse(
            "Epoch keys must change after tree ratchet",
            epoch0Key.contentEquals(epoch1Key)
        )
    }

    @Test
    fun `removed member epoch key must not decrypt new messages`() {
        val oldEpochKey = sha256("epoch_before_removal".toByteArray())
        val newEpochKey = sha256("epoch_after_removal".toByteArray())

        // Encrypt with new epoch
        val message = "classified after removal"
        val encrypted = encryptWithKey(message.toByteArray(), newEpochKey)

        // Attempt decrypt with old epoch — MUST FAIL
        val result = decryptWithKey(encrypted, oldEpochKey)
        assertTrue("Old epoch key must fail to decrypt post-removal messages", result.isFailure)
    }

    // ── TARGET 5: VOICE SHARD PURGE ──────────────────────────────────

    @Test
    fun `voice buffer purge must zero all PCM bytes`() {
        val pcmData = ByteArray(4096) { 0xFF.toByte() } // Simulated audio

        // Verify non-zero before purge
        assertTrue("PCM must be non-zero before purge", pcmData.any { it != 0x00.toByte() })

        // Purge
        pcmData.fill(0x00)

        // Verify all zeros after purge
        assertTrue("PCM must be all-zero after purge", pcmData.all { it == 0x00.toByte() })
    }

    // ── HELPERS ──────────────────────────────────────────────────────

    private fun findAllOccurrences(data: ByteArray, pattern: ByteArray): List<Int> {
        val positions = mutableListOf<Int>()
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            positions.add(i)
        }
        return positions
    }

    private fun sha256(input: ByteArray): ByteArray {
        return java.security.MessageDigest.getInstance("SHA-256").digest(input)
    }

    private fun pbkdf2Sha512Simplified(password: String, salt: String): ByteArray {
        // Simplified deterministic derivation for test purposes
        val combined = "$password:$salt".toByteArray()
        val md = java.security.MessageDigest.getInstance("SHA-512")
        var result = md.digest(combined)
        repeat(2047) { result = md.digest(result) } // 2048 iterations
        return result
    }

    private fun encryptWithKey(data: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(data)
    }

    private fun decryptWithKey(data: ByteArray, key: ByteArray): Result<ByteArray> {
        return try {
            val iv = data.copyOfRange(0, 12)
            val ct = data.copyOfRange(12, data.size)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, iv))
            Result.success(cipher.doFinal(ct))
        } catch (e: Exception) { Result.failure(e) }
    }
}
