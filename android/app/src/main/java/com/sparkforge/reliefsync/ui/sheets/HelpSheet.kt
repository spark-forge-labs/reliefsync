package com.sparkforge.reliefsync.ui.sheets

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sparkforge.reliefsync.audio.MAX_SECONDS
import com.sparkforge.reliefsync.data.Incident
import com.sparkforge.reliefsync.ui.AppViewModel
import com.sparkforge.reliefsync.ui.CaptureMode
import com.sparkforge.reliefsync.ui.Pill
import com.sparkforge.reliefsync.ui.RecState
import com.sparkforge.reliefsync.ui.SectionLabel
import com.sparkforge.reliefsync.ui.Tab
import com.sparkforge.reliefsync.ui.UiState
import com.sparkforge.reliefsync.ui.theme.Mono
import com.sparkforge.reliefsync.ui.theme.RS
import kotlin.math.sin

private val QUICK = listOf(
    "🫁 Oxygen / power" to "Oxygen concentrator needs power.",
    "🩺 Medical" to "Medical emergency, need medication.",
    "🌊 Trapped by water" to "Water rising, we are trapped and need evacuation.",
    "🍼 Baby care" to "Need infant formula for a baby.",
    "💧 Food & water" to "Need drinking water and food.",
)

@Composable
fun HelpSheet(vm: AppViewModel, ui: UiState) {
    val mine = ui.incidents.firstOrNull { it.id in ui.myIncidents && it.status != "RESOLVED" }
        ?: ui.incidents.firstOrNull { it.id == ui.selected && it.status !in setOf("RESOLVED") && ui.myIncidents.isEmpty() && it.source == "demo" }
    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 18.dp)) {
        if (mine != null) {
            StatusCard(mine, ui.liveEta[mine.id], ui.volPositions.values.firstOrNull { it.incidentId == mine.id }?.progress,
                onAgents = { vm.select(mine.id); vm.go(Tab.OPS) })
            Spacer(Modifier.height(14.dp))
        }
        if (!ui.online) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RS.Amber.copy(alpha = .12f)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.CloudOff, null, tint = RS.Amber); Spacer(Modifier.width(10.dp))
                Text("No data. Your SOS is saved on this phone and sends automatically — or text it now.", color = Color(0xFF92400E), fontSize = 12.5.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (mine == null) "Need help?" else "Need more help?", style = MaterialTheme.typography.headlineSmall, color = RS.Ink)
                Text("Tap SOS and say what's happening and where. Agents dispatch the nearest responder.",
                    color = RS.Muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            SosButton(ui.rec) { vm.toggleRecord() }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QUICK.forEach { (label, text) ->
                Text(label, fontSize = 13.sp, color = RS.Ink, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, RS.Line2, RoundedCornerShape(20.dp))
                        .clickable { vm.quickNeed(text) }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(RS.Surface2).padding(start = 4.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = ui.transcript, onValueChange = vm::setTranscript, modifier = Modifier.weight(1f),
                placeholder = { Text("Or type: what's happening, where?", color = RS.Dim) }, maxLines = 4,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = RS.Red),
            )
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(if (ui.transcript.isBlank()) RS.Line2 else RS.Red)
                    .clickable(enabled = ui.transcript.isNotBlank() && ui.rec == RecState.IDLE) { vm.sendText() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White, modifier = Modifier.size(19.dp)) }
        }
        Spacer(Modifier.height(10.dp))
        val ctx = LocalContext.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use demo script", color = RS.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.useDemoScript() }.padding(6.dp))
            Spacer(Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { ctx.startActivity(vm.smsIntent(ui.transcript)) }.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Sms, null, tint = RS.Muted, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp))
                Text("No data? Text SOS", color = RS.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (ui.queued > 0) Text("⏳ ${ui.queued} SOS waiting for signal — will send automatically", color = RS.Amber, fontSize = 12.sp)
    }
}

private val FLOW = listOf("TRIAGED" to "Assessed", "DISPATCHING" to "Matched", "EN_ROUTE" to "On the way", "ON_SCENE" to "Arrived")

