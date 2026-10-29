package com.sparkforge.reliefsync.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sparkforge.reliefsync.data.Offer
import com.sparkforge.reliefsync.ui.AppViewModel
import com.sparkforge.reliefsync.ui.UiState
import com.sparkforge.reliefsync.ui.theme.RS
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Volunteer mode: pick your identity, receive dispatch offers (heads-up + in-app), accept/decline, see SMS log. */
@Composable
fun VolunteerScreen(vm: AppViewModel, ui: UiState) {
    val me = ui.volunteers.firstOrNull { it.id == vm.prefs.volunteerId }
    val msgs = ui.messages.filter { me != null && it.phone == me.phone }
    val assigned = me?.assignedIncident?.let { id -> ui.incidents.firstOrNull { it.id == id } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("I AM VOLUNTEERING AS", style = MaterialTheme.typography.labelSmall, color = RS.Muted)
            Spacer(Modifier.height(8.dp))
            ui.volunteers.forEach { v ->
                val sel = v.id == me?.id
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (sel) RS.Green.copy(alpha = .1f) else RS.Bg2)
                        .border(1.dp, if (sel) RS.Green else RS.Line, RoundedCornerShape(12.dp))
                        .clickable { vm.chooseVolunteer(v.id) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (v.assignedIncident != null) RS.Amber else RS.Green))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.name, fontWeight = FontWeight.Bold)
                        Text("${v.transport} · ${v.phone}", fontSize = 12.sp, color = RS.Muted)
                    }
                    if (v.assignedIncident != null) Text("ON MISSION", fontSize = 10.sp, color = RS.Amber, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            AnimatedVisibility(ui.offer != null, enter = fadeIn() + expandVertically()) {
                ui.offer?.let { OfferCard(it, onYes = { vm.answerOffer(true) }, onNo = { vm.answerOffer(false) }) }
            }
        }
        if (assigned != null) item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RS.Panel)
                    .border(1.dp, RS.Green.copy(alpha = .5f), RoundedCornerShape(16.dp)).padding(14.dp)
            ) {
                Text("ACTIVE MISSION", style = MaterialTheme.typography.labelSmall, color = RS.Green)
                Text(assigned.locationLabel ?: "Incident", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                assigned.resourceName?.let { Text("Pick up: $it", color = RS.Muted, fontSize = 13.sp) }
                if (assigned.blocked.isNotEmpty()) Text("Avoid: ${assigned.blocked.joinToString()}", color = RS.Amber, fontSize = 13.sp)
                Text("Verify code on arrival: ${assigned.token ?: "—"}", color = RS.Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.select(assigned.id); vm.go(com.sparkforge.reliefsync.ui.Tab.MAP) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = RS.Blue)) { Text("Safe route") }
                    Button(onClick = { vm.resolve(assigned.id) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = RS.Green, contentColor = Color(0xFF03261A))) { Text("Delivered") }
                }
            }
        }
        if (me != null) {
            item { Text("SMS LOG · ${me.phone}", style = MaterialTheme.typography.labelSmall, color = RS.Muted, modifier = Modifier.padding(top = 8.dp)) }
            items(msgs.takeLast(30), key = { it.seq }) { m ->
                val out = m.direction == "out"
                Box(Modifier.fillMaxWidth(), contentAlignment = if (out) Alignment.CenterStart else Alignment.CenterEnd) {
                    Column(
                        Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (out) Color(0xFF1C2540) else Color(0xFF0A84FF)).padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(m.body, fontSize = 13.sp)
                        Text("${fmtT.format(Date((m.ts * 1000).toLong()))} · ${m.channel}", fontSize = 10.sp, color = RS.Text.copy(alpha = .55f))
                    }
                }
            }
            if (msgs.isEmpty()) item { Text("No messages yet. Dispatch requests appear here and as a heads-up notification.", color = RS.Dim, fontSize = 13.sp) }
        } else item {
            Text("Choose who you are to receive live dispatch requests on this phone.", color = RS.Dim, fontSize = 13.sp)
        }
    }
}

private val fmtT = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun OfferCard(o: Offer, onYes: () -> Unit, onNo: () -> Unit) {
    var remaining by remember(o) { mutableFloatStateOf(1f) }
    LaunchedEffect(o) {
        while (remaining > 0f) {
            remaining = 1f - (System.currentTimeMillis() - o.receivedAt) / (o.timeoutS.toFloat() * 1000f)
            delay(200)
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF1A1220))
            .border(1.5.dp, RS.Amber, RoundedCornerShape(18.dp)).padding(16.dp)
    ) {
        Text("INCOMING DISPATCH", style = MaterialTheme.typography.labelSmall, color = RS.Amber)
        Spacer(Modifier.height(6.dp))
        Text(o.body, fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(progress = { remaining.coerceIn(0f, 1f) }, color = RS.Amber, trackColor = RS.Line2,
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onNo, modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x22FFFFFF), contentColor = RS.Text)) { Text("NO", fontWeight = FontWeight.Black) }
            Button(onClick = onYes, modifier = Modifier.weight(1.4f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RS.Green, contentColor = Color(0xFF03261A))) { Text("YES – ACCEPT", fontWeight = FontWeight.Black) }
        }
    }
}
