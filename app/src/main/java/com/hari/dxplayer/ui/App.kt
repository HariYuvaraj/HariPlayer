package com.hari.dxplayer.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import com.hari.dxplayer.data.Library
import com.hari.dxplayer.data.Track
import com.hari.dxplayer.dsp.PresetStore
import com.hari.dxplayer.BuildConfig
import com.hari.dxplayer.dsp.SoundSettings
import com.hari.dxplayer.dsp.TubeStyles
import com.hari.dxplayer.support.CrashReporter
import com.hari.dxplayer.support.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- colours
internal val Bg = Color(0xFF0E0F12)
internal val Card = Color(0xFF181A1F)
internal val Raised = Color(0xFF1F2229)
internal val Line = Color(0xFF3A3D46)
internal val Ink = Color(0xFFF2F2F4)
internal val Muted = Color(0xFFA9ACB6)
internal val Accent = Color(0xFFF5B54A)
private val Art = Color(0xFF3B2A12)
private val mixDots = listOf(
    0xFFF5B54A, 0xFF4FD1C5, 0xFF8AB4FF, 0xFFC3A6FF, 0xFFFF8A65, 0xFF9CCC65,
    0xFFF48FB1, 0xFFFFD54F, 0xFF80CBC4, 0xFFF5B54A, 0xFFF48FB1, 0xFF4FD1C5
).map { Color(it) }

@Composable
fun HariApp(state: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    val perms = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(audioPerm, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(audioPerm)

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[audioPerm] == true) scope.launch { state.loadAll(ctx) }
        else state.permissionDenied = true
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, audioPerm) == PackageManager.PERMISSION_GRANTED) {
            state.loadAll(ctx)
        } else permLauncher.launch(perms)
    }
    LaunchedEffect(Unit) {
        // Look for a new version over Wi-Fi each time the app opens.
        if (!state.safeMode) state.checkForUpdate(ctx, installIfAuto = true)
    }
    BackHandler(enabled = state.canGoBack) { state.back() }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card)) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            when {
                state.permissionDenied -> Message("Hari Player needs permission to read your music. Allow it in Settings › Apps › Hari Player › Permissions.")
                state.loading -> Message("Reading your music library…")
                else -> when (val s = state.screen) {
                    Screen.Home -> HomeScreen(state)
                    Screen.Composers -> ComposersScreen(state)
                    is Screen.Tracks -> TracksScreen(state, s.title, s.list)
                    Screen.Now -> NowScreen(state)
                    Screen.Eq -> EqScreen(state)
                    Screen.PickIem -> PickIemScreen(state)
                    Screen.PickTarget -> PickTargetScreen(state)
                    Screen.Modes -> ModesScreen(state)
                    Screen.Support -> SupportScreen(state)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- shared bits
@Composable
internal fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Ink, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun Header(title: String, onBack: (() -> Unit)?, right: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            RoundButton("‹", 44.dp, Card, Ink, onBack)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        right()
    }
}

@Composable
internal fun RoundButton(label: String, size: Dp, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = fg, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold) }
}

