package com.hari.dxplayer.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.tanh

/**
 * Applies the per-IEM PEQ and the sound modes to 16-bit PCM before it reaches
 * the DX270's DAC. With "Direct" on, audio passes through untouched.
 */
@OptIn(UnstableApi::class)
class SoundProcessor : BaseAudioProcessor() {
    private var fs = 44100.0
    private var channels = 2
    private var builtVersion = -1
    private var chain: Array<Biquad> = emptyArray()
    private var preGain = 1.0
    private var widthFactor = 1.0
    private var tubeK = 0.0
    private var tubeBias = 0.0
    private var tubeNorm = 1.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            // Anything other than 16-bit PCM plays untouched instead of failing.
            return AudioProcessor.AudioFormat.NOT_SET
        }
        fs = inputAudioFormat.sampleRate.toDouble()
        channels = inputAudioFormat.channelCount
        builtVersion = -1
        return inputAudioFormat
    }

    private fun rebuild() {
        val s = SoundSettings
        val list = mutableListOf<Biquad>()
        var boostDb = 0.0
        var presetPreamp = 0.0

        val p = s.preset
        if (s.eqOn && p != null) {
            presetPreamp = p.preampDb
            p.filters.forEach { list += Biquad(it.type, fs, it.fc, it.gainDb, it.q) }
        }
        if (s.rumble > 0) {
            val g = s.rumble * 1.0 // up to +10 dB
            list += Biquad("HP", fs, 16.0, 0.0, 0.71)          // protects the drivers below 16 Hz
            list += Biquad("LSC", fs, 60.0, g, 0.8)
            boostDb += g
        }
        if (s.resonance > 0) {
            val g = s.resonance * 0.8 // up to +8 dB, deep resonant bump
            list += Biquad("PK", fs, 48.0, g, 1.4)
            boostDb = maxOf(boostDb, g) + g * 0.3
        }
        if (s.turntable > 0) {
            list += Biquad("PK", fs, 320.0, s.turntable * 0.35, 0.8) // warmth
            list += Biquad("HSC", fs, 6500.0, -s.turntable * 0.6, 0.7) // softer sibilance
            boostDb += s.turntable * 0.1
        }
        // Tube sound: the style's tone shape, glow (saturation) and stereo width.
        val style = TubeStyles.get(s.tube)
        var styleBoost = 0.0
        for (f in style.filters) {
            list += Biquad(f.type, fs, f.fc, f.gainDb, f.q)
            if (f.gainDb > 0) styleBoost += f.gainDb
        }
        boostDb += styleBoost * 0.7
        tubeK = style.drive
        tubeBias = style.bias
        if (tubeK > 0) {
            val t = tanh(tubeBias)
            tubeNorm = 1.0 / (tubeK * (1 - t * t))
        }
        widthFactor = (0.5 + s.width * 0.1) * style.widthMul

        // Leave headroom for the boosts so loud passages do not clip.
        preGain = 10.0.pow((presetPreamp - min(boostDb, 14.0)) / 20.0)
        chain.forEach { it.reset() }
        chain = list.toTypedArray()
        builtVersion = s.version
    }

    private fun saturate(x: Double): Double =
        (tanh(tubeK * x + tubeBias) - tanh(tubeBias)) * tubeNorm

    private fun limit(x: Double): Double {
        val ax = abs(x)
        return if (ax <= 0.9) x else sign(x) * (0.9 + 0.1 * tanh((ax - 0.9) / 0.1))
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(remaining)
        val s = SoundSettings
        if (s.direct) {
            out.put(input)
            out.flip()
            return
        }
        if (builtVersion != s.version) rebuild()

        val frameBytes = 2 * channels
        val frames = remaining / frameBytes
        val stereo = channels >= 2
        val doWidth = stereo && abs(widthFactor - 1.0) > 0.01
        val doTube = tubeK > 0
        for (i in 0 until frames) {
            var l = input.getShort() / 32768.0
            var r = if (stereo) input.getShort() / 32768.0 else l
            for (c in 2 until channels) input.getShort()

            l *= preGain
            r *= preGain
            for (bq in chain) {
                l = bq.process(l, 0)
                r = bq.process(r, 1)
            }
            if (doWidth) {
                val mid = (l + r) * 0.5
                val side = (l - r) * 0.5 * widthFactor
                l = mid + side
                r = mid - side
            }
            if (doTube) {
                l = saturate(l)
                r = saturate(r)
            }
            l = limit(l)
            r = limit(r)
            out.putShort((l * 32767.0).toInt().toShort())
            if (stereo) out.putShort((r * 32767.0).toInt().toShort())
            for (c in 2 until channels) out.putShort(0)
        }
        input.position(input.limit())
        out.flip()
    }

    override fun onFlush() {
        chain.forEach { it.reset() }
    }
}
