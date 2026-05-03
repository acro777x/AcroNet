package com.acronet.ui

import android.util.Log
import com.github.mikephil.charting.data.CandleEntry
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * MarketPulseLiveSocket — Binance WebSocket Live Feed (V8.5)
 *
 * Connects to wss://stream.binance.com:9443/ws/btcusdt@kline_1m
 * for real-time BTCUSDT 1-minute candlestick data.
 *
 * Falls back to "Connecting to Exchange..." if offline.
 */
class MarketPulseLiveSocket {

    companion object {
        private const val TAG = "MarketPulse"
        private const val WS_URL = "wss://stream.binance.com:9443/ws/btcusdt@kline_1m"
    }

    interface Callback {
        fun onCandleUpdate(entry: CandleEntry, price: Float, change24h: Float)
        fun onConnectionStateChanged(connected: Boolean)
    }

    private var ws: WebSocket? = null
    private var callback: Callback? = null
    private var candleIndex = 0f
    private var openPrice: Float = 0f

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    fun connect(cb: Callback) {
        this.callback = cb
        val request = Request.Builder()
            .url(WS_URL)
            .header("User-Agent", "")
            .build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "[LIVE] Connected to Binance stream")
                callback?.onConnectionStateChanged(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val kline = json.getJSONObject("k")

                    val open = kline.getString("o").toFloat()
                    val high = kline.getString("h").toFloat()
                    val low = kline.getString("l").toFloat()
                    val close = kline.getString("c").toFloat()
                    val isClosed = kline.getBoolean("x")

                    if (openPrice == 0f) openPrice = open
                    val change = ((close - openPrice) / openPrice) * 100f

                    val entry = CandleEntry(candleIndex, high, low, open, close)
                    callback?.onCandleUpdate(entry, close, change)

                    if (isClosed) candleIndex++
                } catch (e: Exception) {
                    Log.e(TAG, "[LIVE] Parse error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "[LIVE] Connection failed: ${t.message}")
                callback?.onConnectionStateChanged(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                callback?.onConnectionStateChanged(false)
            }
        })
    }

    fun disconnect() {
        ws?.close(1000, "Shutdown")
        ws = null
        callback = null
    }
}
