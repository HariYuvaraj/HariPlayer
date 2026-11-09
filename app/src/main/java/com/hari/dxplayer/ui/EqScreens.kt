package com.hari.dxplayer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hari.dxplayer.data.Squig
import com.hari.dxplayer.dsp.AutoEq
import com.hari.dxplayer.dsp.PresetStore
import com.hari.dxplayer.dsp.SoundSettings
import kotlinx.coroutines.launch
import kotlin.math.ln

private val TargetColor = Color(0xFF8AB4FF)
private val ResultColor = Color(0xFF4FD1C5)

// ---------------------------------------------------------------- EQ screen
@Composable
fun EqScreen(state: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    @Suppress("UNUSED_VARIABLE") val tick = state.soundTick // read so the screen redraws on change
    LaunchedEffect(Unit) {
        state.restoreAutoEq(ctx)
        state.loadSquig(ctx)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            PresetStore.import(ctx, uri, displayName(ctx, uri))
            state.presets = PresetStore.all(ctx)
        }
    }
    fun apply(block: () -> Unit) {
        block(); SoundSettings.changed(); SoundSettings.save(ctx); state.soundTick = state.soundTick + 1
    }

    Column(Modifier.fillMaxSize()) {
        Header("EQ", { state.back() }) {
            Switch(
                checked = SoundSettings.eqOn, onCheckedChange = { v -> apply { SoundSettings.eqOn = v } },
                colors = SwitchDefaults.colors(checkedTrackColor = Accent)
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            item {
                SectionTitle("1 · My IEM")
                PickerField(
                    state.pickedIem?.label ?: "Choose your IEM",
                    state.pickedIem?.sub ?: ""
                ) { state.go(Screen.PickIem) }
                SectionTitle("2 · Target sound")
                PickerField(
                    state.pickedTarget?.name ?: "Choose a target",
                    state.pickedTarget?.sub ?: ""
                ) { state.go(Screen.PickTarget) }
                Pill(
                    if (state.autoEqBusy) "Working…" else "Make EQ", true,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    if (state.pickedIem != null && state.pickedTarget != null) scope.launch { state.makeEq(ctx) }
                }
                if (state.autoEqStatus.isNotBlank()) {
                    Text(state.autoEqStatus, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            state.autoEq?.let { o -> item { ResultCard(state, o) } }

            item { SectionTitle("Saved presets · one tap") }
            itemsIndexed(state.presets) { _, p ->
                val sel = SoundSettings.preset?.name == p.name
                Pill(p.name, sel, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    apply { SoundSettings.preset = if (sel) null else p; SoundSettings.eqOn = true }
                }
            }
            item {
                Pill("+ Import preset (.txt)", false, Modifier.fillMaxWidth().padding(16.dp)) {
                    importer.launch(arrayOf("text/plain", "*/*"))
                }
                Text(
                    "IEM measurements and targets come from the squig.link network of reviewers. " +
                        "Sites that have asked not to be aggregated, such as Crinacle's, aren't included.",
                    color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Spacer(Modifier.height(16.dp))
            }
        }
        MiniPlayer(state)
    }
}

@Composable
private fun PickerField(title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp)).background(Card)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("▾", color = Accent, fontSize = 20.sp)
    }
}

@Composable
private fun ResultCard(state: AppState, o: AppState.AutoEqOutcome) {
    val ctx = LocalContext.current
    val r = o.result
    ModeCard {
        Text("${o.iem.label} → ${o.target.name}", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Distance from target: %.1f dB → %.1f dB".format(r.errorBefore, r.errorAfter),
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp)
        )
        EqGraph(r)
        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend(Muted, "IEM"); Legend(TargetColor, "Target"); Legend(ResultColor, "With EQ")
        }
        o.note?.let { Text(it, color = Accent, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) }
        Text("Preamp ${r.preset.preampDb} dB", color = Muted, fontSize = 12.sp)
        r.preset.filters.forEach { f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(when (f.type) { "LSC" -> "Low shelf"; "HSC" -> "High shelf"; else -> "Peak" }, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("${f.fc.toInt()} Hz", color = Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("%+.1f dB".format(f.gainDb), color = Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("Q %.2f".format(f.q), color = Muted, fontSize = 12.sp)
            }
        }
        Pill("Use this EQ", true, Modifier.fillMaxWidth().padding(top = 8.dp)) { state.useEq(ctx) }
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(c))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

/** IEM, target and IEM-with-EQ, 20 Hz to 20 kHz, -15 to +25 dB around the target. */
@Composable
private fun EqGraph(r: AutoEq.Result) {
    val g = r.grid
    val result = DoubleArray(g.size) { r.iem[it] + r.eq[it] }
    val all = r.target + r.iem + result
    val top = (all.maxOrNull() ?: 10.0) + 2
    val bottom = (all.minOrNull() ?: -10.0) - 2
    Canvas(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(10.dp)).background(Raised)) {
        val lx0 = ln(20.0); val lx1 = ln(20000.0)
        fun x(f: Double) = ((ln(f) - lx0) / (lx1 - lx0) * size.width).toFloat()
        fun y(d: Double) = ((top - d) / (top - bottom) * size.height).toFloat()
        for (f in doubleArrayOf(100.0, 1000.0, 10000.0)) drawLine(Line, Offset(x(f), 0f), Offset(x(f), size.height), 1f)
        fun curve(v: DoubleArray, c: Color, dashed: Boolean = false) {
            val p = Path()
            for (i in g.indices) if (i == 0) p.moveTo(x(g[i]), y(v[i])) else p.lineTo(x(g[i]), y(v[i]))
            drawPath(p, c, style = Stroke(width = 3f, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
        }
        curve(r.iem, Muted)
        curve(r.target, TargetColor, dashed = true)
        curve(result, ResultColor)
    }
}

// ---------------------------------------------------------------- pickers
@Composable
private fun SearchBox(query: String, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query, onValueChange = onChange, singleLine = true,
        placeholder = { Text(hint, color = Muted) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Ink, unfocusedTextColor = Ink, cursorColor = Accent,
            focusedBorderColor = Accent, unfocusedBorderColor = Line
        )
    )
}

@Composable
private fun ChoiceRow(title: String, sub: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .background(if (selected) Raised else Bg).padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(title, color = if (selected) Accent else if (enabled) Ink else Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub.isNotBlank()) Text(sub, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun matches(text: String, query: String): Boolean =
    query.isBlank() || query.lowercase().split(' ').filter { it.isNotBlank() }.all { text.contains(it) }

private const val SHOW_MAX = 300

@Composable
private fun ListStatus(state: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(state.squigStatus, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (!state.squigBusy) Text(
            "Refresh", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { scope.launch { state.loadSquig(ctx, force = true) } }.padding(8.dp)
        )
    }
}

@Composable
fun PickIemScreen(state: AppState) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val all = state.squig?.iems ?: emptyList()
    val found = remember(query, all) { all.filter { matches(it.search, query) } }
    val mine = remember(state.squig) { Squig.myIemChoices(ctx, state.squig) }.filter { matches(it.search, query) }
    fun choose(c: Squig.IemChoice) { state.pick(ctx, iem = c); state.back() }

    Column(Modifier.fillMaxSize()) {
        Header("My IEM", { state.back() })
        SearchBox(query, "Search brand or model") { query = it }
        ListStatus(state)
        LazyColumn(Modifier.weight(1f)) {
            if (mine.isNotEmpty()) {
                item { SectionTitle("My IEMs") }
                items(mine) { c -> ChoiceRow(c.label, c.sub, state.pickedIem == c) { choose(c) } }
            }
            item { SectionTitle("All IEMs on squig.link (${found.size})") }
            items(found.take(SHOW_MAX)) { c -> ChoiceRow(c.label, c.sub, state.pickedIem == c) { choose(c) } }
            if (found.size > SHOW_MAX) item {
                Text("Showing $SHOW_MAX of ${found.size}. Type more to narrow it down.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
fun PickTargetScreen(state: AppState) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val choices = remember { Squig.targetChoices(ctx) }
    val counts = remember(state.squig) {
        choices.associate { c -> c.name to (c.ref?.let { r -> Squig.measurementsOf(r, state.squig).size } ?: -1) }
    }
    val shown = choices.filter { matches(it.search, query) }
    fun choose(c: Squig.TargetChoice) { state.pick(ctx, target = c); state.back() }

    Column(Modifier.fillMaxSize()) {
        Header("Target sound", { state.back() })
        SearchBox(query, "Search, e.g. Grand Maestro, basshead, endgame") { query = it }
        ListStatus(state)
        LazyColumn(Modifier.weight(1f)) {
            shown.groupBy { it.ref?.group ?: "My targets" }.forEach { (group, list) ->
                item { SectionTitle(group) }
                items(list) { c ->
                    val n = counts[c.name] ?: -1
                    val sub = when {
                        c.ref == null -> c.sub
                        state.squig == null -> "Loading the squig.link list\u2026"
                        n == 0 -> "Not measured on squig.link"
                        else -> "Sound like this IEM \u00b7 $n measurement" + if (n == 1) "" else "s"
                    }
                    val usable = c.ref == null || state.squig == null || n > 0
                    val selected = state.pickedTarget?.name == c.name && state.pickedTarget?.ref?.group == c.ref?.group
                    ChoiceRow(c.name, sub, selected, enabled = usable) { choose(c) }
                }
            }
        }
    }
}
