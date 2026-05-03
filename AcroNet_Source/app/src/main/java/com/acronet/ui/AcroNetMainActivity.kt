package com.acronet.ui

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.acronet.core.AcroNetGenesis
import com.acronet.crypto.AcroNetSecureDatabase
import com.acronet.forensics.AcroNetPinRouter

/**
 * AcroNetMainActivity — V8.5 Apex Synthesis Vault
 *
 * Boot: Genesis check → SQLCipher init → Auth Gate → Dashboard
 * FLAG_SECURE global. Cold boot wipe on destroy/trim.
 */
class AcroNetMainActivity : AppCompatActivity(), AcroNetAuthGate.AuthCallback {

    companion object { private const val TAG = "ACRO_VOID_CRYPTO" }

    private var chatContainerId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // GLOBAL FLAG_SECURE — block screenshots across ALL vault screens
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        // GENESIS VALIDATION
        if (!AcroNetGenesis.validateIntegrity()) {
            Log.e(TAG, "[GENESIS] INTEGRITY FAILED. Aborting.")
            finish(); return
        }
        Log.d(TAG, "[GENESIS] Valid. V${AcroNetGenesis.VERSION} (${AcroNetGenesis.VERSION_CODENAME})")

        // SQLCIPHER INIT
        try {
            AcroNetSecureDatabase.init(this)
            AcroNetSecureDatabase.checkAndRotateKey()
            AcroNetSecureDatabase.purgeExpired()
        } catch (e: Exception) {
            Log.e(TAG, "[BOOT] SQLCipher failed: ${e.message}")
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0F"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        val chatContainer = FrameLayout(this).apply {
            id = View.generateViewId()
            chatContainerId = id
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.INVISIBLE
            tag = "chat_container"
        }
        root.addView(chatContainer)

        // QUANTUM LOGO SPLASH
        val logoView = ImageView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0F"))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(120, 120, 120, 120)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER
            )
        }
        try {
            val resId = resources.getIdentifier("acronet_quantum_logo", "drawable", packageName)
            if (resId != 0) logoView.setImageResource(resId)
        } catch (_: Exception) {}

        root.addView(logoView)
        setContentView(root)

        logoView.postDelayed({
            ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 600
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    val v = anim.animatedValue as Float
                    logoView.alpha = v
                    logoView.scaleX = 1f + (1f - v) * 0.3f
                    logoView.scaleY = 1f + (1f - v) * 0.3f
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        root.removeView(logoView)
                        chatContainer.visibility = View.VISIBLE
                        // Route to Auth Gate FIRST, then Dashboard on success
                        supportFragmentManager.beginTransaction()
                            .replace(chatContainer.id, AcroNetAuthGate())
                            .commit()
                    }
                })
                start()
            }
        }, 1500)
    }

    // AUTH GATE CALLBACK — on biometric/PIN success, load Dashboard
    override fun onAuthSuccess() {
        Log.d(TAG, "[AUTH] Identity verified. Loading encrypted dashboard.")
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(chatContainerId, AcroNetDashboardFragment())
            .commit()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            AcroNetPinRouter.coldBootWipe()
            Log.d(TAG, "[COLD BOOT] Memory pressure. Keys zeroed.")
        }
    }

    override fun onDestroy() {
        AcroNetPinRouter.coldBootWipe()
        AcroNetSecureDatabase.close()
        super.onDestroy()
        Log.d(TAG, "[SHUTDOWN] Cold boot wipe + DB closed.")
    }
}
