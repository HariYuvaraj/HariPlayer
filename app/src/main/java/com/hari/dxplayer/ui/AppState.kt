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
import com.hari.dxplayer.data.Track
import com.hari.dxplayer.dsp.PeqPreset
import com.hari.dxplayer.dsp.PresetStore
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
