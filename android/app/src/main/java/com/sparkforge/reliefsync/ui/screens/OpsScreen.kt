package com.sparkforge.reliefsync.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sparkforge.reliefsync.data.Incident
import com.sparkforge.reliefsync.data.TraceEvent
import com.sparkforge.reliefsync.ui.AppViewModel
import com.sparkforge.reliefsync.ui.UiState
import com.sparkforge.reliefsync.ui.theme.Mono
import com.sparkforge.reliefsync.ui.theme.RS
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PIPE = listOf(
    "AudioTranscribed" to "Transcribe", "EntityExtracted" to "Triage", "GeoResolved" to "Geo",
    "SupplyLocated" to "Supply", "VolunteersRanked" to "Rank", "RouteEvaluated" to "Route",
    "DispatchSent" to "Dispatch", "Accepted" to "Accepted", "EnRoute" to "En route",
)
val STATUS_LABEL = mapOf(
    "RECEIVED" to "Received", "TRANSCRIBED" to "Transcribed", "TRIAGED" to "Triaged", "DISPATCHING" to "Dispatching",
    "ACCEPTED" to "Accepted", "EN_ROUTE" to "Dispatched – Volunteer En Route", "ON_SCENE" to "Volunteer on scene",
    "RESOLVED" to "Resolved", "ESCALATED_MUNICIPAL" to "Escalated to municipal services", "NEEDS_LOCATION" to "Needs location",
)
private val FLOW = listOf("TRANSCRIBED", "TRIAGED", "DISPATCHING", "EN_ROUTE", "ON_SCENE", "RESOLVED")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OpsScreen(vm: AppViewModel, ui: UiState) {
    val inc = ui.incidents.firstOrNull { it.id == ui.selected } ?: ui.incidents.firstOrNull()
    val events = ui.trace.filter { it.incidentId == inc?.id }
    val list = rememberLazyListState()
    LaunchedEffect(events.size) { if (events.isNotEmpty()) list.animateScrollToItem(events.size + 1) }

    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp)) {
        item {
            if (ui.incidents.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                    items(ui.incidents.take(15), key = { it.id }) { i -> IncidentChip(i, i.id == inc?.id) { vm.select(i.id) } }
                }
            }
        }
        item {
            if (inc == null) Empty(vm) else StatusCard(inc, ui.liveEta[inc.id], mine = inc.id in ui.myIncidents) { vm.resolve(inc.id) }
        }
        if (inc != null) {
            item {
                val names = events.map { it.name }.toSet()
                val firstMissing = PIPE.indexOfFirst { it.first !in names }
                Text("AGENT OPERATIONS ROOM", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = RS.Muted,
                    modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PIPE.forEachIndexed { idx, (k, label) ->
                        val done = k in names
                        val active = !done && idx == firstMissing && inc.status !in setOf("RESOLVED", "ESCALATED_MUNICIPAL")
                        val c = when { done -> RS.Green; active -> RS.Amber; else -> RS.Dim }
                        Text(label, color = c, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(c.copy(alpha = if (done || active) .12f else 0f))
                                .border(1.dp, c.copy(alpha = .5f), RoundedCornerShape(20.dp)).padding(horizontal = 9.dp, vertical = 3.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            items(events, key = { it.seq }) { e -> TraceRow(e) }
        }
    }
}

@Composable
private fun Empty(vm: AppViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No incidents yet", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Record an SOS or run the demo to watch agents think → call tools → act in real time.",
            color = RS.Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        Button(onClick = { vm.runDemo() }, colors = ButtonDefaults.buttonColors(containerColor = RS.Red)) { Text("▶ Run demo scenario") }
    }
}

@Composable
private fun IncidentChip(i: Incident, sel: Boolean, onClick: () -> Unit) {
    val c = RS.urgency(i.urgency)
    Column(
        Modifier.widthIn(max = 180.dp).clip(RoundedCornerShape(12.dp)).background(if (sel) RS.Panel else RS.Bg2)
            .border(1.dp, if (sel) c else RS.Line, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(6.dp))
            Text("${i.urgency ?: "··"} ${i.category?.replace('_', '/') ?: "Assessing"}", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Text(STATUS_LABEL[i.status] ?: i.status, fontSize = 11.sp, color = RS.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatusCard(inc: Incident, liveEta: Int?, mine: Boolean, onResolve: () -> Unit) {
    val c = RS.urgency(inc.urgency)
    val idx = FLOW.indexOf(if (inc.status == "ACCEPTED") "EN_ROUTE" else inc.status)
    val prog by animateFloatAsState(((idx + 1).coerceAtLeast(0)) / FLOW.size.toFloat(), tween(600), label = "prog")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(RS.Panel)
            .border(1.dp, RS.Line2, RoundedCornerShape(18.dp)).animateContentSize()
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(c))
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(inc.category?.replace("_", " / ") ?: "Assessing…", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    if (inc.urgency != null) Text(inc.urgency, color = c, fontWeight = FontWeight.Black, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = .16f)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
                if (mine) Text("YOUR REQUEST", color = RS.Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(inc.transcription.ifBlank { "…" }, color = RS.Muted, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 6.dp))
                LinearProgressIndicator(progress = { prog }, color = if (inc.status == "ESCALATED_MUNICIPAL") RS.Amber else RS.Green,
                    trackColor = RS.Line2, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)))
                Spacer(Modifier.height(8.dp))
                val eta = liveEta ?: inc.etaMin
                Text(
                    (STATUS_LABEL[inc.status] ?: inc.status) + if (inc.status in setOf("EN_ROUTE", "ACCEPTED") && eta != null) " (ETA $eta mins)" else "",
                    fontWeight = FontWeight.Bold, color = if (inc.status == "EN_ROUTE") RS.Green else RS.Text, fontSize = 14.sp,
                )
                inc.locationLabel?.let { Text("📍 $it", color = RS.Muted, fontSize = 12.sp) }
                if (inc.volunteerName != null) Text("🧑‍🚒 ${inc.volunteerName} · ${inc.volunteerTransport}" +
                    (inc.resourceName?.let { " · carrying $it" } ?: ""), color = RS.Muted, fontSize = 12.sp)
                if (inc.token != null) Text("Confirmation code  ${inc.token}", fontFamily = Mono, color = RS.Amber, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                if (inc.status in setOf("EN_ROUTE", "ON_SCENE", "ACCEPTED")) {
                    Button(onClick = onResolve, colors = ButtonDefaults.buttonColors(containerColor = RS.Green.copy(alpha = .16f), contentColor = RS.Green),
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) { Text("Mark delivered · scrub PII") }
                }
            }
        }
    }
}

private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun TraceRow(e: TraceEvent) {
    val c = RS.kind(e.kind)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier.width(22.dp).fillMaxHeight().drawBehind {
                val x = size.width / 2
                drawLine(RS.Line2, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
                drawCircle(c.copy(alpha = .25f), 8.dp.toPx(), Offset(x, 16.dp.toPx()))
                drawCircle(c, 4.5.dp.toPx(), Offset(x, 16.dp.toPx()))
            }
        )
        Column(Modifier.padding(start = 6.dp, top = 6.dp, bottom = 10.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.kind.replace('_', ' '), fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(c.copy(alpha = .16f)).padding(horizontal = 6.dp, vertical = 1.dp))
                Spacer(Modifier.width(8.dp))
                Text(e.name, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = RS.Text, modifier = Modifier.weight(1f), maxLines = 1)
                Text("${e.agent} · ${fmt.format(Date((e.ts * 1000).toLong()))}", fontSize = 10.sp, color = RS.Dim)
            }
            Text(e.detail, fontSize = 13.sp, color = Color(0xFFC9D3EA), modifier = Modifier.padding(top = 3.dp))
        }
    }
}

