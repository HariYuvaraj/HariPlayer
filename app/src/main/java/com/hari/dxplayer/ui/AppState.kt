package com.hari.dxplayer.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.hari.dxplayer.data.Library
import com.hari.dxplayer.data.Mix
import com.hari.dxplayer.data.Squig
import com.hari.dxplayer.data.Track
import com.hari.dxplayer.dsp.AutoEq
import com.hari.dxplayer.dsp.PeqPreset
import com.hari.dxplayer.dsp.PresetStore
import com.hari.dxplayer.dsp.SoundSettings
import java.io.File
import com.hari.dxplayer.support.CrashReporter
import com.hari.dxplayer.support.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object Composers : Screen
    data class Tracks(val title: String, val list: List<Track>) : Screen
    data object Now : Screen
    data object Eq : Screen
    data object Modes : Screen
    data object Support : Screen
    data object PickIem : Screen
    data object PickTarget : Screen
}

class AppState {
    var tracks by mutableStateOf<List<Track>>(emptyList())
    var mixes by mutableStateOf<List<Mix>>(emptyList())
    var presets by mutableStateOf<List<PeqPreset>>(emptyList())
    var loading by mutableStateOf(true)
    var permissionDenied by mutableStateOf(false)
    var controller by mutableStateOf<MediaController?>(null)

