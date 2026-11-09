package com.hari.dxplayer.dsp

/**
 * Tube-style sound characters, named by what they do to the music.
 *
 * Each one copies the tonal tendency audiophiles describe for a tube family
 * (an EQ shape), plus a soft "tube glow" (gentle harmonics from a biased
 * saturation curve) and a change in stereo width. It's an emulation of the
 * character, not of a specific circuit.
 */
data class TubeStyle(
    val label: String,       // shown on the button
    val short: String,       // shown in summaries
    val hint: String,        // one line under the buttons
    val filters: List<PeqFilter>,
    val drive: Double,       // saturation amount (0 = none)
    val bias: Double,        // asymmetry: more = more even-order (warm) harmonics
    val widthMul: Double,    // 1 = unchanged stereo width
)

object TubeStyles {
    private fun pk(f: Double, g: Double, q: Double) = PeqFilter("PK", f, g, q)
    private fun ls(f: Double, g: Double, q: Double = 0.7) = PeqFilter("LSC", f, g, q)
    private fun hs(f: Double, g: Double, q: Double = 0.7) = PeqFilter("HSC", f, g, q)

    val all = listOf(
        TubeStyle("Off", "Off", "No tube character.", emptyList(), 0.0, 0.0, 1.0),
        // 6SN7-style: fat, textured mid-bass and depth
        TubeStyle(
            "Big warm bass", "Warm bass",
            "Thick, punchy mid-bass with warmth and depth. Great for Tamil kuthu and film songs.",
            listOf(pk(90.0, 3.5, 0.7), pk(220.0, 1.2, 0.9), hs(9000.0, -1.5)), 1.8, 0.25, 1.1
        ),
        // 6SN7 character plus digital sub-bass (tubes alone don't add rumble)
        TubeStyle(
            "Bass + rumble", "Bass+rumble",
            "Warm tube bass plus deep sub-bass rumble below 50 Hz. For bass-heads and EDM.",
            listOf(ls(50.0, 5.0, 0.8), pk(100.0, 2.5, 0.8), hs(9000.0, -1.0)), 1.7, 0.22, 1.05
        ),
        // Long-plate 12AX7-style: fuller, richer low end
        TubeStyle(
            "Fuller, richer sound", "Fuller",
            "More body and weight across bass and lower mids, without getting boomy.",
            listOf(ls(120.0, 2.5), pk(280.0, 1.2, 0.8)), 1.5, 0.2, 1.0
        ),
        // V-shape: punch and sparkle, slightly recessed mids
        TubeStyle(
            "Punchy V-shape", "V-shape",
            "Bass punch and treble sparkle with slightly pulled-back mids. Fun for pop and EDM.",
            listOf(pk(70.0, 3.0, 0.9), pk(1500.0, -1.5, 0.8), hs(8000.0, 2.0)), 1.4, 0.1, 1.1
        ),
        // 12AU7 / 12AT7-style: linear, airy, spacious
        TubeStyle(
            "Clean & spacious", "Spacious",
            "Airy and open with a wider stage. Best for orchestral scores and Tamil classical.",
            listOf(pk(300.0, -0.8, 0.8), hs(10000.0, 1.5)), 1.15, 0.05, 1.25
        ),
        // 6K4-style: warms a thin setup and widens the stage
        TubeStyle(
            "Warm & wide", "Warm & wide",
            "Gentle warmth with a wider soundstage. Lovely for instrumentals and acoustic music.",
            listOf(pk(150.0, 1.5, 0.8), hs(8000.0, -1.0)), 1.3, 0.15, 1.15
        ),
        // Warm mids for vocals
        TubeStyle(
            "Smooth vocals", "Vocals",
            "Rich, forward voices with softer sibilance. For melodies and love songs.",
            listOf(pk(250.0, 1.0, 0.9), pk(1200.0, 1.5, 0.9), pk(6500.0, -2.0, 2.0)), 1.4, 0.18, 0.95
        ),
    )

    fun get(i: Int): TubeStyle = all.getOrElse(i) { all[0] }
}
