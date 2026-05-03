package com.acronet.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.acronet.ui.utils.TripleTapListener
import com.ghost.app.R
import com.ghost.app.databinding.ActivityAcronetDecoyBinding
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleDataSet
import com.github.mikephil.charting.data.CandleEntry
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.NumberFormat
import java.util.Locale

/**
 * AcroNetDecoyActivity — MarketPulse V8.5 Apex Synthesis
 *
 * LIVE Binance WebSocket feed for BTC candlesticks.
 * Falls back to generated data if offline with "Connecting..." state.
 */
class AcroNetDecoyActivity : AppCompatActivity(), MarketPulseLiveSocket.Callback {

    private lateinit var binding: ActivityAcronetDecoyBinding
    private val liveSocket = MarketPulseLiveSocket()
    private val handler = Handler(Looper.getMainLooper())
    private val liveCandles = mutableListOf<CandleEntry>()
    private var isLive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        binding = ActivityAcronetDecoyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        setupStealthTrigger()
        setupChart()
        setupTopBar()

        // Show connecting state + seed with generated data
        binding.tvBalance.text = "Connecting to Exchange..."
        binding.tvChangePercent.text = "Syncing..."
        binding.tvChangePercent.setTextColor(Color.parseColor("#888899"))

        // Seed chart with generated data first
        val seed = MarketPulseDataGenerator.generateCandleData(50)
        liveCandles.addAll(seed)
        updateChart()
        bindSnapshot()

        // Connect to live Binance stream
        liveSocket.connect(this)
    }

    // ── LIVE SOCKET CALLBACKS ────────────────────────────────────────

    override fun onCandleUpdate(entry: CandleEntry, price: Float, change24h: Float) {
        handler.post {
            isLive = true
            val reindexed = CandleEntry(liveCandles.size.toFloat(), entry.high, entry.low, entry.open, entry.close)
            liveCandles.add(reindexed)
            if (liveCandles.size > 200) liveCandles.removeAt(0) // Rolling window
            updateChart()

            val fmt = NumberFormat.getCurrencyInstance(Locale.US)
            binding.tvBalance.text = fmt.format(price * 1.47) // Simulated holdings
            binding.tvBtcPrice.text = fmt.format(price.toDouble())

            val changeText = String.format("%+.2f%% (24h)", change24h)
            binding.tvChangePercent.text = changeText
            binding.tvBtcChange.text = String.format("%+.1f%%", change24h)

            val color = if (change24h >= 0) Color.parseColor("#22C55E") else Color.parseColor("#EF4444")
            binding.tvChangePercent.setTextColor(color)
            binding.tvBtcChange.setTextColor(color)
        }
    }

    override fun onConnectionStateChanged(connected: Boolean) {
        handler.post {
            if (!connected && !isLive) {
                binding.tvBalance.text = "Connecting to Exchange..."
                binding.tvChangePercent.text = "Offline — using cached data"
                binding.tvChangePercent.setTextColor(Color.parseColor("#888899"))
                bindSnapshot()
            }
        }
    }

    private fun bindSnapshot() {
        val snapshot = MarketPulseDataGenerator.getLastSnapshot() ?: return
        val fmt = NumberFormat.getCurrencyInstance(Locale.US)
        if (!isLive) {
            binding.tvBalance.text = fmt.format(snapshot.portfolioValue)
            binding.tvBtcPrice.text = fmt.format(snapshot.btcPrice.toDouble())
            val change = String.format("%+.2f%%", snapshot.changePercent)
            binding.tvChangePercent.text = "$change (cached)"
            binding.tvBtcChange.text = change
            val color = if (snapshot.changePercent >= 0) Color.parseColor("#22C55E") else Color.parseColor("#EF4444")
            binding.tvChangePercent.setTextColor(color)
            binding.tvBtcChange.setTextColor(color)
        }
    }

    private fun setupStealthTrigger() {
        binding.tvAppTitle.setOnTouchListener(TripleTapListener { launchAcroNet() })
    }

    private fun setupChart() {
        binding.chartMarket.apply {
            description.isEnabled = false
            setTouchEnabled(false)
            isDragEnabled = false
            setScaleEnabled(false)
            setDrawGridBackground(false)
            legend.isEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            xAxis.isEnabled = false
            axisLeft.isEnabled = false
            axisRight.isEnabled = false
            setViewPortOffsets(0f, 0f, 0f, 0f)
        }
    }

    private fun updateChart() {
        val dataSet = CandleDataSet(liveCandles.toList(), "BTC").apply {
            color = Color.parseColor("#3B82F6")
            shadowColor = Color.parseColor("#333344")
            shadowWidth = 0.8f
            decreasingColor = Color.parseColor("#EF4444")
            decreasingPaintStyle = Paint.Style.FILL
            increasingColor = Color.parseColor("#22C55E")
            increasingPaintStyle = Paint.Style.FILL
            neutralColor = Color.parseColor("#3B82F6")
            setDrawValues(false)
        }
        binding.chartMarket.data = CandleData(dataSet)
        binding.chartMarket.notifyDataSetChanged()
        binding.chartMarket.invalidate()
    }

    private fun setupTopBar() {
        binding.btnDecoyAttach.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivity(Intent.createChooser(intent, "Select Document"))
            } catch (_: Exception) {
                Toast.makeText(this, "No file manager", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnDecoySettings.setOnClickListener {
            val dialog = BottomSheetDialog(this, R.style.Theme_GhostChat)
            dialog.setContentView(LayoutInflater.from(this).inflate(R.layout.bottom_sheet_settings, null))
            dialog.window?.navigationBarColor = Color.parseColor("#0A0A0F")
            dialog.show()
        }
    }

    private fun launchAcroNet() {
        val root = binding.root
        val parent = root.parent as ViewGroup
        com.acronet.ui.anim.AcroNetShatterTransition.shatter(root, parent) {
            try {
                startActivity(Intent(this, Class.forName("com.acronet.ui.AcroNetMainActivity")))
                overridePendingTransition(
                    resources.getIdentifier("zoom_in_reveal", "anim", packageName),
                    resources.getIdentifier("fade_out_decoy", "anim", packageName)
                )
                finish()
            } catch (_: ClassNotFoundException) { }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        liveSocket.disconnect()
    }
}
