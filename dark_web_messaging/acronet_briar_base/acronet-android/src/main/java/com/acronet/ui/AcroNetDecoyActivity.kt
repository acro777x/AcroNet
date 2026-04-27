package com.acronet.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/**
 * AcroNetDecoyActivity — "MarketPulse" Stock/Crypto Ticker
 *
 * This is the OS-level forensic decoy. A fully functional, boring
 * cryptocurrency price tracker. Network sniffers see legitimate
 * HTTPS calls to api.coingecko.com. Forensic examiners see a
 * stock app with real data and real interaction patterns.
 *
 * THE STEALTH TRIGGER:
 *   Three-finger swipe DOWN on the chart area → triggers biometric
 *   prompt → launches the true AcroNet dark-web messenger.
 *
 * Edge-to-Edge: Draws behind status bar and navigation pill.
 */
class AcroNetDecoyActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private var priceJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Gesture detection for stealth unlock
    private var fingerCount = 0
    private var threeFingerSwipeDetected = false
    private var swipeStartY = 0f

    // Chart data
    private val priceHistory = mutableListOf<Float>()
    private var currentBtcPrice = 0.0
    private var currentEthPrice = 0.0
    private var btcChange24h = 0.0
    private var ethChange24h = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Edge-to-Edge ─────────────────────────────────────────
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val root = buildDecoyUI()
        setContentView(root)

        // Apply window insets for edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        // Start fetching real market data
        startPriceFeed()
    }

    // ── BUILD THE DECOY UI (Programmatic) ────────────────────────────

    private fun buildDecoyUI(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0E17"))
            gravity = Gravity.TOP
        }

        // ── App Bar ──
        val appBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 24, 48, 24)
            gravity = Gravity.CENTER_VERTICAL
        }

        val logo = TextView(this).apply {
            text = "📈 MarketPulse"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        val liveTag = TextView(this).apply {
            text = "● LIVE"
            setTextColor(Color.parseColor("#22C55E"))
            textSize = 12f
        }

        appBar.addView(logo)
        appBar.addView(spacer)
        appBar.addView(liveTag)
        root.addView(appBar)

        // ── BTC Price Card ──
        root.addView(buildPriceCard("Bitcoin", "BTC", "btc_price", "btc_change"))

        // ── ETH Price Card ──
        root.addView(buildPriceCard("Ethereum", "ETH", "eth_price", "eth_change"))

        // ── Chart Area (STEALTH TRIGGER ZONE) ──
        val chartContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { setMargins(32, 16, 32, 16) }
            setBackgroundColor(Color.parseColor("#111827"))
            id = View.generateViewId()
        }

        val chartView = ChartView(this@AcroNetDecoyActivity)
        chartView.id = View.generateViewId()
        chartContainer.addView(chartView)

        val chartLabel = TextView(this).apply {
            text = "BTC/USD — 24H"
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 11f
            setPadding(24, 12, 0, 0)
        }
        chartContainer.addView(chartLabel)

        // ── STEALTH GESTURE on chart area ──
        chartContainer.setOnTouchListener { _, event -> handleStealthGesture(event) }

        root.addView(chartContainer)

        // ── Bottom row: Volume ──
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 8, 48, 24)
        }
        val volLabel = TextView(this).apply {
            text = "24h Vol: Loading..."
            setTextColor(Color.parseColor("#4B5563"))
            textSize = 12f
            id = View.generateViewId()
            tag = "vol_label"
        }
        bottomBar.addView(volLabel)
        root.addView(bottomBar)

        root.tag = "decoy_root"
        return root
    }

    private fun buildPriceCard(name: String, symbol: String, priceTag: String, changeTag: String): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 28, 48, 28)
            gravity = Gravity.CENTER_VERTICAL
        }

        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        nameCol.addView(TextView(this).apply {
            text = name
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        nameCol.addView(TextView(this).apply {
            text = symbol
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 13f
        })

        val priceCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        priceCol.addView(TextView(this).apply {
            text = "$0.00"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            tag = priceTag
        })
        priceCol.addView(TextView(this).apply {
            text = "+0.0%"
            setTextColor(Color.parseColor("#22C55E"))
            textSize = 13f
            tag = changeTag
        })

        card.addView(nameCol)
        card.addView(priceCol)
        return card
    }

    // ── STEALTH GESTURE HANDLER ──────────────────────────────────────

    private fun handleStealthGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                fingerCount = event.pointerCount
                if (fingerCount == 3) {
                    swipeStartY = event.getY(0)
                    threeFingerSwipeDetected = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (fingerCount >= 3 && event.pointerCount >= 3) {
                    val deltaY = event.getY(0) - swipeStartY
                    if (deltaY > 200) { // 200px downward swipe
                        threeFingerSwipeDetected = true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (threeFingerSwipeDetected && event.pointerCount <= 1) {
                    // STEALTH UNLOCK TRIGGERED
                    launchAcroNet()
                    threeFingerSwipeDetected = false
                }
                if (event.pointerCount <= 1) fingerCount = 0
            }
        }
        return true
    }

    private fun launchAcroNet() {
        // Haptic feedback to confirm trigger
        val root = window.decorView
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        // Launch the true AcroNet interface
        // In production: BiometricPrompt → then launch
        try {
            val intent = Intent(this, Class.forName("com.acronet.ui.AcroNetMainActivity"))
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } catch (e: ClassNotFoundException) {
            // AcroNet module not loaded — fail silently (forensic safety)
        }
    }

    // ── REAL MARKET DATA FEED ────────────────────────────────────────

    private fun startPriceFeed() {
        priceJob = scope.launch {
            while (isActive) {
                try {
                    val data = withContext(Dispatchers.IO) { fetchPrices() }
                    if (data != null) updateUI(data)
                } catch (_: Exception) { /* Network failure — stay silent */ }
                delay(15_000) // Refresh every 15s
            }
        }
    }

    private fun fetchPrices(): JSONObject? {
        val url = "https://api.coingecko.com/api/v3/simple/price" +
                "?ids=bitcoin,ethereum&vs_currencies=usd&include_24hr_change=true&include_24hr_vol=true"
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .build()
        val response = client.newCall(request).execute()
        return if (response.isSuccessful) {
            JSONObject(response.body?.string() ?: "{}")
        } else null
    }

    private fun updateUI(data: JSONObject) {
        val fmt = NumberFormat.getCurrencyInstance(Locale.US)

        val btc = data.optJSONObject("bitcoin")
        val eth = data.optJSONObject("ethereum")

        if (btc != null) {
            currentBtcPrice = btc.optDouble("usd", 0.0)
            btcChange24h = btc.optDouble("usd_24h_change", 0.0)
            priceHistory.add(currentBtcPrice.toFloat())
            if (priceHistory.size > 96) priceHistory.removeAt(0) // Keep 24h at 15min intervals
        }
        if (eth != null) {
            currentEthPrice = eth.optDouble("usd", 0.0)
            ethChange24h = eth.optDouble("usd_24h_change", 0.0)
        }

        // Update price TextViews by tag
        findViewByTag<TextView>("btc_price")?.text = fmt.format(currentBtcPrice)
        findViewByTag<TextView>("btc_change")?.apply {
            text = String.format("%+.2f%%", btcChange24h)
            setTextColor(if (btcChange24h >= 0) Color.parseColor("#22C55E") else Color.parseColor("#EF4444"))
        }
        findViewByTag<TextView>("eth_price")?.text = fmt.format(currentEthPrice)
        findViewByTag<TextView>("eth_change")?.apply {
            text = String.format("%+.2f%%", ethChange24h)
            setTextColor(if (ethChange24h >= 0) Color.parseColor("#22C55E") else Color.parseColor("#EF4444"))
        }
        findViewByTag<TextView>("vol_label")?.text =
            "24h Vol: ${fmt.format(btc?.optDouble("usd_24h_vol", 0.0) ?: 0.0)}"
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> findViewByTag(tag: String): T? {
        return window.decorView.findViewWithTag(tag) as? T
    }

    override fun onDestroy() {
        super.onDestroy()
        priceJob?.cancel()
        scope.cancel()
    }

    // ── INNER: Candlestick Chart View ────────────────────────────────

    inner class ChartView(context: android.content.Context) : View(context) {
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3B82F6")
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val gridPaint = Paint().apply {
            color = Color.parseColor("#1F2937")
            strokeWidth = 1f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            // Grid lines
            for (i in 1..3) {
                val y = h * i / 4f
                canvas.drawLine(0f, y, w, y, gridPaint)
            }

            val data = priceHistory
            if (data.size < 2) {
                // Placeholder sine wave until real data arrives
                val path = Path()
                path.moveTo(0f, h / 2f)
                for (x in 0..w.toInt() step 4) {
                    val y = h / 2f + (Math.sin(x / 40.0) * h / 6f).toFloat()
                    path.lineTo(x.toFloat(), y)
                }
                canvas.drawPath(path, linePaint)
                return
            }

            val min = data.min()
            val max = data.max()
            val range = (max - min).coerceAtLeast(1f)
            val step = w / (data.size - 1)

            // Gradient fill
            val path = Path()
            path.moveTo(0f, h)
            for (i in data.indices) {
                val x = i * step
                val y = h - ((data[i] - min) / range * h * 0.8f + h * 0.1f)
                if (i == 0) path.lineTo(x, y) else path.lineTo(x, y)
            }
            path.lineTo(w, h)
            path.close()

            fillPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                Color.parseColor("#1E3A5F"), Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(path, fillPaint)

            // Line
            val linePath = Path()
            for (i in data.indices) {
                val x = i * step
                val y = h - ((data[i] - min) / range * h * 0.8f + h * 0.1f)
                if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            }
            canvas.drawPath(linePath, linePaint)

            postInvalidateDelayed(1000) // Redraw every second
        }
    }
}
