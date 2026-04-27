package com.acronet.ui.anim

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.random.Random

/**
 * AcroNetTypingIndicator — Cryptographic Hash Matrix Decoder
 *
 * Instead of boring "User is typing...", this view renders a
 * shifting cryptographic hash matrix that slowly decodes into
 * the user's alias as Nostr packets arrive.
 *
 * Visual sequence:
 *   Frame 0:  "a7 f3 2b e9 c1 08 d4"   (random hex)
 *   Frame 10: "a7 f3 Al e9 c1 08 d4"   (first char decoded)
 *   Frame 20: "a7 f3 Al ic e1 08 d4"   (second char decoded)
 *   ...
 *   Frame N:  "Alice is typing..."      (fully decoded)
 *
 * The decoding speed is proportional to incoming packet rate,
 * creating a visual representation of network activity.
 */
class AcroNetTypingIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var targetAlias = ""
    private var decodedChars = 0
    private var matrixChars = CharArray(0)
    private var isAnimating = false
    private var animator: ValueAnimator? = null

    private val hexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22C55E") // Matrix green
        textSize = 36f
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.15f
    }

    private val decodedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A78BFA") // Violet for decoded chars
        textSize = 36f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.15f
    }

    private val suffixPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280")
        textSize = 30f
        typeface = Typeface.MONOSPACE
    }

    /**
     * Start the typing indicator for the given alias.
     * Call this when a "typing" event arrives from Nostr.
     */
    fun startDecoding(alias: String) {
        if (alias == targetAlias && isAnimating) return
        stopDecoding()

        targetAlias = alias
        decodedChars = 0

        // Initialize matrix with random hex chars
        val totalLen = alias.length + 12 // Extra hex noise
        matrixChars = CharArray(totalLen) { randomHexChar() }

        isAnimating = true
        visibility = VISIBLE

        // Animate: decode one char per 150ms
        animator = ValueAnimator.ofInt(0, alias.length).apply {
            duration = alias.length * 150L + 500L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val progress = anim.animatedValue as Int
                decodedChars = progress

                // Scramble undecoded positions
                for (i in progress until matrixChars.size) {
                    if (Random.nextFloat() > 0.7f) {
                        matrixChars[i] = randomHexChar()
                    }
                }

                // Place decoded chars
                for (i in 0 until progress.coerceAtMost(targetAlias.length)) {
                    matrixChars[i] = targetAlias[i]
                }

                invalidate()
            }
        }
        animator?.start()
    }

    fun stopDecoding() {
        animator?.cancel()
        isAnimating = false
        decodedChars = 0
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isAnimating || matrixChars.isEmpty()) return

        var xOffset = 24f
        val yCenter = height / 2f + hexPaint.textSize / 3f

        for (i in matrixChars.indices) {
            val char = matrixChars[i].toString()
            val paint = if (i < decodedChars) decodedPaint else hexPaint

            canvas.drawText(char, xOffset, yCenter, paint)
            xOffset += paint.measureText(char) + 4f

            if (xOffset > width - 100f) break // Clip to view width
        }

        // If fully decoded, append " is typing..."
        if (decodedChars >= targetAlias.length) {
            canvas.drawText(" encrypting...", xOffset, yCenter, suffixPaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (hexPaint.textSize * 2f).toInt()
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    private fun randomHexChar(): Char {
        val hex = "0123456789abcdef"
        return hex[Random.nextInt(hex.length)]
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopDecoding()
    }
}