@Composable
internal fun Pill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Accent else Card)
            .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = if (selected) Bg else Ink, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text.uppercase(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
internal fun AlbumArt(track: Track?, size: Dp, corner: Dp) {
    val ctx = LocalContext.current
    var bmp by remember(track?.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(track?.id) {
        bmp = null
        if (track != null) {
            bmp = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.loadThumbnail(track.uri, android.util.Size(700, 700), null).asImageBitmap()
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(Art),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(
            (track?.title ?: "♪").take(1), color = Accent,
            fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold
        )
    }
}

@Composable
internal fun MiniPlayer(state: AppState) {
    if (state.nowTitle.isBlank()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Raised)
            .clickable { state.go(Screen.Now) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArt(state.nowTrack, 48.dp, 10.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(state.nowTitle, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.nowArtist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundButton(if (state.isPlaying) "❚❚" else "▶", 44.dp, Accent, Bg) {
            state.controller?.let { if (it.isPlaying) it.pause() else it.play() }
        }
    }
}

// ---------------------------------------------------------------- Home
@Composable
private fun HomeScreen(state: AppState) {
    val presetName = SoundSettings.preset?.name.takeIf { SoundSettings.eqOn && !SoundSettings.direct }
    val composers = remember(state.tracks) { state.composerGroups() }
    Column(Modifier.fillMaxSize()) {
        Header("Library", null) {
            Pill(if (SoundSettings.direct) "Direct" else presetName ?: "EQ off", false) { state.go(Screen.Eq) }
            Spacer(Modifier.width(8.dp))
            Pill("Modes", false) { state.go(Screen.Modes) }
            Spacer(Modifier.width(8.dp))
            RoundButton("⚙", 44.dp, Card, Ink) { state.go(Screen.Support) }
        }
        Banners(state)
        LazyColumn(Modifier.weight(1f)) {
            item { SectionTitle("Smart mixes") }
            val rows = state.mixes.chunked(3)
            itemsIndexed(rows) { r, row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { i, mix ->
                        val songs = remember(state.tracks, mix) { Library.inMix(state.tracks, mix) }
                        Column(
                            Modifier.weight(1f).heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp)).background(Card)
                                .clickable { state.go(Screen.Tracks(mix.name, songs)) }.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(mixDots[(r * 3 + i) % mixDots.size]))
                            Spacer(Modifier.height(6.dp))
                            Text(mix.name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                            Text("${songs.size}", color = Muted, fontSize = 11.sp)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionTitle("Composers") }
                    Text("See all", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { state.go(Screen.Composers) }.padding(8.dp))
                }
            }
            itemsIndexed(composers.take(8).chunked(2)) { _, pair ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { (name, songs) -> ComposerCard(name, songs.size, Modifier.weight(1f)) { state.go(Screen.Tracks(name, songs)) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item {
                Pill("All songs (${state.tracks.size})", false, Modifier.fillMaxWidth().padding(16.dp)) {
                    state.go(Screen.Tracks("All songs", state.tracks))
                }
            }
        }
        MiniPlayer(state)
    }
}

@Composable
private fun ComposerCard(name: String, count: Int, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(Card).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
            Text(name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""), color = Bg, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(name, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$count songs", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ComposersScreen(state: AppState) {
    val groups = remember(state.tracks) { state.composerGroups() }
    Column(Modifier.fillMaxSize()) {
        Header("Composers", { state.back() })
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(groups) { _, (name, songs) ->
                ComposerCard(name, songs.size, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    state.go(Screen.Tracks(name, songs))
                }
            }
        }
        MiniPlayer(state)
    }
}

@Composable
private fun TracksScreen(state: AppState, title: String, list: List<Track>) {
    Column(Modifier.fillMaxSize()) {
        Header(title, { state.back() })
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Play all", true, Modifier.weight(1f)) { state.play(list, 0) }
            Pill("Shuffle", false, Modifier.weight(1f)) { state.play(list, (list.indices).randomOrNull() ?: 0, shuffled = true) }
        }
        if (list.isEmpty()) {
            Message("No songs matched. Mixes match words in folder names, titles, artists and albums.")
            return@Column
        }
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            itemsIndexed(list) { i, t ->
                Row(
                    Modifier.fillMaxWidth().clickable { state.play(list, i) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = if (state.nowTrack?.id == t.id) Accent else Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(fmt(t.durationMs), color = Muted, fontSize = 12.sp)
                }
            }
        }
        MiniPlayer(state)
    }
}

private fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

// ---------------------------------------------------------------- Now playing
@Composable
private fun NowScreen(state: AppState) {
    val c = state.controller
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(c) {
        while (c != null) {
            pos = c.currentPosition
            dur = c.duration.coerceAtLeast(0L)
            delay(500)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Now playing", { state.back() })
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AlbumArt(state.nowTrack, 352.dp, 24.dp)
            Column {
                Text(state.nowTitle.ifBlank { "Nothing playing" }, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(state.nowArtist, color = Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                state.nowTrack?.album?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, fontSize = 12.sp, maxLines = 1) }
            }
            Slider(
                value = dragging ?: if (dur > 0) pos.toFloat() / dur else 0f,
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { f -> c?.seekTo((f * dur).toLong()) }; dragging = null },
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Line)
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmt(pos), color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(fmt(dur), color = Muted, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                RoundButton("⤨", 44.dp, if (state.shuffle) Raised else Bg, if (state.shuffle) Accent else Muted) { c?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
                RoundButton("⏮", 56.dp, Bg, Ink) { c?.seekToPrevious() }
                RoundButton(if (state.isPlaying) "❚❚" else "▶", 76.dp, Accent, Bg) { c?.let { if (it.isPlaying) it.pause() else it.play() } }
                RoundButton("⏭", 56.dp, Bg, Ink) { c?.seekToNext() }
                val rep = state.repeatMode != Player.REPEAT_MODE_OFF
                RoundButton(if (state.repeatMode == Player.REPEAT_MODE_ONE) "↻1" else "↻", 44.dp, if (rep) Raised else Bg, if (rep) Accent else Muted) {
                    c?.let {
                        it.repeatMode = when (it.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val eqLabel = if (SoundSettings.direct) "Direct" else SoundSettings.preset?.name?.takeIf { SoundSettings.eqOn } ?: "Off"
                InfoCard("EQ", eqLabel, Modifier.weight(1f)) { state.go(Screen.Eq) }
                InfoCard("Sound modes", modesSummary(), Modifier.weight(1f)) { state.go(Screen.Modes) }
            }
            Text("Wheel = volume · side keys = previous / play / next", color = Muted, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp), textAlign = TextAlign.Center)
        }
    }
}

private fun modesSummary(): String {
    val s = SoundSettings
    if (s.direct) return "Direct"
    val parts = mutableListOf<String>()
    if (s.tube > 0) parts += TubeStyles.get(s.tube).short
    if (s.rumble > 0) parts += "Rumble"
    if (s.resonance > 0 || s.turntable > 0) parts += "Vinyl"
    if (s.width != 5) parts += "Stage"
    return parts.joinToString(" · ").ifBlank { "Off" }
}

@Composable
private fun InfoCard(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Card).clickable(onClick = onClick).padding(12.dp)) {
        Text(label.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Text(value, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- EQ
internal fun displayName(ctx: Context, uri: Uri): String? =
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

// ---------------------------------------------------------------- Sound modes
@Composable
private fun ModesScreen(state: AppState) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val tick = state.soundTick // read so the screen redraws on change
    fun apply(block: () -> Unit) {
        block(); SoundSettings.changed(); SoundSettings.save(ctx); state.soundTick = state.soundTick + 1
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Header("Sound modes", { state.back() })
        ModeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Direct", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Bypasses EQ and every mode below", color = Muted, fontSize = 12.sp)
                }
                Switch(SoundSettings.direct, { v -> apply { SoundSettings.direct = v } }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
            }
        }
        SectionTitle("Quick presets")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Hari Bass", false, Modifier.weight(1f)) {
                apply { SoundSettings.direct = false; SoundSettings.tube = 1; SoundSettings.rumble = 7; SoundSettings.resonance = 4; SoundSettings.turntable = 2; SoundSettings.width = 7 }
            }
            Pill("Vinyl Night", false, Modifier.weight(1f)) {
                apply { SoundSettings.direct = false; SoundSettings.tube = 1; SoundSettings.rumble = 2; SoundSettings.resonance = 6; SoundSettings.turntable = 6; SoundSettings.width = 5 }
            }
            Pill("All off", false, Modifier.weight(1f)) {
                apply { SoundSettings.tube = 0; SoundSettings.rumble = 0; SoundSettings.resonance = 0; SoundSettings.turntable = 0; SoundSettings.width = 5 }
            }
        }
        ModeCard {
            Text("Tube sound", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            val names = TubeStyles.all.map { it.label }
            names.chunked(2).forEachIndexed { r, row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { i, n ->
                        val idx = r * 2 + i
                        Pill(n, SoundSettings.tube == idx, Modifier.weight(1f)) { apply { SoundSettings.tube = idx } }
                    }
                }
            }
            Text(TubeStyles.get(SoundSettings.tube).hint, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        ModeSlider("Sub-bass rumble", "Lifts 20–60 Hz only, up to +10 dB", SoundSettings.rumble) { v -> apply { SoundSettings.rumble = v } }
        ModeSlider("Vinyl resonance", "Deeper, resonating bass notes", SoundSettings.resonance) { v -> apply { SoundSettings.resonance = v } }
        ModeSlider("Turntable warmth", "Warm mids, softer sibilance", SoundSettings.turntable) { v -> apply { SoundSettings.turntable = v } }
        ModeSlider("Soundstage", "5 = original · higher = wider stage", SoundSettings.width) { v -> apply { SoundSettings.width = v } }
        ModeCard {
            Text("Gain, output & hiss", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Gain and output are set by iBasso's system controls. For sensitive IEMs like the Legion, " +
                    "use Low gain to keep the background silent.",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp)
            )
            Pill("Open sound settings", false, Modifier.fillMaxWidth()) {
                try { ctx.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) } catch (_: Exception) {}
            }
        }
        ModeCard {
            Text("Coming from Stem Studio (PC)", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "DSD remastering, AI restore (DSEE-style), AI hiss removal and instrument mixing are too heavy to run " +
                    "live on the DX270. They will be prepared on your PC and played here as files.",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
internal fun ModeCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp)
    ) { content() }
}

@Composable
private fun ModeSlider(title: String, hint: String, value: Int, onSet: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    ModeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(v.toInt().toString(), color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Text(hint, color = Muted, fontSize = 12.sp)
        Slider(
            value = v, onValueChange = { v = it }, valueRange = 0f..10f, steps = 9,
            onValueChangeFinished = { onSet(v.toInt()) },
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Line)
        )
    }
}



// ---------------------------------------------------------------- Support: crashes, safe mode, updates
@Composable
private fun Banner(text: String, action: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(color).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = Bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(action, color = Bg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Banners(state: AppState) {
    if (state.safeMode) Banner("Safe mode: effects are off after a crash", "Details", Color(0xFFFF8A65)) { state.go(Screen.Support) }
    if (state.pendingCrash != null) Banner("Hari Player crashed last time", "Send report", Accent) { state.go(Screen.Support) }
    state.update?.let { u -> Banner("Update ready: build ${u.versionCode}", "Install", Color(0xFF4FD1C5)) { state.go(Screen.Support) } }
}

private fun copyText(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Hari Player report", text))
}

private fun openMango(ctx: Context): Boolean {
    val pm = ctx.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val hit = pm.queryIntentActivities(launcher, 0).firstOrNull {
        it.loadLabel(pm).toString().contains("mango", ignoreCase = true) ||
            it.activityInfo.packageName.contains("mango", ignoreCase = true)
    } ?: return false
    val intent = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return false
    ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
}

@Composable
private fun SupportScreen(state: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var autoRoot by remember { mutableStateOf(Updater.autoRootInstall(ctx)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Header("App & support", { state.back() })
        Text(
            "Hari Player ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp)
        )

        ModeCard {
            Text("Updates over Wi-Fi", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(state.updateStatus.ifBlank { "Not checked yet." }, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Check now", false, Modifier.weight(1f)) { scope.launch { state.checkForUpdate(ctx, installIfAuto = false) } }
                if (state.update != null) Pill(if (state.updateBusy) "Working…" else "Install", true, Modifier.weight(1f)) {
                    scope.launch { state.installUpdate(ctx) }
                }
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Install updates automatically", color = Ink, fontSize = 14.sp)
                    Text("Uses root (Magisk) to skip the confirm screen. Magisk asks once.", color = Muted, fontSize = 12.sp)
                }
                Switch(autoRoot, { v ->
                    autoRoot = v
                    Updater.setAutoRootInstall(ctx, v)
                    if (v) scope.launch {
                        val ok = withContext(Dispatchers.IO) { Updater.hasRoot() }
                        if (!ok) { autoRoot = false; Updater.setAutoRootInstall(ctx, false); state.updateStatus = "Root not granted – updates will ask you to confirm." }
                    }
                }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
            }
        }

        ModeCard {
            Text("Problem reports", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            val crash = state.pendingCrash
            if (crash != null) {
                Text("The app crashed last time. Send the report so it can be fixed.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Email crash report", true, Modifier.weight(1f)) {
                        ctx.startActivity(CrashReporter.emailIntent(crash))
                        CrashReporter.markReportHandled(ctx); state.refreshSupport(ctx)
                    }
                    Pill("Copy", false, Modifier.weight(1f)) { copyText(ctx, crash); note = "Copied – paste it into Claude." }
                }
            } else {
                Text("No crash recorded. For anything else odd, send a diagnostic report.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Email diagnostic report", false, Modifier.weight(1f)) {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { CrashReporter.diagnosticReport(ctx) }
                        ctx.startActivity(CrashReporter.emailIntent(r))
                    }
                }
                Pill("Copy", false, Modifier.weight(1f)) {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { CrashReporter.diagnosticReport(ctx) }
                        copyText(ctx, r); note = "Copied – paste it into Claude."
                    }
                }
            }
            if (note.isNotBlank()) Text(note, color = Accent, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }

        ModeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Safe mode", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Starts with every effect off. Turns on by itself after two crashes in a row.", color = Muted, fontSize = 12.sp)
                }
                Switch(state.safeMode, { v ->
                    CrashReporter.setSafeMode(ctx, v)
                    if (v) { SoundSettings.direct = true; SoundSettings.changed(); SoundSettings.save(ctx) }
                    state.refreshSupport(ctx)
                }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
            }
        }

        ModeCard {
            Text("Back to iBasso's player", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Hari Player is an ordinary app. It never changes the DX270's system, firmware or Mango. " +
                    "Uninstalling it leaves the player exactly as it was.",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Open Mango", false, Modifier.weight(1f)) {
                    if (!openMango(ctx)) note = "Couldn't find Mango – open it from the home screen."
                }
                Pill("Uninstall Hari Player", false, Modifier.weight(1f)) {
                    @Suppress("DEPRECATION")
                    ctx.startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }
}
