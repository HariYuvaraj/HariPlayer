package com.hari.dxplayer.dsp

import android.content.Context

/**
 * Shared sound settings. The UI changes these; the audio processor picks up a
 * change on the next buffer by comparing [version].
 */
object SoundSettings {
    @Volatile var version = 0
        private set

    /** Bypass every effect (no EQ, no modes). */
    @Volatile var direct = false
    @Volatile var eqOn = true
    @Volatile var preset: PeqPreset? = null

    /** 0..10 sliders */
    @Volatile var rumble = 0          // sub-bass 20-60 Hz lift
    @Volatile var resonance = 0       // vinyl tone-arm style deep bass resonance
    @Volatile var turntable = 0       // warm mids, softer sibilance
    @Volatile var width = 5           // 5 = original stereo, lower = narrower, higher = wider

    /** 0 off, 1 warm triode, 2 classic pentode, 3 modern tube */
    @Volatile var tube = 0

    fun changed() {
        version++
    }

    private const val PREFS = "sound"

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("direct", direct)
            .putBoolean("eqOn", eqOn)
            .putString("preset", preset?.name)
            .putInt("rumble", rumble)
            .putInt("resonance", resonance)
            .putInt("turntable", turntable)
            .putInt("width", width)
            .putInt("tube", tube)
            .apply()
    }

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        direct = p.getBoolean("direct", false)
        eqOn = p.getBoolean("eqOn", true)
        rumble = p.getInt("rumble", 0)
        resonance = p.getInt("resonance", 0)
        turntable = p.getInt("turntable", 0)
        width = p.getInt("width", 5)
        tube = p.getInt("tube", 0)
        val name = p.getString("preset", null)
        preset = PresetStore.all(ctx).firstOrNull { it.name == name }
        changed()
    }
}
