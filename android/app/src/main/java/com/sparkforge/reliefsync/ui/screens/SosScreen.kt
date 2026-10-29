package com.sparkforge.reliefsync.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sparkforge.reliefsync.audio.MAX_SECONDS
import com.sparkforge.reliefsync.ui.AppViewModel
import com.sparkforge.reliefsync.ui.CaptureMode
import com.sparkforge.reliefsync.ui.RecState
import com.sparkforge.reliefsync.ui.UiState
import com.sparkforge.reliefsync.ui.theme.Mono
import com.sparkforge.reliefsync.ui.theme.RS
import kotlin.math.sin

@Composable
fun SosScreen(vm: AppViewModel, ui: UiState) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val recording = ui.rec == RecState.RECORDING
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(!ui.online) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(RS.Amber.copy(alpha = .12f))
                    .border(1.dp, RS.Amber.copy(alpha = .4f), RoundedCornerShape(12.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.CloudOff, null, tint = RS.Amber)
                Spacer(Modifier.width(10.dp))
                Text("No data connection. SOS will be stored on this phone and sent automatically. Use SMS for instant help.",
                    color = RS.Amber, fontSize = 12.5.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(if (recording) "Listening… describe what's happening and where" else "Tap to record an SOS",
            color = RS.Muted, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))

        SosButton(state = ui.rec) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.toggleRecord() }

        Spacer(Modifier.height(18.dp))
        Waveform(vm, recording)
        Text(
            "%02d:%02d / 00:%02d".format(ui.elapsed.toInt() / 60, ui.elapsed.toInt() % 60, MAX_SECONDS),
            fontFamily = Mono, color = RS.Muted, fontSize = 12.sp,
        )
        Spacer(Modifier.height(14.dp))

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val opts = listOf(CaptureMode.LIVE_TRANSCRIPT to "Live transcript", CaptureMode.VOICE_NOTE to "Voice note (AAC)")
            opts.forEachIndexed { i, (m, label) ->
                SegmentedButton(
                    selected = ui.mode == m, onClick = { vm.setMode(m) }, enabled = !recording,
                    shape = SegmentedButtonDefaults.itemShape(i, opts.size),
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = RS.Red.copy(alpha = .2f), activeContentColor = RS.Text,
                        inactiveContainerColor = RS.Bg2, inactiveContentColor = RS.Muted),
                ) { Text(label, fontSize = 12.sp) }
            }
        }
        Text(
            if (ui.mode == CaptureMode.LIVE_TRANSCRIPT) "On-device speech recognition – works without internet; text is sent."
            else "Records a compressed 30 s voice note; the server transcribes & translates it (Whisper).",
            color = RS.Dim, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = ui.transcript, onValueChange = vm::setTranscript, enabled = !recording,
            label = { Text("Transcript / message") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RS.Blue, unfocusedBorderColor = RS.Line2,
                disabledTextColor = RS.Text, disabledBorderColor = RS.Amber.copy(alpha = .5f), disabledLabelColor = RS.Amber),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = vm::useDemoScript, enabled = !recording, modifier = Modifier.weight(1f)) {
                Text("Demo script", fontSize = 13.sp)
            }
            Button(
                onClick = vm::sendText, enabled = ui.rec == RecState.IDLE, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = RS.Red),
            ) { Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Send SOS") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { ctx.startActivity(vm.smsIntent(ui.transcript)) }, modifier = Modifier.fillMaxWidth(),
        ) { Icon(Icons.Default.Sms, null, Modifier.size(16.dp), tint = RS.Amber); Spacer(Modifier.width(6.dp)); Text("Send via SMS (no data)", color = RS.Amber) }
        if (ui.queued > 0) {
            Text("⏳ ${ui.queued} SOS queued offline – will auto-send when connected", color = RS.Amber, fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SosButton(state: RecState, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "pulse")
    val recording = state == RecState.RECORDING
    val dur = if (recording) 1000 else 2400
    val p1 by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(dur, easing = LinearEasing)), label = "p1")
    val press by animateFloatAsState(if (recording) 1.04f else 1f, label = "press")
    val ring = if (recording) RS.Amber else RS.Red
    Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            for (k in 0..1) {
                val t = (p1 + k * 0.5f) % 1f
                drawCircle(ring.copy(alpha = (1f - t) * 0.6f), radius = size.minDimension / 2 * (0.62f + 0.38f * t), style = Stroke(3.dp.toPx()))
            }
        }
        val grad = if (recording) Brush.radialGradient(listOf(Color(0xFFFFD166), RS.Orange, Color(0xFFC2410C)))
        else Brush.radialGradient(listOf(Color(0xFFFF7A85), Color(0xFFFF2A3F), Color(0xFFB3001B)))
        Box(
            Modifier.size(156.dp).scale(press).shadow(28.dp, CircleShape, ambientColor = RS.Red, spotColor = RS.Red)
                .clip(CircleShape).background(grad)
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = state != RecState.SENDING, onClick = onClick)
                .semantics { contentDescription = if (recording) "Stop and send SOS" else "Record SOS" },
            contentAlignment = Alignment.Center,
        ) {
            if (state == RecState.SENDING) CircularProgressIndicator(color = Color.White)
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, null, tint = Color.White, modifier = Modifier.size(38.dp))
                Text(if (recording) "SEND" else "SOS", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp, letterSpacing = 3.sp)
            }
        }
    }
}

@Composable
private fun Waveform(vm: AppViewModel, active: Boolean) {
    val level by vm.level.collectAsState()
    val inf = rememberInfiniteTransition(label = "wave")
    val phase by inf.animateFloat(0f, 6.283f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "ph")
    val hist = remember { FloatArray(48) }
    // shift history each recomposition driven by level/phase
    System.arraycopy(hist, 1, hist, 0, hist.size - 1)
    hist[hist.size - 1] = if (active) level else 0f
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val n = hist.size; val bw = size.width / n
        for (i in 0 until n) {
            val idle = 0.05f + 0.03f * sin(phase + i / 3f)
            val v = if (active) (hist[i] * 0.85f + 0.06f) else idle
            val h = (v * size.height).coerceAtLeast(3.dp.toPx())
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(RS.Amber, RS.Red), startY = size.height / 2 - h / 2, endY = size.height / 2 + h / 2),
                topLeft = Offset(i * bw + bw * .2f, size.height / 2 - h / 2), size = Size(bw * .6f, h),
                cornerRadius = CornerRadius(bw * .3f),
            )
        }
    }
}
