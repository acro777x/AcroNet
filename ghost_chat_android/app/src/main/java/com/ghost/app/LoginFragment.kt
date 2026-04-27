package com.ghost.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ghost.app.databinding.FragmentLoginBinding
import kotlinx.coroutines.launch

/**
 * LoginFragment — MVVM View Layer
 *
 * ZERO network calls here. Only observes LoginViewModel StateFlow.
 */
class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LoginViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Handle Enter key on password field
        binding.etKey.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                binding.btnLogin.performClick()
                true
            } else false
        }

        parentFragmentManager.setFragmentResultListener("qrScanResult", viewLifecycleOwner) { _, bundle ->
            val roomHash = bundle.getString("scannedRoom")
            val key = bundle.getString("scannedKey")
            if (roomHash != null && key != null) {
                binding.etKey.setText(key)
                binding.btnLogin.performClick()
            }
        }

        binding.btnScanQr.setOnClickListener {
            val qrFrag = QRScannerFragment().apply {
                arguments = Bundle().apply {
                    putString("mode", "scan")
                }
            }
            parentFragmentManager.beginTransaction()
                .replace(R.id.root_container, qrFrag)
                .addToBackStack(null)
                .commit()
        }

        // Login button → delegate to ViewModel
        binding.btnLogin.setOnClickListener {
            val alias = binding.etAlias.text.toString().trim()
            val key = binding.etKey.text.toString().trim()
            viewModel.login(alias, key)
        }

        // ── Observe ViewModel State (Lifecycle-Aware) ────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loginState.collect { state ->
                    when (state) {
                        is LoginState.Idle -> {
                            binding.btnLogin.isEnabled = true
                            binding.btnLogin.text = "INITIALIZE UPLINK"
                            binding.tvError.visibility = View.GONE
                            binding.tvStatus.text = "Connect to Ghost_Net WiFi first"
                        }
                        is LoginState.Loading -> {
                            binding.btnLogin.isEnabled = false
                            binding.btnLogin.text = "CONNECTING..."
                            binding.tvError.visibility = View.GONE
                            binding.tvStatus.text = "Establishing AES-256 encrypted uplink..."
                        }
                        is LoginState.Success -> {
                            (activity as? MainActivity)?.showChat(state.result)
                            viewModel.resetState()
                        }
                        is LoginState.Error -> {
                            binding.tvError.text = state.message
                            binding.tvError.visibility = View.VISIBLE
                            binding.btnLogin.isEnabled = true
                            binding.btnLogin.text = "INITIALIZE UPLINK"
                            binding.tvStatus.text = "Connect to Ghost_Net WiFi first"
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
