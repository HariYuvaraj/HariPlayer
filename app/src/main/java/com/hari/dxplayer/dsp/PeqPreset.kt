package com.hari.dxplayer.dsp

import android.content.Context
import android.net.Uri
import java.io.File

data class PeqFilter(val type: String, val fc: Double, val gainDb: Double, val q: Double)

data class PeqPreset(val name: String, val preampDb: Double, val filters: List<PeqFilter>) {
    companion object {
        private val filterRe = Regex(
            """Filter\s*\d*\s*:\s*ON\s+(PK|PEQ|LSC|LS|HSC|HS)\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+(-?[\d.]+)\s*dB(?:\s+Q\s+([\d.]+))?""",
            RegexOption.IGNORE_CASE
        )
        private val numberRe = Regex("""-?\d+(\.\d+)?""")

        /** Reads the Equalizer APO / AutoEQ "ParametricEQ.txt" format. */
        fun parse(name: String, text: String): PeqPreset {
            var preamp = 0.0
            val filters = mutableListOf<PeqFilter>()
            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.startsWith("Preamp", ignoreCase = true)) {
                    numberRe.find(line.substringAfter(":"))?.let { preamp = it.value.toDouble() }
                }
                filterRe.find(line)?.let { m ->
                    val type = when (m.groupValues[1].uppercase()) {
                        "LS", "LSC" -> "LSC"
                        "HS", "HSC" -> "HSC"
                        else -> "PK"
                    }
                    filters += PeqFilter(
                        type,
                        m.groupValues[2].toDouble(),
                        m.groupValues[3].toDouble(),
                        m.groupValues[4].toDoubleOrNull() ?: 0.71
                    )
                }
            }
            return PeqPreset(name, preamp, filters)
        }
    }
}

/** Presets live in the app's own folder on internal storage: Android/data/com.hari.dxplayer/files/eq */
object PresetStore {
    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "eq").apply { mkdirs() }

    fun all(ctx: Context): List<PeqPreset> {
        val d = dir(ctx)
        if (d.listFiles()?.isEmpty() != false) writeSample(d)
        return d.listFiles { f -> f.extension.equals("txt", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { PeqPreset.parse(it.nameWithoutExtension, it.readText()) }
            ?: emptyList()
    }

    fun import(ctx: Context, uri: Uri, displayName: String?) {
        val base = (displayName ?: "Imported preset").substringBeforeLast('.')
            .replace(Regex("""[^\w\s.+\-()]"""), "_").ifBlank { "Imported preset" }
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            File(dir(ctx), "$base.txt").outputStream().use { input.copyTo(it) }
        }
    }

    private fun writeSample(d: File) {
        File(d, "Example - Sub-bass shelf.txt").writeText(
            "Preamp: -6.5 dB\nFilter 1: ON LSC Fc 60 Hz Gain 6.0 dB Q 0.71\n"
        )
    }
}
