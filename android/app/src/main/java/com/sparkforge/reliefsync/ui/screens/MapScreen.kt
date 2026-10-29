package com.sparkforge.reliefsync.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sparkforge.reliefsync.data.LatLon
import com.sparkforge.reliefsync.ui.AppViewModel
import com.sparkforge.reliefsync.ui.UiState
import com.sparkforge.reliefsync.ui.theme.RS
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

private class MapHolder(val view: MapView) {
    var centeredOn: String? = null
    val iconCache = HashMap<String, Drawable>()
}

@Composable
fun MapScreen(vm: AppViewModel, ui: UiState) {
    val ctx = LocalContext.current
    val holder = remember {
        val mv = MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)   // night-ops basemap
            overlayManager.tilesOverlay.loadingBackgroundColor = RS.Bg.toArgb()
            controller.setZoom(15.3)
            controller.setCenter(GeoPoint(37.7768, -122.4192))
            isTilesScaledToDpi = true
        }
        MapHolder(mv)
    }
    DisposableEffect(Unit) {
        holder.view.onResume()
        onDispose { holder.view.onPause() }
    }

    val inc = ui.incidents.firstOrNull { it.id == ui.selected } ?: ui.incidents.firstOrNull { it.status != "RESOLVED" }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { holder.view }, modifier = Modifier.fillMaxSize(), update = { mv ->
            val ov = mv.overlays
            ov.clear()
            ui.hazards.forEach { h ->
                ov += Polygon(mv).apply {
                    points = h.polygon.map { GeoPoint(it.lat, it.lon) } + GeoPoint(h.polygon[0].lat, h.polygon[0].lon)
                    fillPaint.color = RS.Red.copy(alpha = .22f).toArgb()
                    outlinePaint.color = RS.Red.toArgb(); outlinePaint.strokeWidth = 4f
                    outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 12f), 0f)
                    title = "⚠ ${h.name}"
                }
            }
            ui.incidents.filter { it.route.size > 1 && it.status in setOf("DISPATCHING", "ACCEPTED", "EN_ROUTE", "ON_SCENE") }.forEach { i ->
                val color = if (i.status == "DISPATCHING") RS.Amber else RS.Green
                ov += Polyline(mv).apply {
                    setPoints(i.route.map { GeoPoint(it.lat, it.lon) })
                    outlinePaint.color = color.copy(alpha = .25f).toArgb(); outlinePaint.strokeWidth = 26f
                }
                ov += Polyline(mv).apply {
                    setPoints(i.route.map { GeoPoint(it.lat, it.lon) })
                    outlinePaint.color = color.toArgb(); outlinePaint.strokeWidth = 9f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                }
            }
            ui.resources.groupBy { it.provider }.forEach { (prov, items) ->
                val r = items.first()
                ov += marker(mv, holder, r.pos, "res", RS.Blue, "▣", prov, items.joinToString("\n") { "${it.item} · ${it.qty}" })
            }
            ui.volunteers.forEach { v ->
                val p = ui.volPositions[v.id]?.let { LatLon(it.first, it.second) } ?: v.pos
                val busy = v.assignedIncident != null || ui.volPositions.containsKey(v.id)
                ov += marker(mv, holder, p, "vol-$busy-${v.transport}", if (busy) RS.Amber else RS.Green,
                    TRANSPORT[v.transport] ?: "•", v.name, "${v.transport}${if (busy) " · assigned" else ""}")
            }
            ui.incidents.filter { it.location != null }.forEach { i ->
                val c = if (i.status == "RESOLVED") Color(0xFF475569) else RS.urgency(i.urgency)
                ov += marker(mv, holder, i.location!!, "inc-${i.urgency}-${i.status == "RESOLVED"}", c, "!", "${i.urgency ?: ""} ${i.category ?: ""}",
                    STATUS_LABEL[i.status] ?: i.status, big = true).apply { setOnMarkerClickListener { _, _ -> vm.select(i.id); true } }
            }
            val focus = inc
            if (focus?.location != null && holder.centeredOn != focus.id + focus.status) {
                holder.centeredOn = focus.id + focus.status
                mv.controller.animateTo(GeoPoint(focus.location.lat + 0.002, focus.location.lon), 15.6, 900L)
            }
            mv.invalidate()
        })

        // Legend
        Row(
            Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(10.dp)).background(RS.Bg.copy(alpha = .85f))
                .border(1.dp, RS.Line, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(RS.Red to "Incident", RS.Green to "Volunteer", RS.Blue to "Supply", RS.Red.copy(alpha = .5f) to "Hazard").forEach { (c, t) ->
                Box(Modifier.size(8.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(4.dp))
                Text(t, fontSize = 10.5.sp, color = RS.Muted); Spacer(Modifier.width(10.dp))
            }
        }

        if (inc != null) {
            Column(
                Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(RS.Panel.copy(alpha = .95f)).border(1.dp, RS.Line2, RoundedCornerShape(16.dp)).padding(14.dp)
            ) {
                val eta = ui.liveEta[inc.id] ?: inc.etaMin
                Text((STATUS_LABEL[inc.status] ?: inc.status) + if (inc.status == "EN_ROUTE" && eta != null) " · ETA $eta min" else "",
                    fontWeight = FontWeight.Bold, color = if (inc.status == "EN_ROUTE") RS.Green else RS.Text)
                inc.locationLabel?.let { Text("📍 $it", fontSize = 12.sp, color = RS.Muted) }
                if (inc.blocked.isNotEmpty()) Text("⚠ Avoiding: ${inc.blocked.joinToString()} — rerouted via secondary ridge",
                    fontSize = 12.sp, color = RS.Amber)
                inc.volunteerName?.let { Text("🧑‍🚒 $it · ${inc.volunteerTransport}" + (inc.resourceName?.let { r -> " · $r" } ?: ""), fontSize = 12.sp, color = RS.Muted) }
            }
        }
    }
}

private val TRANSPORT = mapOf("FOOT" to "🚶", "BICYCLE" to "🚲", "CAR" to "🚗", "BOAT" to "🚤", "4WD" to "🛻")

private fun marker(mv: MapView, h: MapHolder, p: LatLon, key: String, color: Color, glyph: String, title: String, snippet: String, big: Boolean = false) =
    Marker(mv).apply {
        position = GeoPoint(p.lat, p.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = h.iconCache.getOrPut(key) { pin(mv.context, color, glyph, big) }
        this.title = title; this.snippet = snippet
    }

/** Renders a glowing circular pin (no image assets needed). */
private fun pin(ctx: Context, color: Color, glyph: String, big: Boolean): Drawable {
    val d = ctx.resources.displayMetrics.density
    val sz = ((if (big) 34 else 28) * d).toInt()
    val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = sz / 2f
    p.color = color.copy(alpha = .3f).toArgb(); c.drawCircle(r, r, r, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(r, r, r * .72f, p)
    p.color = color.toArgb(); c.drawCircle(r, r, r * .62f, p)
    p.textAlign = Paint.Align.CENTER; p.textSize = r * .8f; p.typeface = Typeface.DEFAULT_BOLD
    p.color = android.graphics.Color.WHITE
    c.drawText(glyph, r, r - (p.descent() + p.ascent()) / 2, p)
    return BitmapDrawable(ctx.resources, bmp)
}
