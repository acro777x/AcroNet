package com.ghost.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * MainActivity — Ghost Chat Entry Point (Final Version)
 *
 * Phase 1: Fragment navigation
 * Phase 2: Settings screen
 * Phase 3: Biometric lock on resume, SecurePrefs init
 * Phase 5: Network binding
 */
class MainActivity : AppCompatActivity() {

    private var needsBiometric = true

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Screenshot & screen recording protection
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // Transparent system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = 0x00000000
        window.navigationBarColor = 0x00000000

        setContentView(R.layout.activity_main)

        // Notch and Keyboard (IME) safety
        val rootView = findViewById<android.view.View>(R.id.root_container)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            v.updatePadding(top = insets.top, bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // Initialize security systems
        SecurePrefs.init(this)
        NetworkBinder.bind(this)

        if (savedInstanceState == null) {
            showLogin()
        }
    }

    override fun onResume() {
        super.onResume()

        // Phase 3: Biometric lock on resume
        if (needsBiometric && SecurePrefs.getBiometricEnabled()) {
            if (BiometricHelper.canAuthenticate(this)) {
                BiometricHelper.authenticate(this,
                    onSuccess = { needsBiometric = false },
                    onFail = { finishAffinity() }
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        needsBiometric = true
    }

    // ── Navigation ──────────────────────────────────────────────────

    fun showLogin() {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.root_container, LoginFragment())
            .commit()
    }

    fun showChat(result: LoginResult) {
        SecurePrefs.saveToken(result.token)
        SecurePrefs.saveAlias(result.alias)
        SecurePrefs.saveRoomKey(result.key)
        SecurePrefs.saveRoomHash(result.roomHash)
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
            .replace(R.id.root_container, ChatFragment.newInstance(result))
            .commit()
    }

    fun showSettings() {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left,
                android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.root_container, SettingsFragment())
            .addToBackStack("settings")
            .commit()
    }

    fun showStarred(messages: List<ChatMessage>) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left,
                android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.root_container, StarredMessagesFragment.newInstance(messages))
            .addToBackStack("starred")
            .commit()
    }

    @Deprecated("Use onBackPressedDispatcher instead")
    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            val frag = supportFragmentManager.findFragmentById(R.id.root_container)
            if (frag is ChatFragment) {
                showLogin()
            } else {
                super.onBackPressed()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        NetworkBinder.unbind()
    }
}
