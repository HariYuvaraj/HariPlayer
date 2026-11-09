package com.hari.dxplayer

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.hari.dxplayer.dsp.SoundSettings
import com.hari.dxplayer.support.CrashReporter
import com.hari.dxplayer.ui.AppState
import com.hari.dxplayer.ui.HariApp

class MainActivity : ComponentActivity() {
    private val state = AppState()
    private var future: ListenableFuture<MediaController>? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            state.updateFrom(player)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SoundSettings.load(this)
        if (CrashReporter.safeModeActive(this)) {
            // Safe mode: start with every effect off so a bad setting can't crash it again.
            SoundSettings.direct = true
            SoundSettings.changed()
        }
        state.refreshSupport(this)
        // If we are still running after 20 seconds, startup was fine.
        Handler(Looper.getMainLooper()).postDelayed({ CrashReporter.appStartedOk(this) }, 20_000)
        setContent { HariApp(state) }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val c = f.get()
            c.addListener(listener)
            state.controller = c
            state.updateFrom(c)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        state.controller?.removeListener(listener)
        state.controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }
}