    var nowTrack by mutableStateOf<Track?>(null)
    var nowTitle by mutableStateOf("")
    var nowArtist by mutableStateOf("")
    var isPlaying by mutableStateOf(false)
    var shuffle by mutableStateOf(false)
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF)

    // Support: crash reports, safe mode, updates
    var pendingCrash by mutableStateOf<String?>(null)
    var safeMode by mutableStateOf(false)
    var update by mutableStateOf<Updater.Update?>(null)
    var updateStatus by mutableStateOf("")
    var updateBusy by mutableStateOf(false)

    suspend fun checkForUpdate(ctx: Context, installIfAuto: Boolean) {
        if (Updater.repo.isBlank()) {
            updateStatus = "Updates are not set up yet (see README)."
            return
        }
        updateStatus = "Checking…"
        update = try {
            Updater.check()
        } catch (e: Exception) {
            updateStatus = "Couldn't check: ${e.message ?: "no connection"}"
            return
        }
        updateStatus = update?.let { "Update available: build ${it.versionCode}" } ?: "You have the latest version."
        if (installIfAuto && update != null && Updater.autoRootInstall(ctx)) installUpdate(ctx)
    }

    suspend fun installUpdate(ctx: Context) {
        val u = update ?: return
        if (updateBusy) return
        updateBusy = true
        try {
            updateStatus = "Downloading build ${u.versionCode}…"
            val apk = Updater.download(ctx, u)
            if (Updater.autoRootInstall(ctx)) {
                updateStatus = "Installing with root…"
                if (Updater.installWithRoot(apk)) {
                    updateStatus = "Installed. Reopening…"
                    return
                }
                updateStatus = "Root install didn't work – using the normal installer."
            }
            updateStatus = if (Updater.installWithPrompt(ctx, apk)) "Tap Update on the screen that opens."
            else "Allow Hari Player to install apps, then come back and tap Install again."
        } catch (e: Exception) {
            updateStatus = "Update failed: ${e.message}"
        } finally {
            updateBusy = false
        }
    }

    fun refreshSupport(ctx: Context) {
        pendingCrash = CrashReporter.pendingReport(ctx)
        safeMode = CrashReporter.safeModeActive(ctx)
    }

    // AutoEQ: IEM + target from squig.link
    class AutoEqOutcome(
        val result: AutoEq.Result,
        val iem: Squig.IemChoice,
        val target: Squig.TargetChoice,
        val note: String?,
    )

    var squig by mutableStateOf<Squig.Index?>(null)
    var squigStatus by mutableStateOf("")
    var squigBusy by mutableStateOf(false)
    var pickedIem by mutableStateOf<Squig.IemChoice?>(null)
    var pickedTarget by mutableStateOf<Squig.TargetChoice?>(null)
    var autoEq by mutableStateOf<AutoEqOutcome?>(null)
    var autoEqBusy by mutableStateOf(false)
    var autoEqStatus by mutableStateOf("")
    private var autoEqRestored = false

    fun restoreAutoEq(ctx: Context) {
        if (autoEqRestored) return
        autoEqRestored = true
        val (i, t) = Squig.restore(ctx)
        pickedIem = i ?: Squig.myIems.first()
        pickedTarget = t ?: Squig.myTargets.first()
    }

    /** Loads the cached squig.link list, building it when missing, old or [force]d. */
    suspend fun loadSquig(ctx: Context, force: Boolean = false) {
        if (squigBusy) return
        if (!force && squig == null) squig = withContext(Dispatchers.IO) { Squig.loadCached(ctx) }
        val old = squig?.let { System.currentTimeMillis() - it.built > 14L * 24 * 3600 * 1000 } ?: true
        if (!force && !old) {
            squigStatus = "${squig!!.iems.size} IEMs and ${squig!!.targets.size} targets from squig.link"
            return
        }
        squigBusy = true
        squigStatus = "Reading squig.link…"
        try {
            squig = Squig.build(ctx) { done, total -> squigStatus = "Reading squig.link… $done of $total sites" }
            squigStatus = "${squig!!.iems.size} IEMs and ${squig!!.targets.size} targets from squig.link"
        } catch (e: Exception) {
            squigStatus = if (squig != null) "Couldn't refresh (${e.message}). Using the saved list."
            else "Couldn't read squig.link: ${e.message ?: "no connection"}. Your own IEMs and targets still work."
        } finally {
            squigBusy = false
        }
    }

    fun pick(ctx: Context, iem: Squig.IemChoice? = pickedIem, target: Squig.TargetChoice? = pickedTarget) {
        pickedIem = iem
        pickedTarget = target
        autoEq = null
        autoEqStatus = ""
        Squig.save(ctx, iem, target)
    }

    suspend fun makeEq(ctx: Context) {
        val iem = pickedIem ?: return
        val target = pickedTarget ?: return
        if (autoEqBusy) return
        autoEqBusy = true
        autoEq = null
        try {
            autoEqStatus = "Downloading ${iem.label}…"
            val m = Squig.iemCurve(iem) ?: throw IllegalStateException("couldn't download the ${iem.label} measurement from ${iem.db.name}")
            autoEqStatus = "Downloading ${target.name}…"
            val (t, from) = Squig.targetCurve(ctx, target, iem, squig)
                ?: throw IllegalStateException(
                    if (target.ref != null) "${target.name} isn't measured on squig.link (or the list hasn't loaded yet)"
                    else "couldn't download the ${target.name} target"
                )
            autoEqStatus = "Working out the EQ…"
            val r = withContext(Dispatchers.Default) { AutoEq.fit("${iem.label} → ${target.name}", m, t) }
            val note = when {
                from == null -> null
                from.rig != iem.db.rig ->
                    "The target is for the ${from.rig} rig but this IEM was measured on ${iem.db.rig}, so treble above about 5 kHz may be off."
                from.base != iem.db.base || target.ref != null -> "Target measured by ${from.name}."
                else -> null
            }
            autoEq = AutoEqOutcome(r, iem, target, note)
            autoEqStatus = ""
        } catch (e: Exception) {
            autoEqStatus = "Couldn't make the EQ: ${e.message}"
        } finally {
            autoEqBusy = false
        }
    }

    /** Saves the result as a preset file and switches to it. */
    fun useEq(ctx: Context) {
        val o = autoEq ?: return
        val name = "${o.iem.label} - ${o.target.name}".replace(Regex("""[\\/:*?"<>|]"""), "_")
        val p = o.result.preset.copy(name = name)
        File(PresetStore.dir(ctx), "$name.txt").writeText(AutoEq.toText(p))
        presets = PresetStore.all(ctx)
        SoundSettings.preset = presets.firstOrNull { it.name == name } ?: p
        SoundSettings.eqOn = true
        SoundSettings.direct = false
        SoundSettings.changed()
        SoundSettings.save(ctx)
        soundTick++
        autoEqStatus = "Now playing with \"$name\"."
    }

    /** Bumped whenever sound settings change so screens redraw. */
    var soundTick by mutableStateOf(0)

    private val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun go(s: Screen) {
        if (stack.last() != s) stack.add(s)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    suspend fun loadAll(ctx: Context) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            Triple(Library.load(ctx), Library.mixes(ctx), PresetStore.all(ctx))
        }
        tracks = result.first
        mixes = result.second
        presets = result.third
        loading = false
        controller?.let { updateFrom(it) }
    }

    fun composerGroups(): List<Pair<String, List<Track>>> =
        tracks.groupBy { Library.composerOf(it) }
            .toList()
            .sortedByDescending { it.second.size }

    fun play(list: List<Track>, start: Int, shuffled: Boolean = false) {
        val c = controller ?: return
        if (list.isEmpty()) return
        // Keep the queue to a sensible size around the chosen song.
        val from = (start - 500).coerceAtLeast(0)
        val window = list.subList(from, (from + 1000).coerceAtMost(list.size))
        c.setMediaItems(window.map { it.toMediaItem() }, start - from, 0L)
        c.shuffleModeEnabled = shuffled
        c.prepare()
        c.play()
        go(Screen.Now)
    }

    fun updateFrom(p: Player) {
        val id = p.currentMediaItem?.mediaId?.toLongOrNull()
        nowTrack = tracks.firstOrNull { it.id == id }
        nowTitle = p.mediaMetadata.title?.toString() ?: nowTrack?.title ?: ""
        nowArtist = p.mediaMetadata.artist?.toString() ?: nowTrack?.artist ?: ""
        isPlaying = p.isPlaying
        shuffle = p.shuffleModeEnabled
        repeatMode = p.repeatMode
    }
}

fun Track.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setComposer(composer)
                .build()
        )
        .build()
