package com.sparkforge.reliefsync.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sparkforge.reliefsync.data.LinkState
import com.sparkforge.reliefsync.ui.map.MapLayer
import com.sparkforge.reliefsync.ui.sheets.AgentsSheet
import com.sparkforge.reliefsync.ui.sheets.HelpSheet
import com.sparkforge.reliefsync.ui.sheets.RecordingOverlay
import com.sparkforge.reliefsync.ui.sheets.SettingsDialog
import com.sparkforge.reliefsync.ui.sheets.VolunteerSheet
import com.sparkforge.reliefsync.ui.theme.RS
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(vm: AppViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val layer = remember { MapLayer(ctx) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    val sheet = rememberBottomSheetScaffoldState(rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded))

    LaunchedEffect(ui.toast) { ui.toast?.let { snack.showSnackbar(it); vm.consumeToast() } }

    // Map wiring
    val focusInc = ui.incidents.firstOrNull { it.id == ui.selected }
        ?: ui.incidents.firstOrNull { it.id in ui.myIncidents && it.status != "RESOLVED" }
        ?: ui.incidents.firstOrNull { it.status !in setOf("RESOLVED", "ESCALATED_MUNICIPAL") }
    layer.selectedIncident = focusInc?.id
    layer.follow = follow
    layer.onIncidentClick = { id -> vm.select(id); vm.go(Tab.OPS); scope.launch { sheet.bottomSheetState.expand() } }
    LaunchedEffect(ui.hazards, ui.resources, ui.incidents, ui.volunteers, ui.volPositions) {
        layer.sync(ui.hazards, ui.resources, ui.incidents, ui.volunteers, ui.volPositions)
    }
    LaunchedEffect(Unit) { while (true) { withFrameNanos { }; layer.frame() } }
    LaunchedEffect(focusInc?.id, focusInc?.location) {
        focusInc?.location?.let { layer.focus(it.lat, it.lon, 15.6) }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { layer.view.onResume(); onDispose { layer.view.onPause() } }
    // keep the map's focal point in the visible area above the bottom sheet
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(Unit) { with(density) { layer.view.setMapCenterOffset(0, -(150.dp.roundToPx())) } }

    Box(Modifier.fillMaxSize().background(RS.Bg)) {
        BottomSheetScaffold(
            scaffoldState = sheet,
            sheetPeekHeight = 330.dp,
            sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            sheetContainerColor = RS.Surface,
            sheetShadowElevation = 16.dp,
            sheetDragHandle = { BottomSheetDefaults.DragHandle(color = RS.Line2) },
            snackbarHost = { SnackbarHost(snack) },
            sheetContent = {
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    ModeSwitch(ui.tab) { vm.go(it) }
                    AnimatedContent(ui.tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "sheet") { tab ->
                        when (tab) {
                            Tab.SOS, Tab.MAP -> HelpSheet(vm, ui)
                            Tab.OPS -> AgentsSheet(vm, ui)
                            Tab.VOLUNTEER -> VolunteerSheet(vm, ui) { follow = true }
                        }
                    }
                }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { layer.view }, modifier = Modifier.fillMaxSize())
                // top fade for legibility of the floating bar
                Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent))))
                TopBar(ui, onSettings = { settings = true }, onDemo = { vm.runDemo() }, modifier = Modifier.statusBarsPadding())
                Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 70.dp, end = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundFab(Icons.Default.GpsFixed, if (follow) RS.Blue else RS.Ink, "Follow vehicle") {
                        follow = !follow
                        if (follow) focusInc?.location?.let { layer.focus(it.lat, it.lon, 15.6) }
                    }
                }
                Legend(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 70.dp, start = 14.dp))
            }
        }
        AnimatedVisibility(ui.rec == RecState.RECORDING || ui.rec == RecState.SENDING,
            enter = fadeIn() + slideInVertically { it / 6 }, exit = fadeOut() + slideOutVertically { it / 6 }) {
            RecordingOverlay(vm, ui)
        }
    }
    if (settings) SettingsDialog(vm) { settings = false }
}

@Composable
private fun TopBar(ui: UiState, onSettings: () -> Unit, onDemo: () -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.shadow(10.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(RS.Surface)
                .padding(start = 8.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(RS.Red, RS.Orange))),
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(11.dp).clip(CircleShape).background(Color.White))
            }
            Spacer(Modifier.width(9.dp))
            Column {
                Text(buildAnnotatedString { append("Relief"); withStyle(SpanStyle(color = RS.Red)) { append("Sync") } },
                    fontWeight = FontWeight.Black, fontSize = 16.sp, color = RS.Ink, lineHeight = 17.sp)
                val (c, t) = when {
                    !ui.online -> RS.Red to "Offline${if (ui.queued > 0) " · ${ui.queued} queued" else ""}"
                    ui.link == LinkState.LIVE -> RS.Green to "Live · ${ui.volunteers.count { it.assignedIncident == null }} responders ready"
                    ui.link == LinkState.CONNECTING -> RS.Amber to "Connecting…"
                    else -> RS.Red to "Server unreachable"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(5.dp))
                    Text(t, fontSize = 11.sp, color = RS.Muted, lineHeight = 12.sp)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        RoundFab(Icons.Default.PlayArrow, RS.Red, "Run demo", onDemo)
        Spacer(Modifier.width(8.dp))
        RoundFab(Icons.Default.Settings, RS.Ink, "Settings", onSettings)
    }
}

@Composable
fun RoundFab(icon: ImageVector, tint: Color, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(RS.Surface).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(21.dp)) }
}

@Composable
private fun Legend(modifier: Modifier) {
    Column(
        modifier.shadow(6.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(RS.Surface.copy(alpha = .95f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(RS.Red to "Incident", RS.Blue to "Responder", RS.Sky to "Supplies", RS.Red.copy(alpha = .35f) to "Flood zone").forEach { (c, t) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(6.dp))
                Text(t, fontSize = 10.5.sp, color = RS.Muted, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ModeSwitch(tab: Tab, onPick: (Tab) -> Unit) {
    val items = listOf(Triple(Tab.SOS, "Get help", Icons.Outlined.LocalHospital), Triple(Tab.OPS, "Agents", Icons.Outlined.Hub),
        Triple(Tab.VOLUNTEER, "Respond", Icons.Outlined.VolunteerActivism))
    Row(
        Modifier.padding(horizontal = 18.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RS.Surface2).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { (t, label, ic) ->
            val sel = tab == t || (t == Tab.SOS && tab == Tab.MAP)
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).then(if (sel) Modifier.shadow(2.dp, RoundedCornerShape(12.dp)) else Modifier)
                    .background(if (sel) RS.Surface else Color.Transparent).clickable { onPick(t) }.padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(ic, null, tint = if (sel) (if (t == Tab.SOS) RS.Red else RS.Blue) else RS.Muted, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, color = if (sel) RS.Ink else RS.Muted)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

/** Small reusable bits */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = .12f)).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = RS.Dim, modifier = modifier)

@Composable
fun Card(modifier: Modifier = Modifier, border: Color = RS.Line, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(RS.Surface).border(1.dp, border, RoundedCornerShape(20.dp)).padding(16.dp)) { content() }
}
