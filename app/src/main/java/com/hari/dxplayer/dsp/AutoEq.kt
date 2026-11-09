package com.hari.dxplayer.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A frequency response: frequencies in Hz (ascending) and levels in dB. */
class FrCurve(val hz: DoubleArray, val db: DoubleArray) {
    companion object {
        /** Reads squig.link / REW / AutoEQ text: lines of "freq  dB [phase]", comment lines ignored. */
        fun parse(text: String): FrCurve? {
            val f = ArrayList<Double>()
            val d = ArrayList<Double>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || !(line[0].isDigit() || line[0] == '.')) continue
                val parts = line.split(Regex("""[\s,;]+"""))
                if (parts.size < 2) continue
                val hz = parts[0].toDoubleOrNull() ?: continue
                val db = parts[1].toDoubleOrNull() ?: continue
                if (hz <= 0 || !hz.isFinite() || !db.isFinite()) continue
                if (f.isNotEmpty() && hz <= f.last()) continue
                f += hz; d += db
            }
            return if (f.size >= 10) FrCurve(f.toDoubleArray(), d.toDoubleArray()) else null
        }

        /** Average of several curves (e.g. left and right) on a shared grid. */
        fun average(curves: List<FrCurve>, grid: DoubleArray): DoubleArray {
            val out = DoubleArray(grid.size)
            for (c in curves) {
                val v = c.on(grid)
                for (i in grid.indices) out[i] += v[i] / curves.size
            }
            return out
        }
    }

    /** Linear interpolation in log-frequency onto [grid]; ends are held flat. */
    fun on(grid: DoubleArray): DoubleArray {
        val out = DoubleArray(grid.size)
        var j = 0
        for (i in grid.indices) {
            val g = grid[i]
            if (g <= hz.first()) { out[i] = db.first(); continue }
            if (g >= hz.last()) { out[i] = db.last(); continue }
            while (j < hz.size - 2 && hz[j + 1] < g) j++
            val x0 = ln(hz[j]); val x1 = ln(hz[j + 1])
            val t = (ln(g) - x0) / (x1 - x0)
            out[i] = db[j] + t * (db[j + 1] - db[j])
        }
        return out
    }
}

/**
 * Works out a parametric EQ that moves an IEM's measured response onto a target,
 * in the spirit of AutoEQ: 1 low shelf, 1 high shelf and peaking filters,
 * fitted on a 1/24-octave grid, with treble above ~10 kHz trusted less.
 */
object AutoEq {
    class Result(
        val preset: PeqPreset,
        val grid: DoubleArray,
        val iem: DoubleArray,       // measured, level-matched to the target
        val target: DoubleArray,
        val eq: DoubleArray,        // EQ curve (dB) without preamp
        val errorBefore: Double,    // RMS dB, 20 Hz - 10 kHz
        val errorAfter: Double,
    )

    private const val FS = 48000.0
    const val MAX_BOOST = 12.0

    val grid: DoubleArray = run {
        val n = floor(24 * log2(20000.0 / 20.0)).toInt() + 1
        DoubleArray(n) { 20.0 * 2.0.pow(it / 24.0) }
    }

    private class Filter(var type: String, var fc: Double, var gain: Double, var q: Double) {
        var resp = DoubleArray(0)
    }

