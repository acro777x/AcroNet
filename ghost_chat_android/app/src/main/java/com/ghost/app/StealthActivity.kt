package com.ghost.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * StealthActivity — Fake Calculator UI.
 * Phase 3C: Disguises app as a calculator.
 * Enter secret PIN (default: 1337) + "=" to launch Ghost Chat.
 */
class StealthActivity : AppCompatActivity() {

    private lateinit var tvDisplay: TextView
    private var currentInput = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stealth_calculator)

        tvDisplay = findViewById(R.id.tv_calc_display)

        // Number buttons
        val buttonIds = mapOf(
            R.id.btn_0 to "0", R.id.btn_1 to "1", R.id.btn_2 to "2",
            R.id.btn_3 to "3", R.id.btn_4 to "4", R.id.btn_5 to "5",
            R.id.btn_6 to "6", R.id.btn_7 to "7", R.id.btn_8 to "8",
            R.id.btn_9 to "9", R.id.btn_dot to "."
        )

        for ((id, value) in buttonIds) {
            findViewById<Button>(id).setOnClickListener {
                currentInput += value
                tvDisplay.text = currentInput
            }
        }

        // Operators (fake — just display)
        val opIds = mapOf(
            R.id.btn_plus to "+", R.id.btn_minus to "-",
            R.id.btn_multiply to "×", R.id.btn_divide to "÷"
        )
        for ((id, op) in opIds) {
            findViewById<Button>(id).setOnClickListener {
                currentInput += op
                tvDisplay.text = currentInput
            }
        }

        // Clear
        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            currentInput = ""
            tvDisplay.text = "0"
        }

        // Equals — CHECK SECRET PIN
        findViewById<Button>(R.id.btn_equals).setOnClickListener {
            val pin = SecurePrefs.getStealthPin()
            // Check if input ends with the PIN
            val cleanInput = currentInput.replace(Regex("[+\\-×÷.]"), "")
            if (cleanInput == pin) {
                // Launch Ghost Chat
                SecurePrefs.init(this)
                val intent = android.content.Intent(this, MainActivity::class.java)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            } else {
                // Fake calculation result
                try {
                    tvDisplay.text = evaluateSimple(currentInput)
                } catch (_: Exception) {
                    tvDisplay.text = "Error"
                }
                currentInput = ""
            }
        }
    }

    private fun evaluateSimple(expr: String): String {
        return try {
            val str = expr.replace("×", "*").replace("÷", "/")
            val result = object : Any() {
                var pos = -1
                var ch = 0

                fun nextChar() {
                    ch = if (++pos < str.length) str[pos].code else -1
                }

                fun eat(charToEat: Int): Boolean {
                    while (ch == ' '.code) nextChar()
                    if (ch == charToEat) {
                        nextChar()
                        return true
                    }
                    return false
                }

                fun parse(): Double {
                    nextChar()
                    val x = parseExpression()
                    if (pos < str.length) throw RuntimeException("Unexpected: " + ch.toChar())
                    return x
                }

                fun parseExpression(): Double {
                    var x = parseTerm()
                    while (true) {
                        if (eat('+'.code)) x += parseTerm()
                        else if (eat('-'.code)) x -= parseTerm()
                        else return x
                    }
                }

                fun parseTerm(): Double {
                    var x = parseFactor()
                    while (true) {
                        if (eat('*'.code)) x *= parseFactor()
                        else if (eat('/'.code)) x /= parseFactor()
                        else return x
                    }
                }

                fun parseFactor(): Double {
                    if (eat('+'.code)) return parseFactor()
                    if (eat('-'.code)) return -parseFactor()
                    var x: Double
                    val startPos = pos
                    if (eat('('.code)) {
                        x = parseExpression()
                        eat(')'.code)
                    } else if (ch >= '0'.code && ch <= '9'.code || ch == '.'.code) {
                        while (ch >= '0'.code && ch <= '9'.code || ch == '.'.code) nextChar()
                        x = str.substring(startPos, pos).toDouble()
                    } else {
                        throw RuntimeException("Unexpected")
                    }
                    return x
                }
            }.parse()

            if (result == result.toLong().toDouble()) result.toLong().toString()
            else result.toString()
        } catch (_: Exception) { "Error" }
    }
}
