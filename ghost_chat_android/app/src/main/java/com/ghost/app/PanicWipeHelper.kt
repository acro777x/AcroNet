package com.ghost.app

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/**
 * PanicWipeHelper — Nuclear data destruction.
 * Phase 3B: Wipes all local data + sends CMD:WIPE to ESP32.
 */
object PanicWipeHelper {

    /**
     * Show confirmation dialog. User must type "WIPE" to confirm.
     */
    fun showConfirmation(context: Context, onConfirm: () -> Unit) {
        val input = EditText(context).apply {
            hint = "Type WIPE to confirm"
            setTextColor(0xFFef4444.toInt())
            setHintTextColor(0xFF475569.toInt())
            textSize = 16f
            setPadding(48, 32, 48, 32)
            setBackgroundColor(0xFF0f172a.toInt())
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 0)
            addView(input)
        }

        AlertDialog.Builder(context, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle("🚨 PANIC WIPE")
            .setMessage("This will PERMANENTLY destroy:\n\n• All messages\n• Encryption keys\n• Session tokens\n• All preferences\n\nThis action CANNOT be undone.\n\nType \"WIPE\" to confirm:")
            .setView(container)
            .setPositiveButton("DESTROY") { _, _ ->
                if (input.text.toString().trim().uppercase() == "WIPE") {
                    executeWipe(context)
                    onConfirm()
                } else {
                    Toast.makeText(context, "Wipe cancelled — incorrect confirmation", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Execute full data destruction.
     */
    private fun executeWipe(context: Context) {
        // 1. Wipe EncryptedSharedPreferences
        SecurePrefs.wipeAll()

        // 2. Send ESP32 Duress Wipe (hardcoded pass: "pink")
        try {
            Thread {
                kotlinx.coroutines.runBlocking {
                    try {
                        GhostApi.login("wipe", "pink", "wipe")
                    } catch (_: Exception) {}
                }
            }.start()
        } catch (_: Exception) {}

        // 3. Clear app cache
        try {
            context.cacheDir.deleteRecursively()
        } catch (_: Exception) {}

        // 4. Clear internal files
        try {
            context.filesDir.deleteRecursively()
        } catch (_: Exception) {}
    }
}