    fun fit(name: String, iemRaw: DoubleArray, targetRaw: DoubleArray, maxFilters: Int = 10): Result {
        val n = grid.size
        // Level-match the IEM to the target over the midrange.
        var off = 0.0; var cnt = 0
        for (i in 0 until n) if (grid[i] in 250.0..2500.0) { off += targetRaw[i] - iemRaw[i]; cnt++ }
        off /= max(cnt, 1)
        val iem = DoubleArray(n) { iemRaw[it] + off }

        // How much the treble is trusted: full to 8 kHz, fading out by 16 kHz.
        val w = DoubleArray(n) {
            val f = grid[it]
            when {
                f <= 8000 -> 1.0
                f >= 16000 -> 0.0
                else -> 1.0 - log2(f / 8000.0)   // 1 at 8k, 0 at 16k
            }
        }
        val raw = DoubleArray(n) { targetRaw[it] - iem[it] }
        val desired = smooth(raw, 1.0 / 6.0)
        // Heavier smoothing in the treble, where measurements are least reliable.
        val desiredHi = smooth(raw, 0.5)
        for (i in 0 until n) {
            if (grid[i] > 6000) {
                val t = min(1.0, log2(grid[i] / 6000.0))
                desired[i] = desired[i] * (1 - t) + desiredHi[i] * t
            }
            desired[i] = desired[i].coerceIn(-MAX_BOOST, MAX_BOOST)
        }

        val filters = ArrayList<Filter>()
        fun total(): DoubleArray {
            val t = DoubleArray(n)
            for (f in filters) for (i in 0 until n) t[i] += f.resp[i]
            return t
        }
        fun add(f: Filter) { f.resp = response(f); filters += f }

        add(Filter("LSC", 105.0, bandMean(desired, 20.0, 80.0).coerceIn(-MAX_BOOST, MAX_BOOST), 0.71))
        if (maxFilters >= 3) add(Filter("HSC", 10000.0, (bandMean(desired, 10000.0, 14000.0) * 0.5).coerceIn(-8.0, 8.0), 0.71))

        // Greedy start: put a peak filter on the biggest remaining error.
        while (filters.size < maxFilters) {
            val res = total().let { t -> DoubleArray(n) { (desired[it] - t[it]) * w[it] } }
            var bi = -1; var bv = 0.0
            for (i in 0 until n) if (grid[i] in 25.0..10000.0 && abs(res[i]) > bv) { bv = abs(res[i]); bi = i }
            if (bi < 0 || bv < 0.6) break
            val g = res[bi].coerceIn(-MAX_BOOST, MAX_BOOST)
            var lo = bi; while (lo > 0 && abs(res[lo]) > bv / 2 && res[lo] * g > 0) lo--
            var hi = bi; while (hi < n - 1 && abs(res[hi]) > bv / 2 && res[hi] * g > 0) hi++
            val bw = max(log2(grid[hi] / grid[lo]), 0.15)
            val q = (sqrt(2.0.pow(bw)) / (2.0.pow(bw) - 1)).coerceIn(0.5, 5.0)
            add(Filter("PK", grid[bi], g, q))
        }

        // Refine all filters together (coordinate search on log-freq, gain and Q).
        fun loss(t: DoubleArray): Double {
            var s = 0.0; var ws = 0.0
            for (i in 0 until n) { val e = desired[i] - t[i]; s += w[i] * e * e; ws += w[i] }
            return s / ws
        }
        var cur = total()
        var best = loss(cur)
        var fStep = 0.25; var gStep = 1.0; var qStep = 1.3
        repeat(80) {
            var improved = false
            for (f in filters) {
                for (p in 0 until 3) for (dir in intArrayOf(1, -1)) {
                    val old = Triple(f.fc, f.gain, f.q)
                    val oldResp = f.resp
                    when (p) {
                        0 -> f.fc = (f.fc * 2.0.pow(dir * fStep)).coerceIn(if (f.type == "PK") 20.0 else 40.0, 16000.0)
                        1 -> f.gain = (f.gain + dir * gStep).coerceIn(-MAX_BOOST, MAX_BOOST)
                        else -> f.q = (if (dir > 0) f.q * qStep else f.q / qStep).coerceIn(
                            if (f.type == "PK") 0.3 else 0.4, if (f.type == "PK") 6.0 else 1.0
                        )
                    }
                    f.resp = response(f)
                    val t = DoubleArray(n) { cur[it] - oldResp[it] + f.resp[it] }
                    val l = loss(t)
                    if (l < best - 1e-6) { best = l; cur = t; improved = true }
                    else { f.fc = old.first; f.gain = old.second; f.q = old.third; f.resp = oldResp }
                }
            }
            if (!improved) {
                fStep /= 2; gStep /= 2; qStep = sqrt(qStep)
                if (gStep < 0.02) return@repeat
            }
        }

        filters.removeAll { abs(it.gain) < 0.3 }
        val eq = total()
        val peak = (0 until n).maxOf { eq[it] }
        val preamp = -(max(0.0, peak) + 0.3)
        val preset = PeqPreset(
            name, round1(preamp),
            filters.sortedBy { it.fc }.map { PeqFilter(it.type, round1(it.fc), round1(it.gain), round2(it.q)) }
        )
        return Result(preset, grid, iem, targetRaw, eq, rms(raw, null), rms(raw, eq))
    }

