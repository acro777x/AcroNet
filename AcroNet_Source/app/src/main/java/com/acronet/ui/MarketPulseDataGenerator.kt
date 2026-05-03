package com.acronet.ui

import com.github.mikephil.charting.data.CandleEntry
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * MarketPulseDataGenerator — Apex Authenticity Injection (V8)
 *
 * Uses Geometric Brownian Motion (GBM) to simulate realistic BTC
 * volatility around $64,000. Generates 200+ candle data points with
 * trend bias, realistic wicks, and volume-correlated price action.
 *
 * This data must fool forensic inspection for at least 60 seconds.
 */
object MarketPulseDataGenerator {

    // Simulated portfolio holdings
    data class PortfolioSnapshot(
        val btcPrice: Float,
        val portfolioValue: Double,
        val changePercent: Double
    )

    private var lastSnapshot: PortfolioSnapshot? = null

    fun getLastSnapshot(): PortfolioSnapshot? = lastSnapshot

    fun generateCandleData(count: Int = 200, startPrice: Float = 63850f): List<CandleEntry> {
        val entries = mutableListOf<CandleEntry>()
        var currentPrice = startPrice

        // GBM parameters
        val mu = 0.0002f    // Slight upward drift (bullish bias)
        val sigma = 0.012f  // Volatility coefficient
        val dt = 1f

        for (i in 0 until count) {
            // Geometric Brownian Motion step
            val drift = (mu - 0.5f * sigma * sigma) * dt
            val diffusion = sigma * gaussianRandom().toFloat() * kotlin.math.sqrt(dt)
            val returnRate = drift + diffusion

            val openPrice = currentPrice
            val closePrice = openPrice * (1f + returnRate)

            // Realistic wicks: high always above both, low always below both
            val bodyHigh = maxOf(openPrice, closePrice)
            val bodyLow = minOf(openPrice, closePrice)
            val wickUp = abs(gaussianRandom().toFloat()) * sigma * openPrice * 0.5f
            val wickDown = abs(gaussianRandom().toFloat()) * sigma * openPrice * 0.5f
            val highPrice = bodyHigh + wickUp
            val lowPrice = bodyLow - wickDown

            entries.add(
                CandleEntry(
                    i.toFloat(),
                    highPrice,
                    lowPrice,
                    openPrice,
                    closePrice
                )
            )

            currentPrice = closePrice
        }

        // Calculate portfolio snapshot from final candle
        if (entries.isNotEmpty()) {
            val finalClose = entries.last().close
            val initialOpen = entries.first().open
            val change = ((finalClose - initialOpen) / initialOpen * 100.0).toDouble()
            lastSnapshot = PortfolioSnapshot(
                btcPrice = finalClose,
                portfolioValue = (finalClose * 1.47).toDouble(), // Simulated 1.47 BTC holding
                changePercent = change
            )
        }

        return entries
    }

    /**
     * Box-Muller transform for Gaussian random numbers.
     * Standard Java/Kotlin Random only gives uniform distribution.
     */
    private fun gaussianRandom(): Double {
        val u1 = Random.nextDouble()
        val u2 = Random.nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2.0 * Math.PI * u2)
    }
}
