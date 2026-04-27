package com.ghost.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * LoginViewModel — Actively binds to WiFi before attempting login.
 */
class LoginViewModel : ViewModel() {

    private val repository = GhostRepository()

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    fun login(alias: String, key: String) {
        if (alias.isBlank() || key.isBlank()) {
            _loginState.value = LoginState.Error("Alias and Decryption Key required")
            return
        }

        _loginState.value = LoginState.Loading

        viewModelScope.launch {
            // Actively try to bind to WiFi (direct scan, not callback)
            if (!NetworkBinder.isBound()) {
                NetworkBinder.tryBindWifi()
            }

            // Give it a moment if still not bound
            var waited = 0
            while (!NetworkBinder.isBound() && waited < 3000) {
                delay(300)
                NetworkBinder.tryBindWifi() // Retry scan
                waited += 300
            }

            // Attempt login regardless — might work even without explicit binding
            val result = repository.login(alias, key)

            result.onSuccess { loginResult ->
                _loginState.value = LoginState.Success(loginResult)
            }

            result.onFailure { error ->
                val msg = if (!NetworkBinder.isBound()) {
                    "⚠ Cannot reach Ghost Node. Make sure you're connected to Ghost_Net WiFi."
                } else {
                    error.message ?: "Unknown error"
                }
                _loginState.value = LoginState.Error(msg)
            }
        }
    }

    fun resetState() {
        _loginState.value = LoginState.Idle
    }
}

sealed class LoginState {
    data object Idle : LoginState()
    data object Loading : LoginState()
    data class Success(val result: LoginResult) : LoginState()
    data class Error(val message: String) : LoginState()
}
