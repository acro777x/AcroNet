package com.ghost.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NetworkBinder — Forces all OkHttp traffic through ESP32 WiFi.
 *
 * Uses BOTH:
 * 1. Direct binding (immediate — scans existing networks)
 * 2. Callback binding (deferred — catches future connections)
 */
object NetworkBinder {

    private var connectivityManager: ConnectivityManager? = null
    private var boundNetwork: Network? = null

    private val _networkState = MutableStateFlow(NetworkState.SEARCHING)
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            bindToNetwork(network)
        }

        override fun onLost(network: Network) {
            if (boundNetwork == network) {
                boundNetwork = null
                _networkState.value = NetworkState.LOST
                connectivityManager?.bindProcessToNetwork(null)
                GhostApi.rebindToNetwork(null)
            }
        }
    }

    fun bind(context: Context) {
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

        // 1. Immediate: try to bind to any existing WiFi network RIGHT NOW
        tryBindWifi()

        // 2. Deferred: register callback for future WiFi connections
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (_: Exception) {}
    }

    /**
     * Directly scan all active networks and bind to WiFi.
     * This is the critical fix — doesn't rely on async callbacks.
     */
    fun tryBindWifi(): Boolean {
        val cm = connectivityManager ?: return false
        try {
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    bindToNetwork(network)
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private fun bindToNetwork(network: Network) {
        boundNetwork = network
        _networkState.value = NetworkState.BOUND
        connectivityManager?.bindProcessToNetwork(network)
        GhostApi.rebindToNetwork(network)
    }

    fun unbind() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            connectivityManager?.bindProcessToNetwork(null)
        } catch (_: Exception) {}
        boundNetwork = null
        _networkState.value = NetworkState.SEARCHING
    }

    fun isBound(): Boolean = boundNetwork != null
}

enum class NetworkState {
    SEARCHING, BOUND, LOST, UNAVAILABLE
}
