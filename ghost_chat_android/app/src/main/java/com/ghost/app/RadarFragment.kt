package com.ghost.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.LinearInterpolator
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import kotlinx.coroutines.*

class RadarFragment : Fragment() {

    private var sweepAnimator: ObjectAnimator? = null
    private var radarJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Keep stable positions per node name
    private val nodePositions = mutableMapOf<String, Pair<Float, Float>>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_radar, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Defer sweep setup until the view is actually laid out
        val sweepLine = view.findViewById<View>(R.id.v_sweep)
        sweepLine.post {
            startRadarSweep(sweepLine)
        }

        startPollingNodes()
    }

    private fun startRadarSweep(sweepLine: View) {
        // Pivot at the left-center of the sweep line so it rotates from the center of the radar
        sweepLine.pivotX = 0f
        sweepLine.pivotY = sweepLine.height / 2f

        sweepAnimator = ObjectAnimator.ofFloat(sweepLine, "rotation", 0f, 360f).apply {
            duration = 4000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun startPollingNodes() {
        radarJob = scope.launch {
            while (isActive) {
                try {
                    // Call actual GhostApi endpoint
                    val loraResult = GhostApi.loraStatus()
                    val loraData = loraResult.getOrDefault("Offline")

                    val nodes = mutableListOf<Triple<String, Int, Boolean>>()

                    if (loraData != "Offline" && loraData.contains("dBm")) {
                        // Real LoRa data: e.g. "-65 dBm"
                        val rssi = loraData.replace(" dBm", "").toIntOrNull() ?: -100
                        // Convert RSSI to a distance-like value: stronger signal = closer
                        val distance = rssiToDistance(rssi)
                        nodes.add(Triple("LoRa Node", distance, true))
                    }

                    // If no real nodes found, show a "scanning" state
                    if (nodes.isEmpty()) {
                        view?.findViewById<TextView>(R.id.tv_status)?.text = "No nodes detected \u2014 Scanning..."
                    } else {
                        view?.findViewById<TextView>(R.id.tv_status)?.text = "Tracking ${nodes.size} Active Node${if (nodes.size > 1) "s" else ""}"
                    }

                    updateNodesOnRadar(nodes)
                } catch (e: Exception) {
                    view?.findViewById<TextView>(R.id.tv_status)?.text = "Radar offline \u2014 Cannot reach ESP32"
                }

                delay(3000)
            }
        }
    }

    /** Convert RSSI to a radar-display distance (0-140dp range) */
    private fun rssiToDistance(rssi: Int): Int {
        // RSSI typically ranges from -30 (very close) to -120 (far/weak)
        val clamped = rssi.coerceIn(-120, -30)
        // Map to 20dp (closest) to 140dp (farthest)
        return (((-clamped - 30).toFloat() / 90f) * 120f + 20f).toInt()
    }

    /** Generate stable angle for a node name (deterministic) */
    private fun stableAngle(name: String): Double {
        val hash = name.hashCode().toLong() and 0xFFFFFFFFL
        return (hash % 360) * (Math.PI / 180.0)
    }

    private fun updateNodesOnRadar(nodes: List<Triple<String, Int, Boolean>>) {
        val container = view?.findViewById<RelativeLayout>(R.id.rl_radar_container) ?: return
        val density = resources.displayMetrics.density

        // Remove old node views
        val viewsToRemove = mutableListOf<View>()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child.tag == "node" || child.tag == "label") {
                viewsToRemove.add(child)
            }
        }
        viewsToRemove.forEach { container.removeView(it) }

        nodes.forEach { (name, distance, isOnline) ->
            val angle = stableAngle(name)
            val radius = distance.coerceIn(0, 140)
            val xOffset = (radius * Math.cos(angle)).toFloat()
            val yOffset = (radius * Math.sin(angle)).toFloat()

            // Node dot
            val dotSize = (10 * density).toInt()
            val nodeView = View(requireContext()).apply {
                layoutParams = RelativeLayout.LayoutParams(dotSize, dotSize).apply {
                    addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
                }
                background = resources.getDrawable(R.drawable.circle_btn_bg, null)
                background.setTint(if (isOnline) Color.parseColor("#10b981") else Color.parseColor("#6b7280"))
                translationX = xOffset * density
                translationY = yOffset * density
                tag = "node"
                alpha = 0f
            }
            container.addView(nodeView)

            // Fade-in animation
            nodeView.animate().alpha(1f).setDuration(600).start()

            // Node label
            val label = TextView(requireContext()).apply {
                text = name
                setTextColor(Color.parseColor("#94a3b8"))
                textSize = 9f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                layoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
                }
                translationX = xOffset * density
                translationY = (yOffset + 10) * density
                gravity = Gravity.CENTER
                tag = "label"
                alpha = 0f
            }
            container.addView(label)
            label.animate().alpha(1f).setDuration(600).setStartDelay(200).start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        sweepAnimator?.cancel()
        radarJob?.cancel()
    }
}
