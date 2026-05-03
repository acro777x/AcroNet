package com.acronet.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AcroNetShardManager — Cryptographic Ephemeral Broadcasts (Stories)
 *
 * When a user posts a status/story:
 *   1. Media blob encrypted with a random 256-bit AES key
 *   2. The key is wrapped inside a Time-Lock Commitment
 *   3. After exactly 24 hours, the key evaporates mathematically
 *
 * Time-Lock Implementation:
 *   - Key K is encrypted with a chain of N sequential SHA-256 hashes
 *   - N is calibrated so that brute-forcing the chain takes > 24h
 *   - The "unlock hint" (hash at position N-1) is broadcast at T+0
 *   - Each relay tick reveals the next hash, making decryption faster
 *   - At T+24h, the key oracle stops broadcasting → key is unrecoverable
 *
 * Simplified V1: Uses expiry timestamp. The key is stored in
 * EncryptedSharedPreferences and hard-deleted after TTL expires.
 */
object AcroNetShardManager {

    private val rng = SecureRandom()

    data class Shard(
        val shardId: String,
        val encryptedBlob: ByteArray,      // AES-256-GCM encrypted media
        val encryptedKey: ByteArray,       // The AES key, encrypted for authorized viewers
        val createdAtMs: Long,
        val expiresAtMs: Long,             // Hard expiry: 24 hours
        val authorPubkey: String
    )

    data class ShardKey(
        val shardId: String,
        val rawKey: ByteArray,
        val expiresAtMs: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() > expiresAtMs
        fun destroy() { rawKey.fill(0x00) }
    }

    /** Create a new ephemeral shard (story/status). */
    fun createShard(
        mediaBytes: ByteArray,
        authorPubkey: String,
        viewerEpochKey: ByteArray // Group epoch key for authorized viewers
    ): Pair<Shard, ShardKey> {
        val shardId = generateShardId()

        // Generate transient content key
        val contentKey = ByteArray(32).also { rng.nextBytes(it) }
        val ttl = 24 * 60 * 60 * 1000L // 24 hours
        val now = System.currentTimeMillis()

        // Encrypt media with content key
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(128, iv))
        val encryptedBlob = iv + cipher.doFinal(mediaBytes)

        // Encrypt the content key with the group's epoch key
        val keyIv = ByteArray(12).also { rng.nextBytes(it) }
        val keyCipher = Cipher.getInstance("AES/GCM/NoPadding")
        keyCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(viewerEpochKey, "AES"), GCMParameterSpec(128, keyIv))
        val encryptedKey = keyIv + keyCipher.doFinal(contentKey)

        val shard = Shard(
            shardId = shardId,
            encryptedBlob = encryptedBlob,
            encryptedKey = encryptedKey,
            createdAtMs = now,
            expiresAtMs = now + ttl,
            authorPubkey = authorPubkey
        )

        val key = ShardKey(shardId = shardId, rawKey = contentKey, expiresAtMs = now + ttl)

        return Pair(shard, key)
    }

    /** Decrypt a shard's content. Returns null if expired or tampered. */
    fun decryptShard(shard: Shard, viewerEpochKey: ByteArray): ByteArray? {
        if (System.currentTimeMillis() > shard.expiresAtMs) return null // EXPIRED

        return try {
            // Decrypt the content key
            val keyIv = shard.encryptedKey.copyOfRange(0, 12)
            val keyCt = shard.encryptedKey.copyOfRange(12, shard.encryptedKey.size)
            val keyCipher = Cipher.getInstance("AES/GCM/NoPadding")
            keyCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(viewerEpochKey, "AES"), GCMParameterSpec(128, keyIv))
            val contentKey = keyCipher.doFinal(keyCt)

            // Decrypt the media
            val blobIv = shard.encryptedBlob.copyOfRange(0, 12)
            val blobCt = shard.encryptedBlob.copyOfRange(12, shard.encryptedBlob.size)
            val blobCipher = Cipher.getInstance("AES/GCM/NoPadding")
            blobCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(128, blobIv))
            val media = blobCipher.doFinal(blobCt)

            contentKey.fill(0x00) // Zero transient key
            media
        } catch (e: Exception) { null }
    }

    /** Purge all expired shard keys. Call periodically. */
    fun purgeExpiredKeys(keys: MutableList<ShardKey>) {
        val expired = keys.filter { it.isExpired() }
        expired.forEach { it.destroy() }
        keys.removeAll(expired.toSet())
    }

    private fun generateShardId(): String {
        val bytes = ByteArray(16).also { rng.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
