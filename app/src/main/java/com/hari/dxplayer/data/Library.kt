package com.hari.dxplayer.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val composer: String,
    val path: String,
    val durationMs: Long,
) {
    /** Text used for matching smart mixes. */
    val searchText: String by lazy { "$path $title $artist $album $composer".lowercase() }
}

data class Mix(val name: String, val keywords: List<String>)

object Library {

    /** Default smart mixes: a song joins a mix if any keyword appears in its folder, title, artist, album or composer. */
    private val defaultMixes = listOf(
        Mix("Gym", listOf("gym", "workout", "kuthu", "mass", "party", "dance")),
        Mix("Commute", listOf("commute", "train", "travel")),
        Mix("Car", listOf("car drive", "drive", "road")),
        Mix("Study", listOf("study", "instrumental", "bgm", "theme", "lofi")),
        Mix("Bass", listOf("bass", "808", "edm", "dubstep")),
        Mix("Orchestral", listOf("orchestral", "orchestra", "score", "symphony")),
        Mix("Tamil", listOf("tamil")),
        Mix("Remixes", listOf("remix", "mashup")),
        Mix("Tamil Classical", listOf("carnatic", "classical", "kriti", "raaga", "raga", "veena")),
        Mix("Anirudh", listOf("anirudh")),
        Mix("Love Songs", listOf("love", "melody", "kadhal", "romantic", "romance")),
        Mix("Harris Jayaraj", listOf("harris")),
    )

    /**
     * Your own mixes: put a mixes.json file in Android/data/com.hari.dxplayer/files/
     * like {"Gym": ["kuthu", "mass"], "Night": ["melody"]} to add or replace mixes.
     */
    fun mixes(ctx: Context): List<Mix> {
        val f = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "mixes.json")
        if (!f.exists()) return defaultMixes
        return try {
            val o = JSONObject(f.readText())
            val custom = o.keys().asSequence().map { name ->
                val arr = o.getJSONArray(name)
                Mix(name, (0 until arr.length()).map { arr.getString(it).lowercase() })
            }.toList()
            val names = custom.map { it.name }.toSet()
            defaultMixes.filter { it.name !in names } + custom
        } catch (e: Exception) {
            defaultMixes
        }
    }

    fun inMix(tracks: List<Track>, mix: Mix): List<Track> =
        tracks.filter { t -> mix.keywords.any { k -> t.searchText.contains(k) } }

    /** Main credited composer (falls back to the first artist). */
    fun composerOf(t: Track): String {
        val raw = t.composer.ifBlank { t.artist }
        return raw.split(",", "&", "/", ";", " feat", " ft.").first().trim().ifBlank { "Unknown" }
    }

    fun load(ctx: Context): List<Track> {
        val out = mutableListOf<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.COMPOSER,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION,
        )
        val base = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        ctx.contentResolver.query(
            base, projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iComposer = c.getColumnIndexOrThrow(MediaStore.Audio.Media.COMPOSER)
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                out += Track(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id),
                    title = c.getString(iTitle) ?: "Unknown",
                    artist = c.getString(iArtist)?.takeIf { it != "<unknown>" } ?: "",
                    album = c.getString(iAlbum) ?: "",
                    composer = c.getString(iComposer) ?: "",
                    path = c.getString(iData) ?: "",
                    durationMs = c.getLong(iDur),
                )
            }
        }
        return out
    }
}