    /** RMS error from 20 Hz to 10 kHz, optionally after applying [eq]. */
    private fun rms(raw: DoubleArray, eq: DoubleArray?): Double {
        var s = 0.0; var c = 0
        for (i in grid.indices) if (grid[i] <= 10000) { val e = raw[i] - (eq?.get(i) ?: 0.0); s += e * e; c++ }
        return sqrt(s / c)
    }

    private fun bandMean(v: DoubleArray, lo: Double, hi: Double): Double {
        var s = 0.0; var c = 0
        for (i in grid.indices) if (grid[i] in lo..hi) { s += v[i]; c++ }
        return if (c == 0) 0.0 else s / c
    }

    /** Fractional-octave smoothing on the 1/24-octave grid. */
    private fun smooth(v: DoubleArray, octaves: Double): DoubleArray {
        val half = (octaves * 24 / 2).toInt().coerceAtLeast(1)
        return DoubleArray(v.size) { i ->
            var s = 0.0; var c = 0
            for (j in max(0, i - half)..min(v.size - 1, i + half)) { s += v[j]; c++ }
            s / c
        }
    }

    private fun response(f: Filter): DoubleArray = responseOf(f.type, f.fc, f.gain, f.q, grid)

    /** Magnitude (dB) of an RBJ biquad at each frequency. */
    fun responseOf(type: String, fc: Double, gainDb: Double, q: Double, freqs: DoubleArray): DoubleArray {
        val c = coeffs(type, fc, gainDb, q)
        return DoubleArray(freqs.size) {
            val wv = 2 * PI * freqs[it] / FS
            val c1 = cos(wv); val s1 = sin(wv); val c2 = cos(2 * wv); val s2 = sin(2 * wv)
            val nr = c[0] + c[1] * c1 + c[2] * c2; val ni = -(c[1] * s1 + c[2] * s2)
            val dr = 1 + c[3] * c1 + c[4] * c2; val di = -(c[3] * s1 + c[4] * s2)
            10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
        }
    }

    private fun coeffs(type: String, f0: Double, gainDb: Double, q: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * f0 / FS
        val cs = cos(w0); val sn = sin(w0)
        val alpha = sn / (2 * q)
        val sq = 2 * sqrt(a) * alpha
        val c = when (type) {
            "LSC" -> doubleArrayOf(
                a * ((a + 1) - (a - 1) * cs + sq), 2 * a * ((a - 1) - (a + 1) * cs), a * ((a + 1) - (a - 1) * cs - sq),
                (a + 1) + (a - 1) * cs + sq, -2 * ((a - 1) + (a + 1) * cs), (a + 1) + (a - 1) * cs - sq
            )
            "HSC" -> doubleArrayOf(
                a * ((a + 1) + (a - 1) * cs + sq), -2 * a * ((a - 1) + (a + 1) * cs), a * ((a + 1) + (a - 1) * cs - sq),
                (a + 1) - (a - 1) * cs + sq, 2 * ((a - 1) - (a + 1) * cs), (a + 1) - (a - 1) * cs - sq
            )
            else -> doubleArrayOf(1 + alpha * a, -2 * cs, 1 - alpha * a, 1 + alpha / a, -2 * cs, 1 - alpha / a)
        }
        return doubleArrayOf(c[0] / c[3], c[1] / c[3], c[2] / c[3], c[4] / c[3], c[5] / c[3])
    }

    private fun round1(x: Double) = Math.round(x * 10) / 10.0
    private fun round2(x: Double) = Math.round(x * 100) / 100.0

    /** Equalizer APO / AutoEQ ParametricEQ.txt text, read back by [PeqPreset.parse]. */
    fun toText(p: PeqPreset): String = buildString {
        appendLine("Preamp: %.1f dB".format(java.util.Locale.US, p.preampDb))
        p.filters.forEachIndexed { i, f ->
            appendLine("Filter ${i + 1}: ON ${f.type} Fc %.1f Hz Gain %.1f dB Q %.2f".format(java.util.Locale.US, f.fc, f.gainDb, f.q))
        }
    }

}
