package com.acronet.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKEMExtractor
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKEMGenerator
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKeyGenerationParameters
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKeyPairGenerator
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberParameters
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberPrivateKeyParameters
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberPublicKeyParameters
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

/**
 * AcroNetKeyExchange — Post-Quantum Hybrid Key Agreement
 *
 * Combines X25519 (classical ECDH) with Kyber-512 (NIST ML-KEM)
 * to produce a session key that survives both classical and quantum attacks.
 *
 * Protocol:
 *   1. Both parties generate ephemeral X25519 + Kyber keypairs
 *   2. Exchange public keys (X25519 pubkey + Kyber pubkey)
 *   3. Initiator: encapsulate Kyber → (ciphertext, shared_secret_pq)
 *   4. Both: X25519 agree → shared_secret_classical
 *   5. Combined: HKDF(classical || pq) → AES-256-GCM session key
 *   6. ZEROING: All private key bytes overwritten with 0x00
 *
 * Security: If EITHER X25519 OR Kyber survives quantum attack,
 *           the session key remains unrecoverable.
 */
object AcroNetKeyExchange {

    private const val HKDF_SALT = "AcroNet-Horizon-V2"
    private const val SESSION_KEY_LENGTH = 32 // 256-bit AES key

    /**
     * Ephemeral keypair bundle for one side of the handshake.
     * MUST be zeroed after key agreement completes.
     */
    data class KeyBundle(
        // X25519
        val x25519Private: X25519PrivateKeyParameters,
        val x25519Public: X25519PublicKeyParameters,
        val x25519PublicBytes: ByteArray,
        // Kyber-512
        val kyberPrivate: KyberPrivateKeyParameters,
        val kyberPublic: KyberPublicKeyParameters,
        val kyberPublicBytes: ByteArray
    ) {
        /** Zero all private key material */
        fun destroy() {
            x25519Private.encoded.fill(0x00)
            kyberPrivate.encoded.fill(0x00)
        }
    }

    /**
     * Result of a completed key exchange.
     */
    data class SessionResult(
        val sessionKey: SecretKeySpec,
        val kyberCiphertext: ByteArray? // non-null for initiator, null for responder
    )

    // ── STEP 1: Generate ephemeral keypairs ─────────────────────────

    fun generateKeyBundle(): KeyBundle {
        val rng = SecureRandom()

        // X25519 keypair
        val x25519Gen = X25519KeyPairGenerator()
        x25519Gen.init(X25519KeyGenerationParameters(rng))
        val x25519Pair = x25519Gen.generateKeyPair()
        val x25519Priv = x25519Pair.private as X25519PrivateKeyParameters
        val x25519Pub = x25519Pair.public as X25519PublicKeyParameters

        // Kyber-512 keypair
        val kyberGen = KyberKeyPairGenerator()
        kyberGen.init(KyberKeyGenerationParameters(rng, KyberParameters.kyber512))
        val kyberPair = kyberGen.generateKeyPair()
        val kyberPriv = kyberPair.private as KyberPrivateKeyParameters
        val kyberPub = kyberPair.public as KyberPublicKeyParameters

        return KeyBundle(
            x25519Private = x25519Priv,
            x25519Public = x25519Pub,
            x25519PublicBytes = x25519Pub.encoded,
            kyberPrivate = kyberPriv,
            kyberPublic = kyberPub,
            kyberPublicBytes = kyberPub.encoded
        )
    }

    // ── STEP 2 (Initiator): Encapsulate + Agree ─────────────────────

    /**
     * Called by the INITIATOR (Alice).
     * Takes Bob's public keys, produces:
     *   - The AES-256 session key
     *   - The Kyber ciphertext (to send to Bob)
     */
    fun initiateKeyExchange(
        myBundle: KeyBundle,
        remotePubX25519: ByteArray,
        remotePubKyber: ByteArray
    ): SessionResult {
        // X25519 key agreement
        val remoteX25519 = X25519PublicKeyParameters(remotePubX25519, 0)
        val x25519Agreement = X25519Agreement()
        x25519Agreement.init(myBundle.x25519Private)
        val classicalSecret = ByteArray(32)
        x25519Agreement.calculateAgreement(remoteX25519, classicalSecret, 0)

        // Kyber-512 encapsulation (initiator generates ciphertext)
        val remoteKyber = KyberPublicKeyParameters(KyberParameters.kyber512, remotePubKyber)
        val kemGen = KyberKEMGenerator(SecureRandom())
        val encapsulated = kemGen.generateEncapsulated(remoteKyber)
        val kyberCiphertext = encapsulated.encapsulation
        val pqSecret = encapsulated.secret

        // Derive session key: HKDF(classical || pq)
        val sessionKey = deriveSessionKey(classicalSecret, pqSecret)

        // ZEROING: Destroy intermediate secrets
        classicalSecret.fill(0x00)
        pqSecret.fill(0x00)
        myBundle.destroy()

        return SessionResult(sessionKey = sessionKey, kyberCiphertext = kyberCiphertext)
    }

    // ── STEP 3 (Responder): Decapsulate + Agree ─────────────────────

    /**
     * Called by the RESPONDER (Bob).
     * Takes Alice's public keys + Kyber ciphertext, produces the same session key.
     */
    fun completeKeyExchange(
        myBundle: KeyBundle,
        remotePubX25519: ByteArray,
        kyberCiphertext: ByteArray
    ): SessionResult {
        // X25519 key agreement
        val remoteX25519 = X25519PublicKeyParameters(remotePubX25519, 0)
        val x25519Agreement = X25519Agreement()
        x25519Agreement.init(myBundle.x25519Private)
        val classicalSecret = ByteArray(32)
        x25519Agreement.calculateAgreement(remoteX25519, classicalSecret, 0)

        // Kyber-512 decapsulation (responder extracts shared secret)
        val kemExtractor = KyberKEMExtractor(myBundle.kyberPrivate)
        val pqSecret = kemExtractor.extractSecret(kyberCiphertext)

        // Derive session key: HKDF(classical || pq)
        val sessionKey = deriveSessionKey(classicalSecret, pqSecret)

        // ZEROING
        classicalSecret.fill(0x00)
        pqSecret.fill(0x00)
        myBundle.destroy()

        return SessionResult(sessionKey = sessionKey, kyberCiphertext = null)
    }

    // ── HKDF Key Derivation ─────────────────────────────────────────

    private fun deriveSessionKey(
        classicalSecret: ByteArray,
        pqSecret: ByteArray
    ): SecretKeySpec {
        // Concatenate: classical_secret || pq_secret
        val combined = classicalSecret + pqSecret
        val salt = HKDF_SALT.toByteArray(Charsets.UTF_8)
        val info = "AcroNet-SessionKey-V2".toByteArray(Charsets.UTF_8)

        // HKDF-SHA256
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(combined, salt, info))
        val sessionKeyBytes = ByteArray(SESSION_KEY_LENGTH)
        hkdf.generateBytes(sessionKeyBytes, 0, SESSION_KEY_LENGTH)

        // Zero the combined intermediate
        combined.fill(0x00)

        return SecretKeySpec(sessionKeyBytes, "AES")
    }
}
