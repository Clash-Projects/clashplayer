package com.clashplayer

import android.app.Application
import com.clashplayer.diagnostics.DiagnosticsManager

class ClashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticsManager.init(this)
    }
}
