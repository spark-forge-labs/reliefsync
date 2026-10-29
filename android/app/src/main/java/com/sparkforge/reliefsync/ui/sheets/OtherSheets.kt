package com.sparkforge.reliefsync.ui.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sparkforge.reliefsync.data.Offer
import com.sparkforge.reliefsync.data.TraceEvent
import com.sparkforge.reliefsync.ui.AppViewModel
import com.sparkforge.reliefsync.ui.Pill
import com.sparkforge.reliefsync.ui.SectionLabel
import com.sparkforge.reliefsync.ui.UiState
import com.sparkforge.reliefsync.ui.theme.Mono
import com.sparkforge.reliefsync.ui.theme.RS
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PIPE = listOf(
    "AudioTranscribed" to "Transcribe", "EntityExtracted" to "Triage", "GeoResolved" to "Locate",
    "SupplyLocated" to "Supplies", "VolunteersRanked" to "Rank", "RouteEvaluated" to "Route",
    "DispatchSent" to "Dispatch", "Accepted" to "Accepted", "EnRoute" to "En route", "PickedUp" to "Pickup", "Arrived" to "Arrived",
)
private val KIND_LABEL = mapOf("THOUGHT" to "Thought", "TOOL_CALL" to "Tool call", "ACTION" to "Action", "RESULT" to "Result", "ERROR" to "Error")
private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

