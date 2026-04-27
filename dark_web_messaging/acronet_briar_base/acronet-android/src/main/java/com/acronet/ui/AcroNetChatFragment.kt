package com.acronet.ui

import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * AcroNetChatFragment — The True Messenger Interface
 *
 * Launched ONLY after stealth trigger + biometric verification.
 *
 * Design Directives:
 * - Glassmorphism: RenderEffect blur (API 31+) or #CC0A0A0F fallback
 * - Edge-to-Edge: WindowInsetsCompat behind status + nav bars
 * - IME Animation: OvershootInterpolator 300ms keyboard push
 * - ViewBinding: Aggressively nullified in onDestroyView()
 * - UDF: All state via StateFlow from ViewModel only
 */
class AcroNetChatFragment : Fragment() {

    // ViewBinding references — MUST be nullified in onDestroyView
    private var rootView: FrameLayout? = null
    private var chatContainer: LinearLayout? = null
    private var inputBar: LinearLayout? = null
    private var messageInput: EditText? = null
    private var sendButton: ImageButton? = null
    private var headerPanel: LinearLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = FrameLayout(requireContext()).apply {
            setBackgroundColor(Color.parseColor("#0A0A0F"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ── Glassmorphism Background Layer ──
        val glassPanel = FrameLayout(requireContext()).apply {
            setBackgroundColor(Color.parseColor("#CC0A0A0F")) // Fallback
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        applyGlassmorphism(glassPanel)
        root.addView(glassPanel)

        // ── Header: Room Info + Status ──
        headerPanel = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 16, 48, 16)
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }
        val roomLabel = TextView(requireContext()).apply {
            text = "🔒 AcroNet Horizon"
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        val spacer = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        val statusDot = TextView(requireContext()).apply {
            text = "● PQ-Secured"
            setTextColor(Color.parseColor("#A78BFA"))
            textSize = 11f
        }
        headerPanel?.addView(roomLabel)
        headerPanel?.addView(spacer)
        headerPanel?.addView(statusDot)
        root.addView(headerPanel)

        // ── Chat Content Area (ScrollView placeholder) ──
        chatContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 80, 32, 120)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val scrollView = ScrollView(requireContext()).apply {
            addView(chatContainer)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(scrollView)

        // ── Input Bar (Bottom) ──
        inputBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 16, 32, 16)
            setBackgroundColor(Color.parseColor("#CC111827"))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }
        messageInput = EditText(requireContext()).apply {
            hint = "Encrypted message..."
            setHintTextColor(Color.parseColor("#4B5563"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1F2937"))
            setPadding(32, 24, 32, 24)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        sendButton = ImageButton(requireContext()).apply {
            setBackgroundColor(Color.parseColor("#7C3AED"))
            setPadding(28, 28, 28, 28)
            contentDescription = "Send"
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(120, 120).apply {
                setMargins(16, 0, 0, 0)
            }
        }
        inputBar?.addView(messageInput)
        inputBar?.addView(sendButton)
        applyGlassmorphism(inputBar!!)
        root.addView(inputBar)

        rootView = root

        // ── Edge-to-Edge Insets ──
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            headerPanel?.setPadding(48, sys.top + 16, 48, 16)
            inputBar?.setPadding(32, 16, 32, maxOf(sys.bottom, ime.bottom) + 16)
            insets
        }

        // ── IME Keyboard Animation (Overshoot 300ms) ──
        ViewCompat.setWindowInsetsAnimationCallback(root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    val imeInset = insets.getInsets(WindowInsetsCompat.Type.ime())
                    inputBar?.translationY = -imeInset.bottom.toFloat()
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    // Snap back with overshoot
                    inputBar?.animate()
                        ?.translationY(0f)
                        ?.setDuration(300)
                        ?.setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                        ?.start()
                }
            })

        return root
    }

    // ── Glassmorphism Effect ─────────────────────────────────────────

    private fun applyGlassmorphism(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: Hardware-accelerated blur
            view.setRenderEffect(
                RenderEffect.createBlurEffect(30f, 30f, Shader.TileMode.CLAMP)
            )
        }
        // API < 31: Uses the #CC0A0A0F background already set
    }

    // ── CRITICAL: Nullify ViewBindings on Destroy ────────────────────

    override fun onDestroyView() {
        // Security: Clear input field to prevent decrypted text in JVM heap
        messageInput?.text?.clear()

        // Nullify all view references to prevent memory leaks
        rootView = null
        chatContainer = null
        inputBar = null
        messageInput = null
        sendButton = null
        headerPanel = null

        super.onDestroyView()
    }
}
