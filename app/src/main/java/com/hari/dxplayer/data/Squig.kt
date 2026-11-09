package com.hari.dxplayer.data

import android.content.Context
import com.hari.dxplayer.dsp.AutoEq
import com.hari.dxplayer.dsp.FrCurve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * IEM measurements and targets from the squig.link network.
 *
 * Every squig.link site keeps its list in data/phone_book.json, its measurements as
 * data/<file> L.txt / R.txt and its targets as data/<name> Target.txt.
 * Sites that have opted out of being aggregated (listed by squig.link itself,
 * e.g. Crinacle / Hangout.Audio) are left out.
 */
object Squig {
    private const val UA = "HariPlayer/0.3 (Android music player; personal EQ)"
    private const val ROOT = "https://squig.link/"
    private val fallbackOptedOut = listOf(
        "64audio", "bloomaudio", "cammyfi", "crinacle", "eliseaudio", "hbb", "joycesreview", "kr0mka", "graph", "vsg"
    )

    /** One measurement database. [rig] is "711" or "5128". */
    data class Db(val name: String, val base: String, val rig: String)

    data class IemChoice(val label: String, val file: String, val db: Db) {
        val sub: String get() = "${db.name} · ${db.rig}"
        val search: String by lazy { "$label ${db.name}".lowercase() }
    }

    /** A target name with the sites that offer it, or one of the app's own targets ([asset]). */
    data class TargetChoice(val name: String, val dbs: List<Db>, val asset: String? = null) {
        val sub: String
            get() = asset?.let { "Your target" } ?: if (dbs.size == 1) dbs[0].name else "on ${dbs.size} sites"
        val search: String by lazy { name.lowercase() }
    }

    class Index(val built: Long, val iems: List<IemChoice>, val targets: List<TargetChoice>)

    private val superReview = Db("Super* Review", ROOT, "711")
    private val audioAmigo = Db("Audio Amigo", "https://audioamigo.squig.link/", "711")

    /** Hari's own IEMs, always shown first. */
    val myIems = listOf(
        IemChoice("AFUL Performer 5+2", "Aful Performer5+2", superReview),
        IemChoice("FATfreq Deuce (no adaptor)", "Fatfreq Deuce", superReview),
        IemChoice("Jomo Nautilus", "Jomo Nautilus", audioAmigo),
        IemChoice("CrinEar Daybreak", "CrinEar Daybreak", superReview),
        IemChoice("Flipears Legion", "Flipears Legion", audioAmigo),
    )

    /** Hari's own targets, bundled with the app (assets/targets). */
    val myTargets = listOf(
        TargetChoice("Hari Favourite iem V5.2", emptyList(), "Hari Favourite iem V5.2.txt"),
        TargetChoice("Hari Favourite iem V5.1", emptyList(), "Hari Favourite iem V5.1.txt"),
    )

    // ------------------------------------------------------------------ network
    private fun get(url: String, timeoutMs: Int = 15_000): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        try {
            if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ------------------------------------------------------------------ index
    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "squig_index.json")

    fun loadCached(ctx: Context): Index? = try {
        cacheFile(ctx).takeIf { it.exists() }?.let { fromJson(JSONObject(it.readText())) }
    } catch (e: Exception) {
        null
    }

