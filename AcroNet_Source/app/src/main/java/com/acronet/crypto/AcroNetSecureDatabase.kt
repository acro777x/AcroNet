package com.acronet.crypto

import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.acronet.core.AcroNetGenesis
import net.sqlcipher.database.SQLiteDatabase

/**
 * AcroNetSecureDatabase — SQLCipher Encrypted Persistence (Phase 3)
 *
 * All messages are stored as AES-256-GCM Base64 ciphertext inside
 * a SQLCipher-encrypted database. The database key is managed by
 * AcroNetDatabaseKeyManager with 24-hour rolling rotation.
 */
object AcroNetSecureDatabase {

    private const val TAG = "ACRO_VOID_CRYPTO"
    private const val DB_NAME = "acronet_vault.db"
    private const val TABLE = "messages"

    private var db: SQLiteDatabase? = null

    fun init(context: Context) {
        if (db != null) return

        SQLiteDatabase.loadLibs(context)
        AcroNetDatabaseKeyManager.init(context)

        val keyHex = AcroNetDatabaseKeyManager.getCurrentKeyHex()
        val dbFile = context.getDatabasePath(DB_NAME)
        dbFile.parentFile?.mkdirs()

        db = SQLiteDatabase.openOrCreateDatabase(
            dbFile.absolutePath, keyHex, null, null
        )
        createSchema()
        Log.d(TAG, "[DB] SQLCipher vault opened. Genesis: ${AcroNetGenesis.getSignatureHex().take(16)}...")
    }

    private fun createSchema() {
        db?.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE (
                id TEXT PRIMARY KEY,
                sender TEXT NOT NULL,
                payload TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                room TEXT NOT NULL,
                type TEXT NOT NULL DEFAULT 'TEXT',
                is_mine INTEGER NOT NULL DEFAULT 0,
                ephemeral INTEGER NOT NULL DEFAULT 0,
                expires_at INTEGER NOT NULL DEFAULT 0
            )
        """)
    }

    fun insertMessage(msg: SecureMessage, room: String) {
        val values = ContentValues().apply {
            put("id", msg.id)
            put("sender", msg.senderId)
            put("payload", msg.encryptedPayload)
            put("timestamp", msg.timestamp)
            put("room", room)
            put("type", msg.messageType.name)
            put("is_mine", if (msg.isMine) 1 else 0)
            put("ephemeral", if (msg.isEphemeral) 1 else 0)
            put("expires_at", msg.expiresAt)
        }
        db?.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Log.d(TAG, "[DB] Stored encrypted msg ${msg.id} (${msg.encryptedPayload.length} chars)")
    }

    fun getMessages(room: String): List<SecureMessage> {
        val messages = mutableListOf<SecureMessage>()
        val cursor = db?.rawQuery(
            "SELECT * FROM $TABLE WHERE room = ? ORDER BY timestamp ASC",
            arrayOf(room)
        )
        try {
            while (cursor?.moveToNext() == true) {
                messages.add(SecureMessage(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    senderId = cursor.getString(cursor.getColumnIndexOrThrow("sender")),
                    encryptedPayload = cursor.getString(cursor.getColumnIndexOrThrow("payload")),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                    isMine = cursor.getInt(cursor.getColumnIndexOrThrow("is_mine")) == 1,
                    messageType = try {
                        SecureMessage.MessageType.valueOf(
                            cursor.getString(cursor.getColumnIndexOrThrow("type"))
                        )
                    } catch (_: Exception) { SecureMessage.MessageType.TEXT },
                    isEphemeral = cursor.getInt(cursor.getColumnIndexOrThrow("ephemeral")) == 1,
                    expiresAt = cursor.getLong(cursor.getColumnIndexOrThrow("expires_at"))
                ))
            }
        } finally {
            cursor?.close()
        }
        Log.d(TAG, "[DB] Loaded ${messages.size} encrypted messages for room: $room")
        return messages
    }

    fun purgeExpired() {
        val now = System.currentTimeMillis()
        val deleted = db?.delete(
            TABLE, "ephemeral = 1 AND expires_at > 0 AND expires_at < ?",
            arrayOf(now.toString())
        )
        if ((deleted ?: 0) > 0) {
            Log.d(TAG, "[DB] Purged $deleted expired ephemeral messages")
        }
    }

    fun deleteMessage(id: String) {
        db?.delete(TABLE, "id = ?", arrayOf(id))
    }

    fun checkAndRotateKey() {
        val result = AcroNetDatabaseKeyManager.checkAndRotate()
        if (result.rotated && result.newKeyHex != null) {
            db?.execSQL("PRAGMA rekey = '${result.newKeyHex}'")
            Log.d(TAG, "[DB] 24-hour key rotation complete.")
        }
    }

    fun close() {
        db?.close()
        db = null
    }
}
