package com.hari.dxplayer

import android.app.Application
import com.hari.dxplayer.support.CrashReporter

class HariApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
