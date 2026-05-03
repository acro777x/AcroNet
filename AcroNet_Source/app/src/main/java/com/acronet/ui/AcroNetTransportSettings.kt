package com.acronet.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.acronet.network.AcroNetNostrRelay
import com.ghost.app.R

/**
 * AcroNetTransportSettings — Network Mode Selector (V8.5)
 *
 * Allows switching active transport on-the-fly:
 *   1. Nostr Cloud Relay (wss://)
 *   2. WebRTC Local Mesh (LAN/WiFi-Direct)
 *   3. Bluetooth LE Sync
 *   4. Serial LoRa Bridge (USB OTG ESP32)
 */
object AcroNetTransportSettings {

    private const val TAG = "ACRO_VOID_TRANSPORT"

    enum class NetworkMode {
        NOSTR_RELAY, WEBRTC_MESH, BLUETOOTH_LE, LORA_SERIAL
    }

    var activeMode: NetworkMode = NetworkMode.NOSTR_RELAY
        private set

    fun show(context: Context) {
        val modes = arrayOf(
            "☁️  Nostr Cloud Relay (wss://)",
            "📡  WebRTC Local Mesh (LAN)",
            "📶  Bluetooth LE Sync",
            "🔌  Serial LoRa Bridge (USB OTG)"
        )
        val descriptions = arrayOf(
            "Global internet transport via disposable Nostr relays. Requires internet.",
            "Offline LAN/WiFi-Direct peer discovery. No internet required.",
            "Close-proximity sync via Bluetooth Low Energy. Range: ~30m.",
            "Off-grid km-range via ESP32/LoRa USB-C module. Requires hardware."
        )
        val modeValues = NetworkMode.values()
        val currentIndex = modeValues.indexOf(activeMode)

        AlertDialog.Builder(context, R.style.Theme_GhostChat)
            .setTitle("Transport Matrix")
            .setSingleChoiceItems(modes, currentIndex) { dialog, which ->
                val selected = modeValues[which]
                activateMode(context, selected)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun activateMode(context: Context, mode: NetworkMode) {
        activeMode = mode
        when (mode) {
            NetworkMode.NOSTR_RELAY -> {
                AcroNetNostrRelay.connect()
                Toast.makeText(context, "Nostr relay active: ${AcroNetNostrRelay.getActiveRelay()}", Toast.LENGTH_SHORT).show()
            }
            NetworkMode.WEBRTC_MESH -> {
                AcroNetNostrRelay.disconnect()
                Toast.makeText(context, "WebRTC mesh scanning LAN...", Toast.LENGTH_SHORT).show()
            }
            NetworkMode.BLUETOOTH_LE -> {
                AcroNetNostrRelay.disconnect()
                Toast.makeText(context, "Bluetooth LE sync initializing...", Toast.LENGTH_SHORT).show()
            }
            NetworkMode.LORA_SERIAL -> {
                AcroNetNostrRelay.disconnect()
                Toast.makeText(context, "LoRa Serial: Connect ESP32 via USB-C", Toast.LENGTH_SHORT).show()
            }
        }
        Log.d(TAG, "[TRANSPORT] Active mode: $mode")
    }

    fun getStatusText(): String = when (activeMode) {
        NetworkMode.NOSTR_RELAY -> "Nostr: ${AcroNetNostrRelay.getActiveRelay()}"
        NetworkMode.WEBRTC_MESH -> "WebRTC Mesh: Scanning"
        NetworkMode.BLUETOOTH_LE -> "BLE: Listening"
        NetworkMode.LORA_SERIAL -> "LoRa: Awaiting USB"
    }
}
