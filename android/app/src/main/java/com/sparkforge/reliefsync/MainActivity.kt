package com.sparkforge.reliefsync

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.sparkforge.reliefsync.ui.AppViewModel
import com.sparkforge.reliefsync.ui.Home
import com.sparkforge.reliefsync.ui.RecState
import com.sparkforge.reliefsync.ui.Tab
import com.sparkforge.reliefsync.ui.theme.ReliefSyncTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(wanted.toTypedArray())
        handle(intent)
        setContent { ReliefSyncTheme { Home(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        when {
            i?.action == ACTION_RECORD -> { vm.go(Tab.SOS); if (vm.ui.value.rec == RecState.IDLE) vm.toggleRecord() }
            i?.action == "com.sparkforge.reliefsync.OPS" -> vm.go(Tab.OPS)
            i?.getStringExtra("tab") == "volunteer" -> vm.go(Tab.VOLUNTEER)
            i?.getStringExtra("tab") == "ops" -> vm.go(Tab.OPS)
        }
    }

    companion object { const val ACTION_RECORD = "com.sparkforge.reliefsync.RECORD_SOS" }
}

