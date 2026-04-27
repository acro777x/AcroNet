package com.ghost.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SecurePrefs — Military-grade local key storage.
 * Backed by Android Keystore AES-256-GCM.
 */
object SecurePrefs {

    private const val PREFS_NAME = "ghost_secure_prefs"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context, PREFS_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ── Session ─────────────────────────────────────────────────────
    fun saveToken(token: String) = prefs?.edit()?.putString("session_token", token)?.apply()
    fun getToken(): String? = prefs?.getString("session_token", null)
    fun clearToken() = prefs?.edit()?.remove("session_token")?.apply()
    fun saveAlias(alias: String) = prefs?.edit()?.putString("last_alias", alias)?.apply()
    fun getAlias(): String? = prefs?.getString("last_alias", null)
    fun saveRoomKey(key: String) = prefs?.edit()?.putString("room_key", key)?.apply()
    fun getRoomKey(): String = prefs?.getString("room_key", "") ?: ""
    fun saveRoomHash(hash: String) = prefs?.edit()?.putString("room_hash", hash)?.apply()
    fun getRoomHash(): String = prefs?.getString("room_hash", "") ?: ""

    // ── Phase 2: Settings ───────────────────────────────────────────
    fun setEsp32Ip(ip: String) = prefs?.edit()?.putString("esp32_ip", ip)?.apply()
    fun getEsp32Ip(): String = prefs?.getString("esp32_ip", "192.168.4.1") ?: "192.168.4.1"

    fun setFontSize(size: Int) = prefs?.edit()?.putInt("font_size", size)?.apply()
    fun getFontSize(): Int = prefs?.getInt("font_size", 15) ?: 15

    fun setTheme(theme: String) = prefs?.edit()?.putString("app_theme", theme)?.apply()
    fun getTheme(): String = prefs?.getString("app_theme", "midnight") ?: "midnight"

    fun setBubbleStyle(style: String) = prefs?.edit()?.putString("bubble_style", style)?.apply()
    fun getBubbleStyle(): String = prefs?.getString("bubble_style", "rounded") ?: "rounded"

    fun setNotificationsEnabled(on: Boolean) = prefs?.edit()?.putBoolean("notif_enabled", on)?.apply()
    fun getNotificationsEnabled(): Boolean = prefs?.getBoolean("notif_enabled", true) ?: true

    fun setVibrationEnabled(on: Boolean) = prefs?.edit()?.putBoolean("vibration_enabled", on)?.apply()
    fun getVibrationEnabled(): Boolean = prefs?.getBoolean("vibration_enabled", true) ?: true

    // ── Phase 3: Security ───────────────────────────────────────────
    fun setBiometricEnabled(on: Boolean) = prefs?.edit()?.putBoolean("biometric_lock", on)?.apply()
    fun getBiometricEnabled(): Boolean = prefs?.getBoolean("biometric_lock", false) ?: false

    fun setStealthMode(on: Boolean) = prefs?.edit()?.putBoolean("stealth_mode", on)?.apply()
    fun getStealthMode(): Boolean = prefs?.getBoolean("stealth_mode", false) ?: false

    fun setStealthPin(pin: String) = prefs?.edit()?.putString("stealth_pin", pin)?.apply()
    fun getStealthPin(): String = prefs?.getString("stealth_pin", "1337") ?: "1337"

    fun setAutoLockSeconds(s: Int) = prefs?.edit()?.putInt("auto_lock_sec", s)?.apply()
    fun getAutoLockSeconds(): Int = prefs?.getInt("auto_lock_sec", 0) ?: 0

    // ── Phase 5: Disappearing Messages ──────────────────────────────
    fun setDisappearTimer(seconds: Long) = prefs?.edit()?.putLong("disappear_timer", seconds)?.apply()
    fun getDisappearTimer(): Long = prefs?.getLong("disappear_timer", 0L) ?: 0L

    // ── Starred Messages ────────────────────────────────────────────
    fun addStarredMsg(id: String) {
        val set = getStarredIds().toMutableSet()
        set.add(id)
        prefs?.edit()?.putStringSet("starred_ids", set)?.apply()
    }
    fun removeStarredMsg(id: String) {
        val set = getStarredIds().toMutableSet()
        set.remove(id)
        prefs?.edit()?.putStringSet("starred_ids", set)?.apply()
    }
    fun getStarredIds(): Set<String> = prefs?.getStringSet("starred_ids", emptySet()) ?: emptySet()
    fun isStarred(id: String): Boolean = getStarredIds().contains(id)

    // ── Deleted Messages (persist across app restarts) ──────────────
    fun addDeletedId(id: String) {
        val set = getDeletedIds().toMutableSet()
        set.add(id)
        prefs?.edit()?.putStringSet("deleted_ids", set)?.apply()
    }
    fun getDeletedIds(): Set<String> = prefs?.getStringSet("deleted_ids", emptySet()) ?: emptySet()
    fun clearDeletedIds() = prefs?.edit()?.remove("deleted_ids")?.apply()

    // ── Onboarding ──────────────────────────────────────────────────
    fun setOnboardingDone(done: Boolean) = prefs?.edit()?.putBoolean("onboarding_done", done)?.apply()
    fun isOnboardingDone(): Boolean = prefs?.getBoolean("onboarding_done", false) ?: false

    // ── Nuclear Wipe ────────────────────────────────────────────────
    fun wipeAll() = prefs?.edit()?.clear()?.apply()
}