    /** Reads every site's list. Takes a minute or so on Wi-Fi; the result is cached. */
    suspend fun build(ctx: Context, progress: (done: Int, total: Int) -> Unit): Index = withContext(Dispatchers.IO) {
        val sites = JSONArray(get(ROOT + "squigsites.json") ?: throw IllegalStateException("Couldn't reach squig.link"))
        val optedOut = get(ROOT + "squigsites.js")?.let { parseOptedOut(it) }?.takeIf { it.isNotEmpty() } ?: fallbackOptedOut

        val dbs = ArrayList<Db>()
        for (i in 0 until sites.length()) {
            val s = sites.getJSONObject(i)
            val user = s.optString("username")
            if (user in optedOut) continue
            val base = when (s.optString("urlType")) {
                "root" -> ROOT.trimEnd('/')
                "altDomain" -> s.optString("altDomain").trimEnd('/')
                "subdomain" -> "https://$user.squig.link"
                else -> "https://squig.link/lab/$user"
            }
            if (base.contains("hangout.audio")) continue
            val list = s.optJSONArray("dbs") ?: continue
            for (j in 0 until list.length()) {
                val d = list.getJSONObject(j)
                val rig = when (d.optString("type")) { "IEMs" -> "711"; "5128" -> "5128"; else -> null } ?: continue
                val folder = d.optString("folder", "/").let { if (it.endsWith("/")) it else "$it/" }
                dbs += Db(s.optString("name", user), base + folder, rig)
            }
        }

        val done = AtomicInteger(0)
        val gate = Semaphore(6)
        val perDb = coroutineScope {
            dbs.map { db ->
                async {
                    gate.withPermit {
                        val iems = get(db.base + "data/phone_book.json")?.let { parsePhoneBook(it, db) } ?: emptyList()
                        val targets = get(db.base + "config.js")?.let { parseTargets(it) } ?: emptyList()
                        progress(done.incrementAndGet(), dbs.size)
                        Triple(db, iems, targets)
                    }
                }
            }.awaitAll()
        }

        val iems = perDb.flatMap { it.second }.sortedBy { it.label.lowercase() }
        val byName = LinkedHashMap<String, MutableList<Db>>()
        for ((db, _, names) in perDb) for (n in names) byName.getOrPut(n) { ArrayList() }.add(db)
        val targets = byName.map { (n, d) -> TargetChoice(n, d) }.sortedBy { it.name.lowercase() }
        if (iems.isEmpty()) throw IllegalStateException("No IEM lists could be read")

        val index = Index(System.currentTimeMillis(), iems, targets)
        try { cacheFile(ctx).writeText(toJson(index).toString()) } catch (_: Exception) {}
        index
    }

    private fun parseOptedOut(js: String): List<String> {
        val m = Regex("""optedOut\s*=\s*\[([^\]]*)\]""").find(js) ?: return emptyList()
        return Regex("""['"]([^'"]+)['"]""").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
    }

    private fun parsePhoneBook(text: String, db: Db): List<IemChoice> = try {
        val out = ArrayList<IemChoice>()
        val brands = JSONArray(text)
        for (i in 0 until brands.length()) {
            val b = brands.optJSONObject(i) ?: continue
            val brand = b.optString("name")
            if (brand.startsWith("_")) continue
            val phones = b.optJSONArray("phones") ?: continue
            for (j in 0 until phones.length()) {
                val p = phones.optJSONObject(j) ?: continue
                val model = p.optString("name")
                when (val f = p.opt("file")) {
                    is String -> out += IemChoice("$brand $model", f, db)
                    is JSONArray -> {
                        val suffix = p.optJSONArray("suffix")
                        for (k in 0 until f.length()) {
                            val file = f.optString(k)
                            val extra = suffix?.optString(k)?.takeIf { it.isNotBlank() }
                            val label = when {
                                f.length() == 1 -> "$brand $model"
                                extra != null -> "$brand $model $extra"
                                else -> "$brand $model ($file)"
                            }
                            out += IemChoice(label, file, db)
                        }
                    }
                }
            }
        }
        out
    } catch (e: Exception) {
        emptyList()
    }

    /** Pulls the target names out of a site's config.js (the `const targets = [...]` block). */
    private fun parseTargets(cfg: String): List<String> {
        val k = Regex("""const\s+targets\s*=""").find(cfg)?.range?.first ?: return emptyList()
        val start = cfg.indexOf('[', k)
        if (start < 0) return emptyList()
        var depth = 0; var end = -1
        for (i in start until cfg.length) {
            if (cfg[i] == '[') depth++
            else if (cfg[i] == ']') { depth--; if (depth == 0) { end = i + 1; break } }
        }
        if (end < 0) return emptyList()
        val block = cfg.substring(start, end)
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines().joinToString("\n") { it.replace(Regex("""(^|[^:])//.*$"""), "$1") }
        val out = ArrayList<String>()
        for (m in Regex("""files\s*:\s*\[([^\]]*)\]""").findAll(block))
            for (q in Regex("""["']([^"']+)["']""").findAll(m.groupValues[1])) out += q.groupValues[1].trim()
        return out.filter { it.isNotBlank() && !it.contains("Coming Soon", ignoreCase = true) }
    }

