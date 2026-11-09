package com.hari.dxplayer.support

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.hari.dxplayer.BuildConfig
import com.hari.dxplayer.dsp.SoundSettings
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Catches crashes, saves a report (stack trace + recent app log + settings),
 * and turns on Safe mode if the app crashes twice in a row while starting.
 */
object CrashReporter {
    /** Where "Send report" addresses the email. Change if you like. */
    const val REPORT_EMAIL = "dr.yuvaraj.2007@gmail.com"
    const val SUBJECT_TAG = "[HariPlayer report]"

    private const val PREFS = "support"
    private const val STARTUP_WINDOW_MS = 15_000L

    private fun dir(ctx: Context) = File(ctx.filesDir, "crash").apply { mkdirs() }
    private fun lastFile(ctx: Context) = File(dir(ctx), "last.txt")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sinceStart = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
                val p = prefs(app)
                val startupCrashes = if (sinceStart < STARTUP_WINDOW_MS) p.getInt("startupCrashes", 0) + 1 else 0
                // commit() so it is saved before the process dies
                p.edit().putInt("startupCrashes", startupCrashes).putBoolean("pending", true).commit()
                lastFile(app).writeText(buildReport(app, "CRASH on thread ${thread.name}", error))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Call once the app has run for a while without crashing. */
    fun appStartedOk(ctx: Context) {
        prefs(ctx).edit().putInt("startupCrashes", 0).apply()
    }

    fun safeModeActive(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.getBoolean("safeManual", false) || p.getInt("startupCrashes", 0) >= 2
    }

    fun setSafeMode(ctx: Context, on: Boolean) {
        val editor = prefs(ctx).edit().putBoolean("safeManual", on)
        if (!on) editor.putInt("startupCrashes", 0)
        editor.apply()
    }

    fun pendingReport(ctx: Context): String? =
        if (prefs(ctx).getBoolean("pending", false) && lastFile(ctx).exists()) lastFile(ctx).readText() else null

    fun markReportHandled(ctx: Context) {
        prefs(ctx).edit().putBoolean("pending", false).apply()
    }

    /** A report on demand, for problems that are not crashes. */
    fun diagnosticReport(ctx: Context, note: String = "Diagnostic report (no crash)"): String =
        buildReport(ctx, note, null)

    fun buildReport(ctx: Context, title: String, error: Throwable?): String {
        val sb = StringBuilder()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        sb.appendLine("$SUBJECT_TAG $title")
        sb.appendLine("Time: $time")
        sb.appendLine("App: Hari Player ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Firmware: ${Build.DISPLAY}")
        sb.appendLine("Safe mode: ${safeModeActive(ctx)}")
        val s = SoundSettings
        sb.appendLine(
            "Sound: direct=${s.direct} eqOn=${s.eqOn} preset=${s.preset?.name} rumble=${s.rumble} " +
                "resonance=${s.resonance} turntable=${s.turntable} width=${s.width} tube=${s.tube}"
        )
        if (error != null) {
            sb.appendLine("\n--- Stack trace ---")
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            sb.appendLine(sw.toString())
        }
        sb.appendLine("\n--- Recent app log ---")
        sb.appendLine(readOwnLog())
        return sb.toString()
    }

    /** An app may read its own log lines without any permission. */
    private fun readOwnLog(): String = try {
        val proc = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-t", "400", "--pid", Process.myPid().toString())
        )
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor(3, TimeUnit.SECONDS)
        text.ifBlank { "(no log lines)" }
    } catch (e: Exception) {
        "(could not read log: ${e.message})"
    }

    fun emailIntent(report: String): Intent {
        val firstLine = report.lineSequence().firstOrNull() ?: SUBJECT_TAG
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, firstLine.take(120))
            putExtra(Intent.EXTRA_TEXT, report)
        }
        return Intent.createChooser(send, "Send report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
