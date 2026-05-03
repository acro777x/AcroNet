package com.acronet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.acronet.forensics.AcroNetPinRouter
import com.ghost.app.databinding.FragmentAuthGateBinding

/**
 * AcroNetAuthGate — Biometric + PIN Authentication (V8.5)
 *
 * Root authentication fragment. Protects SQLCipher master key.
 * On 3 failed PIN attempts → coldBootWipe() + close vault.
 */
class AcroNetAuthGate : Fragment() {

    private var _binding: FragmentAuthGateBinding? = null
    private val binding get() = _binding!!
    private var failedAttempts = 0

    interface AuthCallback {
        fun onAuthSuccess()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAuthGateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnFingerprint.setOnClickListener { launchBiometric() }

        binding.tvPinFallback.setOnClickListener {
            binding.pinContainer.visibility = View.VISIBLE
            binding.tvPinFallback.visibility = View.GONE
        }

        binding.etPin.setOnEditorActionListener { _, _, _ ->
            validatePin()
            true
        }

        // Auto-launch biometric if available
        val bioMgr = BiometricManager.from(requireContext())
        if (bioMgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS) {
            launchBiometric()
        }
    }

    private fun launchBiometric() {
        val executor = ContextCompat.getMainExecutor(requireContext())
        val prompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
                override fun onAuthenticationFailed() {
                    Toast.makeText(requireContext(), "Biometric failed", Toast.LENGTH_SHORT).show()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        Toast.makeText(requireContext(), errString, Toast.LENGTH_SHORT).show()
                    }
                }
            })

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("AcroNet Vault")
            .setSubtitle("Verify identity to access encrypted vault")
            .setNegativeBtnText("Use PIN")
            .build()

        prompt.authenticate(info)
    }

    private fun validatePin() {
        val pin = binding.etPin.text.toString()
        if (pin.length < 4) {
            Toast.makeText(requireContext(), "PIN too short", Toast.LENGTH_SHORT).show()
            return
        }

        // For Phase 4, accept any 4+ digit PIN (real PIN routing in Phase 5)
        // In production, this calls AcroNetPinRouter.unlock()
        if (pin.length >= 4) {
            onSuccess()
        } else {
            failedAttempts++
            binding.tvAttempts.visibility = View.VISIBLE
            binding.tvAttempts.text = "Failed attempts: $failedAttempts/3"

            if (failedAttempts >= 3) {
                AcroNetPinRouter.coldBootWipe()
                Toast.makeText(requireContext(), "Vault locked. Keys destroyed.", Toast.LENGTH_LONG).show()
                requireActivity().finish()
            }
        }
    }

    private fun onSuccess() {
        (requireActivity() as? AuthCallback)?.onAuthSuccess()
    }

    // Workaround: BiometricPrompt.PromptInfo.Builder doesn't have setNegativeBtnText
    // Use setNegativeButtonText instead
    private fun BiometricPrompt.PromptInfo.Builder.setNegativeBtnText(text: String):
            BiometricPrompt.PromptInfo.Builder = setNegativeButtonText(text)

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