@Composable
fun StatusCard(inc: Incident, liveEta: Int?, progress: Float?, onAgents: () -> Unit) {
    val stage = when (inc.status) {
        "RECEIVED", "TRANSCRIBED" -> 0; "TRIAGED" -> 1; "DISPATCHING" -> 2; "ACCEPTED", "EN_ROUTE" -> 3; "ON_SCENE", "RESOLVED" -> 4; else -> 2
    }
    val (title, sub) = when (inc.status) {
        "RECEIVED", "TRANSCRIBED", "TRIAGED" -> "Assessing your request" to "AI agents are reading your message…"
        "DISPATCHING" -> "Finding a responder" to "Contacting the nearest volunteers with the right supplies"
        "ACCEPTED", "EN_ROUTE" -> "Help is on the way" to "${inc.volunteerName ?: "A volunteer"} is driving to you on a flood-safe route"
        "ON_SCENE" -> "Your responder has arrived" to "Show them your code"
        "ESCALATED_MUNICIPAL" -> "Escalated to emergency services" to "City responders have your request"
        else -> inc.status to ""
    }
    val eta = liveEta ?: inc.etaMin
    val onTheWay = inc.status in setOf("ACCEPTED", "EN_ROUTE")
    Column(
        Modifier.fillMaxWidth().shadow(0.dp).clip(RoundedCornerShape(22.dp))
            .background(if (onTheWay) Brush.linearGradient(listOf(Color(0xFF1D4ED8), Color(0xFF2563EB), Color(0xFF0EA5E9)))
                        else Brush.linearGradient(listOf(Color(0xFF0B1220), Color(0xFF1E293B))))
            .padding(18.dp).animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (inc.urgency != null) Pill(inc.urgency, if (inc.urgency == "P0") Color(0xFFFFB4BC) else Color(0xFFFDE68A))
                    Spacer(Modifier.width(6.dp))
                    Text(inc.category?.replace("_", " / ")?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "", color = Color(0xCCFFFFFF), fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 25.sp)
                Text(sub, color = Color(0xCCFFFFFF), fontSize = 13.sp, lineHeight = 17.sp)
            }
            if (onTheWay && eta != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 10.dp)) {
                    Text("$eta", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black, lineHeight = 40.sp)
                    Text("min", color = Color(0xCCFFFFFF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else if (stage < 3 && inc.status != "ESCALATED_MUNICIPAL") {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        // stepper
        Row(verticalAlignment = Alignment.CenterVertically) {
            FLOW.forEachIndexed { i, (_, label) ->
                val done = stage > i; val now = stage == i + 1 || (stage == i && i == 0)
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF))) {
                        val f = when { stage > i + 1 -> 1f; stage == i + 1 -> if (i == 2 && progress != null) progress.coerceIn(0.05f, 1f) else 1f; else -> 0f }
                        val anim by animateFloatAsState(f, tween(500), label = "s$i")
                        Box(Modifier.fillMaxWidth(anim).height(4.dp).background(Color.White))
                    }
                    Text(label, color = if (done || now) Color.White else Color(0x80FFFFFF), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 5.dp))
                }
                if (i < FLOW.lastIndex) Spacer(Modifier.width(6.dp))
            }
        }
        if (inc.volunteerName != null) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x26FFFFFF)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Text(inc.volunteerName.split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString(""), color = RS.Blue, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(inc.volunteerName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("${vehicleName(inc.volunteerTransport)}${inc.resourceName?.let { " · bringing $it" } ?: ""}",
                        color = Color(0xCCFFFFFF), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (inc.token != null) Column(horizontalAlignment = Alignment.End) {
                    Text("CODE", color = Color(0x99FFFFFF), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(inc.token, color = Color.White, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
        if (onTheWay && inc.miles != null) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🧭 ${"%.1f".format(inc.miles)} mi · shortest safe road route", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            if (inc.blocked.isNotEmpty()) Text("⚠ Detouring around ${inc.blocked.joinToString()}", color = Color(0xFFFDE68A), fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
        }
        Text("See how the agents decided →", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onAgents).padding(vertical = 4.dp))
    }
}

fun vehicleName(t: String?) = when (t) { "4WD" -> "4×4 truck"; "CAR" -> "Car"; "BOAT" -> "Rescue boat"; "BICYCLE" -> "Bicycle"; "FOOT" -> "On foot"; else -> t ?: "" }

@Composable
fun SosButton(state: RecState, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "sos")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "t")
    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            for (k in 0..1) {
                val f = (t + k * .5f) % 1f
                drawCircle(RS.Red.copy(alpha = (1 - f) * .35f), radius = size.minDimension / 2 * (0.66f + 0.34f * f), style = Stroke(3.dp.toPx()))
            }
        }
        Box(
            Modifier.size(84.dp).shadow(14.dp, CircleShape, spotColor = RS.Red).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFFFF6B7A), RS.Red, RS.RedDeep)))
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = state == RecState.IDLE, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Mic, null, tint = Color.White, modifier = Modifier.size(24.dp))
                Text("SOS", color = Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp, letterSpacing = 2.sp)
            }
        }
    }
}


