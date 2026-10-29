package com.sparkforge.reliefsync

import android.app.Application
import com.sparkforge.reliefsync.notify.Notifier
import org.osmdroid.config.Configuration

class ReliefSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        Configuration.getInstance().apply {
            userAgentValue = "ReliefSync/1.0 (${packageName})"
            osmdroidBasePath = cacheDir
            osmdroidTileCache = cacheDir.resolve("tiles")   // offline tile cache for degraded networks
        }
    }
}
