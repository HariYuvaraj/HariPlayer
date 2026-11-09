package com.hari.dxplayer.support

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import com.hari.dxplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Over-the-air updates. New versions are built automatically on GitHub
 * (see .github/workflows/build.yml) and published as a release. The app checks
 * the latest release over Wi-Fi, downloads the APK and installs it.
 */
object Updater {
    data class Update(val versionCode: Int, val title: String, val notes: String, val apkUrl: String)

    private const val PREFS = "updater"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val repo: String get() = BuildConfig.UPDATE_REPO

    /** With root (Magisk), install without the confirmation screen. */
    fun autoRootInstall(ctx: Context) = prefs(ctx).getBoolean("autoRoot", false)
    fun setAutoRootInstall(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("autoRoot", on).apply()

    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        if (repo.isBlank()) return@withContext null
        val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "HariPlayer")
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode != 200) return@withContext null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val code = Regex("""\d+""").findAll(json.optString("tag_name")).lastOrNull()?.value?.toIntOrNull()
                ?: return@withContext null
            if (code <= BuildConfig.VERSION_CODE) return@withContext null
            val assets = json.optJSONArray("assets") ?: return@withContext null
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url")
            }
            apk?.let { Update(code, json.optString("name", "Build $code"), json.optString("body"), it) }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun download(ctx: Context, u: Update): File = withContext(Dispatchers.IO) {
        val out = File(ctx.cacheDir, "update.apk")
        var url = URL(u.apkUrl)
        var conn: HttpURLConnection
        var hops = 0
        while (true) { // follow GitHub's redirect to its download server
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "HariPlayer")
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            val code = conn.responseCode
            if (code in 300..399 && hops < 5) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect()
                hops++
                continue
            }
            if (code != 200) throw IllegalStateException("Download failed (HTTP $code)")
            break
        }
        conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
        out
    }

    fun hasRoot(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (e: Exception) {
        false
    }

    /** Silent install with root, then reopen the app. Returns false if root is not available. */
    suspend fun installWithRoot(apk: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val tmp = "/data/local/tmp/hariplayer_update.apk"
            val cmd = "cp '${apk.absolutePath}' $tmp && pm install -r $tmp; r=\$?; rm -f $tmp; " +
                "[ \$r -eq 0 ] && am start -n ${BuildConfig.APPLICATION_ID}/.MainActivity; exit \$r"
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /** Standard install: Android shows its "Update this app?" screen; tap Update. */
    fun installWithPrompt(ctx: Context, apk: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("update", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val pending = PendingIntent.getBroadcast(
                ctx, sessionId, Intent(ctx, InstallReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(pending.intentSender)
        }
        return true
    }
}