/** Full-screen immersive capture: live waveform orb + streaming transcript. */
@Composable
fun RecordingOverlay(vm: AppViewModel, ui: UiState) {
    val level by vm.level.collectAsState()
    val inf = rememberInfiniteTransition(label = "rec")
    val ph by inf.animateFloat(0f, 6.283f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "ph")
    val lv by animateFloatAsState(level, tween(90), label = "lv")
    val sending = ui.rec == RecState.SENDING
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A0610), Color(0xFF7A0E1F), Color(0xFFB3132B))))
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (sending) Color.White else Color(0xFFFF8A9A)))
                Spacer(Modifier.width(8.dp))
                Text(if (sending) "Sending to agents…" else "Listening", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("%d:%02d / 0:%02d".format(ui.elapsed.toInt() / 60, ui.elapsed.toInt() % 60, MAX_SECONDS), color = Color(0xCCFFFFFF), fontFamily = Mono)
                if (!sending) IconButton(onClick = { vm.cancelRecording() }) { Icon(Icons.Default.Close, "Cancel", tint = Color.White) }
            }
            Spacer(Modifier.weight(0.6f))
            Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val r0 = size.minDimension / 2 * 0.52f
                    for (ring in 3 downTo 1) {
                        val amp = (0.06f + lv * 0.45f) * ring / 3f
                        val path = androidx.compose.ui.graphics.Path()
                        val n = 90
                        for (i in 0..n) {
                            val a = i / n.toFloat() * 6.283f
                            val wob = 1f + amp * (0.6f * sin(a * 3 + ph * ring) + 0.4f * sin(a * 5 - ph * 1.3f))
                            val r = r0 * (1f + ring * 0.13f) * wob
                            val x = center.x + r * kotlin.math.cos(a); val y = center.y + r * sin(a)
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        path.close()
                        drawPath(path, Color.White.copy(alpha = 0.08f + 0.06f * (3 - ring)))
                    }
                    drawCircle(Color.White, r0 * (0.92f + lv * 0.12f))
                }
                if (sending) CircularProgressIndicator(color = RS.Red, strokeWidth = 4.dp, modifier = Modifier.size(56.dp))
                else Icon(Icons.Default.Mic, null, tint = RS.Red, modifier = Modifier.size(54.dp).scale(1f + lv * 0.15f))
            }
            Spacer(Modifier.height(26.dp))
            Text(
                ui.transcript.ifBlank { if (ui.mode == CaptureMode.VOICE_NOTE) "Recording voice note…\nSay what is happening and where you are." else "Say what's happening and where you are…" },
                color = if (ui.transcript.isBlank()) Color(0x99FFFFFF) else Color.White, fontSize = 22.sp, lineHeight = 30.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(78.dp).shadow(16.dp, CircleShape).clip(CircleShape).background(Color.White)
                    .clickable(enabled = !sending) { vm.toggleRecord() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Stop, "Stop and send", tint = RS.Red, modifier = Modifier.size(36.dp)) }
            Text(if (sending) "" else "Tap to send", color = Color(0xCCFFFFFF), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(8.dp))
            SectionLabel(if (ui.mode == CaptureMode.LIVE_TRANSCRIPT) "On-device speech recognition · works offline" else "Compressed AAC voice note", Modifier)
        }
    }
}
