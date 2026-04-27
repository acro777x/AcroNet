package com.ghost.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment

/**
 * SettingsFragment — Full settings screen.
 * Phase 2: 100% local, zero ESP32 load.
 */
class SettingsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnBack = view.findViewById<ImageButton>(R.id.btn_settings_back)
        btnBack.setOnClickListener { (activity as? MainActivity)?.onBackPressed() }

        // ── Security Section ────────────────────────────────────────
        val swBiometric = view.findViewById<Switch>(R.id.sw_biometric)
        swBiometric.isChecked = SecurePrefs.getBiometricEnabled()
        swBiometric.setOnCheckedChangeListener { _, checked ->
            SecurePrefs.setBiometricEnabled(checked)
        }

        val swStealth = view.findViewById<Switch>(R.id.sw_stealth)
        swStealth.isChecked = SecurePrefs.getStealthMode()
        swStealth.setOnCheckedChangeListener { _, checked ->
            SecurePrefs.setStealthMode(checked)
            
            try {
                val pm = requireContext().packageManager
                val mainComp = android.content.ComponentName(requireContext(), "com.ghost.app.LauncherAlias")
                val stealthComp = android.content.ComponentName(requireContext(), StealthActivity::class.java)
                
                if (checked) {
                    pm.setComponentEnabledSetting(stealthComp, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                    pm.setComponentEnabledSetting(mainComp, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                } else {
                    pm.setComponentEnabledSetting(mainComp, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                    pm.setComponentEnabledSetting(stealthComp, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            Toast.makeText(requireContext(),
                if (checked) "🕵️ Stealth Mode: App will appear as Calculator on next launch"
                else "👻 Normal Mode: Ghost Chat icon restored",
                Toast.LENGTH_LONG).show()
        }

        // ── Custom Calculator PIN ───────────────────────────────────
        val etPin = view.findViewById<EditText>(R.id.et_stealth_pin)
        etPin.setText(SecurePrefs.getStealthPin())
        val btnSavePin = view.findViewById<TextView>(R.id.btn_save_pin)
        btnSavePin.setOnClickListener {
            val newPin = etPin.text.toString().trim()
            if (newPin.length < 4) {
                Toast.makeText(requireContext(), "⚠ PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            SecurePrefs.setStealthPin(newPin)
            Toast.makeText(requireContext(), "🔐 Calculator PIN updated to $newPin", Toast.LENGTH_SHORT).show()
        }

        val btnQrGenerate = view.findViewById<TextView>(R.id.btn_qr_generate)
        btnQrGenerate.setOnClickListener {
            val roomHash = SecurePrefs.getRoomHash()
            val roomKey = SecurePrefs.getRoomKey()
            if (roomHash.isEmpty() || roomKey.isEmpty()) {
                Toast.makeText(requireContext(), "Join a room first to generate QR", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val qrFrag = QRScannerFragment().apply {
                arguments = Bundle().apply {
                    putString("mode", "generate")
                    putString("shareData", "$roomHash:$roomKey")
                }
            }
            parentFragmentManager.beginTransaction()
                .replace(R.id.root_container, qrFrag)
                .addToBackStack(null)
                .commit()
        }

        val btnPanicWipe = view.findViewById<TextView>(R.id.btn_panic_wipe)
        btnPanicWipe.setOnClickListener {
            PanicWipeHelper.showConfirmation(requireContext()) {
                SecurePrefs.wipeAll()
                Toast.makeText(requireContext(), "All data destroyed.", Toast.LENGTH_SHORT).show()
                (activity as? MainActivity)?.showLogin()
            }
        }

        // ── Messages Section ────────────────────────────────────────
        val spinnerTimer = view.findViewById<Spinner>(R.id.spinner_disappear)
        val timerOptions = arrayOf("Off", "30 seconds", "5 minutes", "1 hour", "24 hours")
        val timerValues = longArrayOf(0, 30, 300, 3600, 86400)
        spinnerTimer.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_dropdown_item, timerOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val currentTimer = SecurePrefs.getDisappearTimer()
        spinnerTimer.setSelection(timerValues.indexOf(currentTimer).coerceAtLeast(0))
        spinnerTimer.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                SecurePrefs.setDisappearTimer(timerValues[pos])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val spinnerFont = view.findViewById<Spinner>(R.id.spinner_font_size)
        val fontOptions = arrayOf("Small (13sp)", "Medium (15sp)", "Large (18sp)")
        val fontValues = intArrayOf(13, 15, 18)
        spinnerFont.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_dropdown_item, fontOptions)
        spinnerFont.setSelection(fontValues.indexOf(SecurePrefs.getFontSize()).coerceAtLeast(0))
        spinnerFont.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                SecurePrefs.setFontSize(fontValues[pos])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ── Notifications ───────────────────────────────────────────
        val swNotif = view.findViewById<Switch>(R.id.sw_notifications)
        swNotif.isChecked = SecurePrefs.getNotificationsEnabled()
        swNotif.setOnCheckedChangeListener { _, c -> SecurePrefs.setNotificationsEnabled(c) }

        val swVibrate = view.findViewById<Switch>(R.id.sw_vibration)
        swVibrate.isChecked = SecurePrefs.getVibrationEnabled()
        swVibrate.setOnCheckedChangeListener { _, c -> SecurePrefs.setVibrationEnabled(c) }

        // ── Network ─────────────────────────────────────────────────
        val etIp = view.findViewById<EditText>(R.id.et_esp32_ip)
        etIp.setText(SecurePrefs.getEsp32Ip())
        val btnSaveIp = view.findViewById<TextView>(R.id.btn_save_ip)
        btnSaveIp.setOnClickListener {
            SecurePrefs.setEsp32Ip(etIp.text.toString().trim())
            Toast.makeText(requireContext(), "✅ ESP32 IP updated", Toast.LENGTH_SHORT).show()
        }

        // ── About ───────────────────────────────────────────────────
        val tvVersion = view.findViewById<TextView>(R.id.tv_version)
        tvVersion.text = "Ghost Chat v3.0  •  AES-256-GCM  •  MVVM\nTeam Scrapyard  •  2026"
    }
}
