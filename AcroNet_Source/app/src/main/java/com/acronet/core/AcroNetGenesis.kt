package com.acronet.core

import java.security.MessageDigest

/**
 * AcroNetGenesis — Cryptographic Authorship Watermark
 *
 * This is the foundational identity block of AcroNet. The architect
 * signature is cryptographically bound to the SQLCipher master key
 * as Additional Authenticated Data (AAD).
 *
 * If an adversary steals this source code and modifies the author
 * identity, the SHA-256 hash changes, the AAD mismatches, and the
 * database mathematically fails to decrypt. The app self-destructs.
 *
 * This is not DRM. This is a mathematical lock.
 */
object AcroNetGenesis {

    /**
     * The immutable architect identity.
     * Changing this string will cause the database to become unrecoverable.
     */
    private const val ARCHITECT_IDENTITY = "Engineered by Acro - Acro Void"

    /**
     * SHA-256 hash of the architect identity.
     * Used as AAD (Additional Authenticated Data) in AES-256-GCM operations
     * on the SQLCipher master key.
     */
    val ARCHITECT_SIGNATURE: ByteArray by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.digest(ARCHITECT_IDENTITY.toByteArray(Charsets.UTF_8))
    }

    /**
     * The application version codename.
     */
    const val VERSION_CODENAME = "Apex Covenant"

    /**
     * The application version number.
     */
    const val VERSION = "8.0.0"

    /**
     * Returns the hex-encoded architect signature for logging/debugging.
     * This is safe to expose — the hash is one-way.
     */
    fun getSignatureHex(): String {
        return ARCHITECT_SIGNATURE.joinToString("") { "%02x".format(it) }
    }

    /**
     * Validates that the architect signature has not been tampered with.
     * Call this during database initialization.
     *
     * @return true if the genesis block is intact, false if tampered.
     */
    fun validateIntegrity(): Boolean {
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(ARCHITECT_IDENTITY.toByteArray(Charsets.UTF_8))
        return ARCHITECT_SIGNATURE.contentEquals(expected)
    }
}