// ======================================================================= Agents
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentsSheet(vm: AppViewModel, ui: UiState) {
    val inc = ui.incidents.firstOrNull { it.id == ui.selected } ?: ui.incidents.firstOrNull()
    val events = ui.trace.filter { it.incidentId == inc?.id }
    val scroll = rememberScrollState()
    LaunchedEffect(events.size) { scroll.animateScrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(scroll).padding(horizontal = 18.dp).padding(bottom = 18.dp)) {
        if (inc == null) {
            Text("Agent Operations Room", style = MaterialTheme.typography.titleLarge)
            Text("Run the demo (▶ top right) or send an SOS to watch agents think → call tools → act, live.",
                color = RS.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            Button(onClick = { vm.runDemo() }, colors = ButtonDefaults.buttonColors(containerColor = RS.Red)) { Text("▶  Run demo scenario") }
            return@Column
        }
        if (ui.incidents.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.incidents.take(12).forEach { i ->
                    val sel = i.id == inc.id
                    Row(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(if (sel) RS.Ink else RS.Surface2).clickable { vm.select(i.id) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(RS.urgency(i.urgency))); Spacer(Modifier.width(6.dp))
                        Text("${i.urgency ?: "··"} ${i.category?.replace('_', '/') ?: "…"}", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = if (sel) androidx.compose.ui.graphics.Color.White else RS.Ink, maxLines = 1)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(inc.category?.replace("_", " / ") ?: "Assessing…", style = MaterialTheme.typography.titleLarge)
                Text("“${inc.transcription}”", color = RS.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (inc.urgency != null) Pill(inc.urgency, RS.urgency(inc.urgency))
        }
        Spacer(Modifier.height(12.dp))
        val names = events.map { it.name }.toSet()
        val next = PIPE.indexOfFirst { it.first !in names }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PIPE.forEachIndexed { i, (k, label) ->
                val done = k in names
                val active = !done && i == next && inc.status !in setOf("RESOLVED", "ESCALATED_MUNICIPAL")
                val c = when { done -> RS.Green; active -> RS.Amber; else -> RS.Dim }
                Row(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(c.copy(alpha = if (done || active) .12f else .06f)).padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (done) { Icon(Icons.Default.CheckCircle, null, tint = c, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp)) }
                    Text(label, color = c, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        SectionLabel("Live reasoning trace · ${events.size} steps")
        Spacer(Modifier.height(8.dp))
        events.forEach { TraceRow(it) }
    }
}

@Composable
private fun TraceRow(e: TraceEvent) {
    val c = RS.kind(e.kind)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).animateContentSize()) {
        Box(
            Modifier.width(20.dp).fillMaxHeight().drawBehind {
                val x = size.width / 2
                drawLine(RS.Line2, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
                drawCircle(androidx.compose.ui.graphics.Color.White, 7.dp.toPx(), Offset(x, 15.dp.toPx()))
                drawCircle(c, 4.5.dp.toPx(), Offset(x, 15.dp.toPx()))
            }
        )
        Column(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 10.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(KIND_LABEL[e.kind] ?: e.kind, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(c.copy(alpha = .12f)).padding(horizontal = 6.dp, vertical = 1.dp))
                Spacer(Modifier.width(8.dp))
                Text(e.name, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = RS.Ink, modifier = Modifier.weight(1f), maxLines = 1)
                Text(fmt.format(Date((e.ts * 1000).toLong())), fontSize = 10.sp, color = RS.Dim)
            }
            Text(e.detail, fontSize = 13.sp, color = RS.Muted, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
            Text(e.agent, fontSize = 10.5.sp, color = RS.Dim, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ======================================================================= Volunteer
@Composable
fun VolunteerSheet(vm: AppViewModel, ui: UiState, onNavigate: () -> Unit) {
    val me = ui.volunteers.firstOrNull { it.id == vm.prefs.volunteerId }
    val mission = me?.assignedIncident?.let { id -> ui.incidents.firstOrNull { it.id == id } }
        ?: ui.offer?.let { o -> ui.incidents.firstOrNull { it.id == o.incidentId && it.volunteerName == me?.name } }
    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 18.dp)) {
        AnimatedVisibility(ui.offer != null, enter = fadeIn() + expandVertically()) {
            ui.offer?.let { OfferCard(it, { vm.answerOffer(true); onNavigate() }, { vm.answerOffer(false) }) }
        }
        if (mission != null && mission.status in setOf("ACCEPTED", "EN_ROUTE", "ON_SCENE")) {
            val eta = ui.liveEta[mission.id] ?: mission.etaMin
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(RS.Ink).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Active mission")
                        Text(mission.locationLabel ?: "Incident", color = androidx.compose.ui.graphics.Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    if (eta != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$eta", color = androidx.compose.ui.graphics.Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black, lineHeight = 34.sp)
                        Text("min", color = RS.Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(10.dp))
                mission.resourceName?.let { MissionLine("1", "Pick up $it") }
                MissionLine(if (mission.resourceName != null) "2" else "1", "Deliver to caller · ${mission.category?.replace('_', '/') ?: ""} ${mission.urgency ?: ""}")
                if (mission.blocked.isNotEmpty()) MissionLine("⚠", "Avoid ${mission.blocked.joinToString()} — route already detours", RS.Amber)
                MissionLine("#", "Verify code ${mission.token ?: "—"} on arrival")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { vm.select(mission.id); onNavigate() }, modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RS.Blue)) {
                        Icon(Icons.Default.Navigation, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Navigate")
                    }
                    Button(onClick = { vm.resolve(mission.id) }, modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RS.Green)) { Text("Delivered") }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        SectionLabel("Responding as")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ui.volunteers.forEach { v ->
                val sel = v.id == me?.id
                Column(
                    Modifier.width(92.dp).clip(RoundedCornerShape(18.dp)).background(if (sel) RS.Blue.copy(alpha = .08f) else RS.Surface)
                        .border(if (sel) 2.dp else 1.dp, if (sel) RS.Blue else RS.Line, RoundedCornerShape(18.dp))
                        .clickable { vm.chooseVolunteer(v.id) }.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(RS.vehicle(v.transport).copy(alpha = .15f)), contentAlignment = Alignment.Center) {
                        Text(v.name.split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString(""), color = RS.vehicle(v.transport), fontWeight = FontWeight.Black)
                    }
                    Text(v.name.substringBefore(" "), fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
                    Text(vehicleName(v.transport), color = RS.Muted, fontSize = 11.sp, maxLines = 1)
                    Text(if (v.assignedIncident != null) "On mission" else "Available", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = if (v.assignedIncident != null) RS.Amber else RS.Green)
                }
            }
        }
        if (me == null) Text("Pick who you are to receive dispatch requests on this phone.", color = RS.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        else {
            val msgs = ui.messages.filter { it.phone == me.phone }.takeLast(8)
            if (msgs.isNotEmpty()) {
                Spacer(Modifier.height(16.dp)); SectionLabel("SMS · ${me.phone}"); Spacer(Modifier.height(8.dp))
                msgs.forEach { m ->
                    val out = m.direction == "out"
                    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp), contentAlignment = if (out) Alignment.CenterStart else Alignment.CenterEnd) {
                        Text(m.body, fontSize = 13.sp, color = if (out) RS.Ink else androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(18.dp)).background(if (out) RS.Surface2 else RS.Blue)
                                .padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MissionLine(n: String, text: String, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.White) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(0x26FFFFFF)), contentAlignment = Alignment.Center) {
            Text(n, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = color.copy(alpha = .9f), fontSize = 13.sp)
    }
}

@Composable
private fun OfferCard(o: Offer, onYes: () -> Unit, onNo: () -> Unit) {
    var remaining by remember(o) { mutableFloatStateOf(1f) }
    LaunchedEffect(o) {
        while (remaining > 0f) { remaining = 1f - (System.currentTimeMillis() - o.receivedAt) / (o.timeoutS.toFloat() * 1000f); delay(200) }
    }
    val secs = (remaining.coerceAtLeast(0f) * o.timeoutS).toInt()
    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(22.dp)).background(RS.Surface)
            .border(2.dp, RS.Red, RoundedCornerShape(22.dp)).padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Pill("INCOMING DISPATCH", RS.Red)
                Text("Someone nearby needs you", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
            }
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(56.dp)) {
                    drawCircle(RS.Line, style = Stroke(5.dp.toPx()))
                    drawArc(RS.Red, -90f, 360f * remaining.coerceIn(0f, 1f), false, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                }
                Text("$secs", fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
        }
        Text(o.body.removePrefix("ReliefSync Alert: "), color = RS.Muted, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.padding(vertical = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onNo, modifier = Modifier.weight(1f).height(54.dp)) { Text("Pass", fontWeight = FontWeight.Bold, color = RS.Ink) }
            Button(onClick = onYes, modifier = Modifier.weight(1.6f).height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = RS.Green)) {
                Text("Accept & go", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            }
        }
    }
}

// ======================================================================= Settings
@Composable
fun SettingsDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var server by remember { mutableStateOf(vm.prefs.server) }
    var gateway by remember { mutableStateOf(vm.prefs.gateway) }
    var phone by remember { mutableStateOf(vm.prefs.phone) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = RS.Surface,
        title = { Text("Settings", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column {
                OutlinedTextField(server, { server = it }, label = { Text("ReliefSync server") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Emulator: http://10.0.2.2:8077 · Phone: http://<mac-ip>:8077") })
                OutlinedTextField(gateway, { gateway = it }, label = { Text("SMS gateway (no-data fallback)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it }, label = { Text("My phone (status texts)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("API keys (Claude, Whisper, Twilio, maps) are set in the web app ⚙ on the server.", color = RS.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.padding(top = 6.dp)) {
                    TextButton(onClick = { vm.runDemo(); onDismiss() }) { Text("▶ Run demo") }
                    TextButton(onClick = { vm.resetDemo(); onDismiss() }) { Text("Reset scenario", color = RS.Muted) }
                }
            }
        },
        confirmButton = { Button(onClick = { vm.updateSettings(server, gateway, phone); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = RS.Muted) } },
    )
}
