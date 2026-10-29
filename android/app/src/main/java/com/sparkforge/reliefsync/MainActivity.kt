package com.sparkforge.reliefsync

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sparkforge.reliefsync.data.LinkState
import com.sparkforge.reliefsync.ui.AppViewModel
import com.sparkforge.reliefsync.ui.RecState
import com.sparkforge.reliefsync.ui.Tab
import com.sparkforge.reliefsync.ui.screens.MapScreen
import com.sparkforge.reliefsync.ui.screens.OpsScreen
import com.sparkforge.reliefsync.ui.screens.SosScreen
import com.sparkforge.reliefsync.ui.screens.VolunteerScreen
import com.sparkforge.reliefsync.ui.theme.RS
import com.sparkforge.reliefsync.ui.theme.ReliefSyncTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(wanted.toTypedArray())
        handle(intent)
        setContent { ReliefSyncTheme { Root(vm) } }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root(vm: AppViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var settings by remember { mutableStateOf(false) }

    LaunchedEffect(ui.toast) { ui.toast?.let { snack.showSnackbar(it); vm.consumeToast() } }

    Scaffold(
        containerColor = RS.Bg,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = RS.Bg),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                            .background(Brush.sweepGradient(listOf(RS.Red, RS.Orange, RS.Amber, RS.Red))),
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.White))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(buildAnnotatedString {
                                append("Relief"); withStyle(SpanStyle(color = RS.Red)) { append("Sync") }
                            }, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
                            LinkChip(ui.link, ui.online, ui.queued)
                        }
                    }
                },
                actions = { IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Settings", tint = RS.Muted) } },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = RS.Bg2) {
                val c = NavigationBarItemDefaults.colors(selectedIconColor = RS.Text, indicatorColor = RS.Red.copy(alpha = .22f),
                    unselectedIconColor = RS.Dim, selectedTextColor = RS.Text, unselectedTextColor = RS.Dim)
                NavigationBarItem(ui.tab == Tab.SOS, { vm.go(Tab.SOS) }, { Icon(Icons.Default.Mic, null) }, label = { Text("SOS") }, colors = c)
                NavigationBarItem(ui.tab == Tab.OPS, { vm.go(Tab.OPS) }, {
                    val active = ui.incidents.count { it.status !in setOf("RESOLVED", "ESCALATED_MUNICIPAL") }
                    BadgedBox(badge = { if (active > 0) Badge(containerColor = RS.Red) { Text("$active") } }) { Icon(Icons.Default.Hub, null) }
                }, label = { Text("Agents") }, colors = c)
                NavigationBarItem(ui.tab == Tab.MAP, { vm.go(Tab.MAP) }, { Icon(Icons.Default.Map, null) }, label = { Text("Map") }, colors = c)
                NavigationBarItem(ui.tab == Tab.VOLUNTEER, { vm.go(Tab.VOLUNTEER) }, {
                    BadgedBox(badge = { if (ui.offer != null) Badge(containerColor = RS.Amber) { Text("!") } }) { Icon(Icons.Default.VolunteerActivism, null) }
                }, label = { Text("Volunteer") }, colors = c)
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            AnimatedContent(ui.tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { tab ->
                when (tab) {
                    Tab.SOS -> SosScreen(vm, ui)
                    Tab.OPS -> OpsScreen(vm, ui)
                    Tab.MAP -> MapScreen(vm, ui)
                    Tab.VOLUNTEER -> VolunteerScreen(vm, ui)
                }
            }
        }
    }

    if (settings) SettingsDialog(vm) { settings = false }
}

@Composable
private fun LinkChip(link: LinkState, online: Boolean, queued: Int) {
    val (c, t) = when {
        !online -> RS.Red to "offline${if (queued > 0) " · $queued queued" else ""}"
        link == LinkState.LIVE -> RS.Green to "live agent stream${if (queued > 0) " · syncing $queued" else ""}"
        link == LinkState.CONNECTING -> RS.Amber to "connecting…"
        else -> RS.Red to "server unreachable"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(6.dp))
        Text(t, fontSize = 11.sp, color = RS.Muted)
    }
}

@Composable
private fun SettingsDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var server by remember { mutableStateOf(vm.prefs.server) }
    var gateway by remember { mutableStateOf(vm.prefs.gateway) }
    var phone by remember { mutableStateOf(vm.prefs.phone) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RS.Panel,
        title = { Text("Settings") },
        text = {
            Column {
                OutlinedTextField(server, { server = it }, label = { Text("Agent server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(gateway, { gateway = it }, label = { Text("SMS gateway number (fallback)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(phone, { phone = it }, label = { Text("My phone (for status SMS)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(12.dp))
                Row {
                    TextButton(onClick = { vm.runDemo(); onDismiss() }) { Text("Run demo scenario") }
                    TextButton(onClick = { vm.resetDemo(); onDismiss() }) { Text("Reset", color = RS.Muted) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.updateSettings(server, gateway, phone); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = RS.Muted) } },
    )
}