    private fun toJson(ix: Index): JSONObject {
        val dbs = (ix.iems.map { it.db } + ix.targets.flatMap { it.dbs }).distinct()
        val id = dbs.withIndex().associate { it.value to it.index }
        return JSONObject()
            .put("built", ix.built)
            .put("dbs", JSONArray(dbs.map { JSONArray(listOf(it.name, it.base, it.rig)) }))
            .put("iems", JSONArray(ix.iems.map { JSONArray(listOf(it.label, it.file, id[it.db])) }))
            .put("targets", JSONArray(ix.targets.map { JSONArray(listOf(it.name, JSONArray(it.dbs.map { d -> id[d] }))) }))
    }

    private fun fromJson(o: JSONObject): Index {
        val d = o.getJSONArray("dbs")
        val dbs = (0 until d.length()).map { d.getJSONArray(it).let { a -> Db(a.getString(0), a.getString(1), a.getString(2)) } }
        val i = o.getJSONArray("iems")
        val iems = (0 until i.length()).map { i.getJSONArray(it).let { a -> IemChoice(a.getString(0), a.getString(1), dbs[a.getInt(2)]) } }
        val t = o.getJSONArray("targets")
        val targets = (0 until t.length()).map {
            t.getJSONArray(it).let { a ->
                val ids = a.getJSONArray(1)
                TargetChoice(a.getString(0), (0 until ids.length()).map { k -> dbs[ids.getInt(k)] })
            }
        }
        return Index(o.getLong("built"), iems, targets)
    }

    // ------------------------------------------------------------------ curves
    /** The IEM's response (left and right averaged) on [AutoEq.grid]. */
    suspend fun iemCurve(c: IemChoice): DoubleArray? = withContext(Dispatchers.IO) {
        val url = { suffix: String -> c.db.base + "data/" + enc(c.file + suffix) + ".txt" }
        for (names in listOf(listOf(" L", " R"), listOf(" L1", " R1"), listOf(""))) {
            val curves = names.mapNotNull { n -> get(url(n))?.let { FrCurve.parse(it) } }
            if (curves.isNotEmpty()) return@withContext FrCurve.average(curves, AutoEq.grid)
        }
        null
    }

    /**
     * The target on [AutoEq.grid], preferring the IEM's own site (same rig and calibration),
     * then another site with the same rig. Returns the curve and where it came from.
     */
    suspend fun targetCurve(ctx: Context, t: TargetChoice, iem: IemChoice): Pair<DoubleArray, Db?>? = withContext(Dispatchers.IO) {
        if (t.asset != null) {
            val text = ctx.assets.open("targets/${t.asset}").bufferedReader().readText()
            return@withContext FrCurve.parse(text)?.let { it.on(AutoEq.grid) to null }
        }
        val ordered = t.dbs.sortedBy {
            when {
                it.base == iem.db.base -> 0
                it.rig == iem.db.rig && it.base == ROOT -> 1
                it.rig == iem.db.rig -> 2
                else -> 3
            }
        }
        for (db in ordered.take(5)) {
            val text = get(db.base + "data/" + enc(t.name + " Target") + ".txt") ?: continue
            val curve = FrCurve.parse(text) ?: continue
            return@withContext curve.on(AutoEq.grid) to db
        }
        null
    }

    // ------------------------------------------------------------------ remembering picks
    private const val SEP = "\u001F"

    fun save(ctx: Context, iem: IemChoice?, target: TargetChoice?) {
        ctx.getSharedPreferences("autoeq", Context.MODE_PRIVATE).edit()
            .putString("iem", iem?.let { listOf(it.label, it.file, it.db.name, it.db.base, it.db.rig).joinToString(SEP) })
            .putString("target", target?.let {
                listOf(it.name, it.asset ?: "") .plus(it.dbs.flatMap { d -> listOf(d.name, d.base, d.rig) }).joinToString(SEP)
            })
            .apply()
    }

    fun restore(ctx: Context): Pair<IemChoice?, TargetChoice?> {
        val p = ctx.getSharedPreferences("autoeq", Context.MODE_PRIVATE)
        val iem = p.getString("iem", null)?.split(SEP)?.takeIf { it.size == 5 }?.let {
            IemChoice(it[0], it[1], Db(it[2], it[3], it[4]))
        }
        val target = p.getString("target", null)?.split(SEP)?.takeIf { it.size >= 2 }?.let { a ->
            val dbs = a.drop(2).chunked(3).filter { it.size == 3 }.map { Db(it[0], it[1], it[2]) }
            TargetChoice(a[0], dbs, a[1].ifBlank { null })
        }
        return iem to target
    }
}
