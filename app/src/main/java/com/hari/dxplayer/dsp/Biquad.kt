package com.hari.dxplayer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One second-order filter (RBJ Audio EQ Cookbook), processed per channel. */
class Biquad(type: String, fs: Double, f0: Double, gainDb: Double, q: Double) {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private val z1 = DoubleArray(2)
    private val z2 = DoubleArray(2)

    init {
        val f = f0.coerceIn(10.0, fs * 0.45)
        val qq = q.coerceIn(0.1, 20.0)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / fs
        val cs = cos(w0)
        val sn = sin(w0)
        val alpha = sn / (2.0 * qq)
        val sqA = 2.0 * sqrt(a) * alpha
        val c: DoubleArray = when (type) {
            "LSC" -> doubleArrayOf(
                a * ((a + 1) - (a - 1) * cs + sqA),
                2 * a * ((a - 1) - (a + 1) * cs),
                a * ((a + 1) - (a - 1) * cs - sqA),
                (a + 1) + (a - 1) * cs + sqA,
                -2 * ((a - 1) + (a + 1) * cs),
                (a + 1) + (a - 1) * cs - sqA
            )
            "HSC" -> doubleArrayOf(
                a * ((a + 1) + (a - 1) * cs + sqA),
                -2 * a * ((a - 1) + (a + 1) * cs),
                a * ((a + 1) + (a - 1) * cs - sqA),
                (a + 1) - (a - 1) * cs + sqA,
                2 * ((a - 1) - (a + 1) * cs),
                (a + 1) - (a - 1) * cs - sqA
            )
            "HP" -> doubleArrayOf(
                (1 + cs) / 2, -(1 + cs), (1 + cs) / 2,
                1 + alpha, -2 * cs, 1 - alpha
            )
            else -> doubleArrayOf( // PK
                1 + alpha * a, -2 * cs, 1 - alpha * a,
                1 + alpha / a, -2 * cs, 1 - alpha / a
            )
        }
        val a0 = c[3]
        b0 = c[0] / a0; b1 = c[1] / a0; b2 = c[2] / a0
        a1 = c[4] / a0; a2 = c[5] / a0
    }

    /** Transposed direct form II. */
    fun process(x: Double, ch: Int): Double {
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1.fill(0.0); z2.fill(0.0)
    }
}
