package com.sparkforge.reliefsync.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.sparkforge.reliefsync.data.Hazard
import com.sparkforge.reliefsync.data.Incident
import com.sparkforge.reliefsync.data.Resource
import com.sparkforge.reliefsync.data.Volunteer
import com.sparkforge.reliefsync.ui.VehicleFix
import com.sparkforge.reliefsync.ui.theme.RS
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

/**
 * Imperative map engine on top of osmdroid (OpenStreetMap tiles).
 * Overlays are updated incrementally (no flicker); vehicles are interpolated every frame between
 * server fixes and rotate with their heading; active incidents pulse.
 */
class MapLayer(ctx: Context) {
    val view: MapView = MapView(ctx).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        isTilesScaledToDpi = true
        isVerticalMapRepetitionEnabled = false
        minZoomLevel = 4.0
        controller.setZoom(15.2)
        controller.setCenter(GeoPoint(37.7790, -122.4170))
        overlayManager.tilesOverlay.loadingBackgroundColor = 0xFFEDEBE6.toInt()
        overlayManager.tilesOverlay.loadingLineColor = 0xFFE2DFD8.toInt()
    }
    private val d = ctx.resources.displayMetrics.density

    private val hazardLayer = ArrayList<Polygon>()
    private val routeLayer = HashMap<String, RouteLines>()
    private val resourceMarkers = HashMap<String, Marker>()
    private val incidentMarkers = HashMap<String, Marker>()
    private val vehicleMarkers = HashMap<String, Marker>()
    private val anims = HashMap<String, Anim>()
    private val pulse = PulseOverlay()
    private val iconCache = HashMap<String, Drawable>()
    private var hazardSig = ""
    private var resourceSig = ""

    var follow = true
    var selectedIncident: String? = null
    var onIncidentClick: (String) -> Unit = {}
    var onVehicleClick: (String) -> Unit = {}

    private class RouteLines(val casing: Polyline, val remain: Polyline, val done: Polyline, val path: List<GeoPoint>, val sig: String, val pickup: Marker?)
    private class Anim(var from: GeoPoint, var to: GeoPoint, var t0: Long, var dur: Long, var heading: Float, var cur: Float,
                       var incident: String, var live: Boolean)

    init { view.overlays.add(pulse) }

    // ---------------------------------------------------------------- sync from state
    fun sync(hazards: List<Hazard>, resources: List<Resource>, incidents: List<Incident>, volunteers: List<Volunteer>,
             fixes: Map<String, VehicleFix>) {
        val ov = view.overlays
        // hazards (rarely change)
        val hs = hazards.joinToString { it.id }
        if (hs != hazardSig) {
            hazardSig = hs; ov.removeAll(hazardLayer.toSet()); hazardLayer.clear()
            hazards.forEach { h ->
                hazardLayer += Polygon(view).apply {
                    points = h.polygon.map { GeoPoint(it.lat, it.lon) } + GeoPoint(h.polygon[0].lat, h.polygon[0].lon)
                    fillPaint.color = RS.Red.copy(alpha = .18f).toArgb()
                    fillPaint.shader = null
                    outlinePaint.color = RS.Red.toArgb(); outlinePaint.strokeWidth = 2.5f * d
                    outlinePaint.pathEffect = DashPathEffect(floatArrayOf(10 * d, 6 * d), 0f)
                    title = "⚠ ${h.name}"; snippet = "Impassable – routes avoid this zone"
                }
            }
            ov.addAll(0, hazardLayer)
        }
        // routes
        val active = incidents.filter { it.route.size > 1 && it.status in setOf("DISPATCHING", "ACCEPTED", "EN_ROUTE", "ON_SCENE") }
        val keep = active.map { it.id }.toSet()
        routeLayer.keys.filter { it !in keep }.forEach { id -> routeLayer.remove(id)?.let { removeRoute(it) } }
        active.forEach { i ->
            val sig = "${i.status}:${i.route.size}:${i.route.first()}"
            val cur = routeLayer[i.id]
            if (cur == null || cur.sig != sig) {
                cur?.let { removeRoute(it) }
                val pts = i.route.map { GeoPoint(it.lat, it.lon) }
                val color = if (i.status == "DISPATCHING") RS.Amber else RS.Blue
                val casing = line(pts, Color(0xFF0B1220).copy(alpha = .22f), 9f)
                val remain = line(pts, color, 5.5f)
                val done = line(emptyList(), Color(0xFF94A3B8), 5.5f)
                val pickup = if (i.resourceName != null && i.route.size > 2) i.pickupIdx?.let { idx ->
                    Marker(view).apply {
                        position = pts[idx.coerceIn(0, pts.lastIndex)]
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        icon = icon("pickup") { pickupPin() }
                        title = "Pickup"; snippet = i.resourceName
                    }
                } else null
                val idx = ov.indexOf(pulse).coerceAtLeast(0)
                ov.addAll(idx, listOfNotNull(casing, done, remain, pickup))
                routeLayer[i.id] = RouteLines(casing, remain, done, pts, sig, pickup)
            }
        }
        // resources (grouped per provider)
        val rs = resources.joinToString { "${it.provider}:${it.qty}" }
        if (rs != resourceSig) {
            resourceSig = rs
            resourceMarkers.values.forEach { ov.remove(it) }; resourceMarkers.clear()
            resources.groupBy { it.provider }.forEach { (prov, items) ->
                val r = items.first()
                resourceMarkers[prov] = Marker(view).apply {
                    position = GeoPoint(r.pos.lat, r.pos.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = icon("res") { pin(RS.Sky, "+") }
                    title = prov; snippet = items.joinToString("<br>") { "${it.item} · ${it.qty}" }
                }.also { ov.add(ov.indexOf(pulse).coerceAtLeast(0), it) }
            }
        }
        // incidents
        val incKeep = incidents.filter { it.location != null }.associateBy { it.id }
        incidentMarkers.keys.filter { it !in incKeep }.forEach { ov.remove(incidentMarkers.remove(it)) }
        incKeep.values.forEach { i ->
            val resolved = i.status == "RESOLVED"
            val key = "inc-${i.urgency}-$resolved"
            val m = incidentMarkers.getOrPut(i.id) {
                Marker(view).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ -> onIncidentClick(i.id); true }
                }.also { ov.add(it) }
            }
            m.position = GeoPoint(i.location!!.lat, i.location.lon)
            m.icon = icon(key) { pin(if (resolved) Color(0xFF94A3B8) else RS.urgency(i.urgency), "!") }
            m.title = "${i.urgency ?: ""} ${i.category ?: ""}"; m.snippet = i.status
        }
        pulse.points = incidents.filter { it.location != null && it.status !in setOf("RESOLVED", "ESCALATED_MUNICIPAL") }
            .map { GeoPoint(it.location!!.lat, it.location.lon) to RS.urgency(it.urgency) }
        // vehicles
        volunteers.forEach { v ->
            val busy = v.assignedIncident != null || fixes[v.id]?.let { it.progress < 1f } == true
            val m = vehicleMarkers.getOrPut(v.id) {
                Marker(view).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    isFlat = true
                    position = GeoPoint(v.pos.lat, v.pos.lon)
                    setOnMarkerClickListener { _, _ -> onVehicleClick(v.id); true }
                }.also { ov.add(it) }
            }
            m.icon = icon("veh-${v.transport}-$busy") { vehicle(v.transport, busy) }
            m.title = v.name; m.snippet = v.transport
            val fx = fixes[v.id]
            if (fx == null) {
                if (!anims.containsKey(v.id)) m.position = GeoPoint(v.pos.lat, v.pos.lon)
            } else {
                val a = anims[v.id]
                if (a == null || a.to.latitude != fx.lat || a.to.longitude != fx.lon) {
                    val from = m.position.clone()
                    anims[v.id] = Anim(from, GeoPoint(fx.lat, fx.lon), SystemClock.uptimeMillis(), (fx.tickS * 1050).toLong(),
                        fx.heading, a?.cur ?: fx.heading, fx.incidentId, fx.progress < 1f)
                }
                routeLayer[fx.incidentId]?.let { rl ->
                    if (fx.seg >= 0 && fx.seg < rl.path.size) {
                        val here = GeoPoint(fx.lat, fx.lon)
                        rl.done.setPoints(rl.path.subList(0, fx.seg + 1) + here)
                        rl.remain.setPoints(listOf(here) + rl.path.subList((fx.seg + 1).coerceAtMost(rl.path.size), rl.path.size))
                    }
                }
            }
        }
        view.invalidate()
    }

    private fun removeRoute(r: RouteLines) { view.overlays.removeAll(listOfNotNull(r.casing, r.remain, r.done, r.pickup).toSet()) }

    private fun line(pts: List<GeoPoint>, c: Color, w: Float) = Polyline(view).apply {
        setPoints(pts)
        outlinePaint.color = c.toArgb(); outlinePaint.strokeWidth = w * d
        outlinePaint.strokeCap = Paint.Cap.ROUND; outlinePaint.strokeJoin = Paint.Join.ROUND
        outlinePaint.isAntiAlias = true
        isGeodesic = false
        infoWindow = null
    }

    // ---------------------------------------------------------------- per-frame animation
    /** Called every display frame. Returns true while something is moving. */
    fun frame(): Boolean {
        val now = SystemClock.uptimeMillis()
        var moving = false
        anims.forEach { (vid, a) ->
            val m = vehicleMarkers[vid] ?: return@forEach
            val k = ((now - a.t0).toFloat() / a.dur.coerceAtLeast(1)).coerceIn(0f, 1f)
            val lat = a.from.latitude + (a.to.latitude - a.from.latitude) * k
            val lon = a.from.longitude + (a.to.longitude - a.from.longitude) * k
            m.position = GeoPoint(lat, lon)
            val diff = ((a.heading - a.cur + 540f) % 360f) - 180f
            a.cur = (a.cur + diff * 0.2f + 360f) % 360f
            m.rotation = -a.cur   // osmdroid rotates counter-clockwise
            if (k < 1f || kotlin.math.abs(diff) > 0.5f) moving = true
            if (follow && a.live && a.incident == selectedIncident) view.controller.setCenter(m.position)
        }
        if (pulse.points.isNotEmpty()) moving = true
        view.postInvalidateOnAnimation()
        return moving
    }

    fun focus(lat: Double, lon: Double, zoom: Double = 16.0) = view.controller.animateTo(GeoPoint(lat, lon), zoom, 800L)

    // ---------------------------------------------------------------- drawing
    private fun icon(key: String, make: () -> Bitmap): Drawable =
        iconCache.getOrPut(key) { BitmapDrawable(view.resources, make()) }

    private fun pin(c: Color, glyph: String): Bitmap {
        val w = (34 * d).toInt(); val h = (44 * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = w / 2f
        val path = Path().apply {
            addCircle(r, r, r - 2 * d, Path.Direction.CW)
            moveTo(r - 7 * d, r * 1.6f); lineTo(r, h - 1 * d); lineTo(r + 7 * d, r * 1.6f); close()
        }
        p.color = 0x33000000; cv.save(); cv.translate(0f, 1.5f * d); cv.drawPath(path, p); cv.restore()
        p.color = android.graphics.Color.WHITE; cv.drawPath(path, p)
        p.color = c.toArgb(); cv.drawCircle(r, r, r - 5 * d, p)
        p.color = android.graphics.Color.WHITE; p.textAlign = Paint.Align.CENTER; p.textSize = 15 * d; p.typeface = Typeface.DEFAULT_BOLD
        cv.drawText(glyph, r, r - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    private fun pickupPin(): Bitmap {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10 * d; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f }
        val tw = p.measureText("PICKUP")
        val w = (tw + 16 * d).toInt(); val h = (28 * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(bmp)
        p.color = RS.Sky.toArgb()
        cv.drawRoundRect(RectF(0f, 0f, w.toFloat(), 20 * d), 6 * d, 6 * d, p)
        cv.drawPath(Path().apply { moveTo(w / 2f - 5 * d, 19 * d); lineTo(w / 2f, h.toFloat()); lineTo(w / 2f + 5 * d, 19 * d); close() }, p)
        p.color = android.graphics.Color.WHITE; p.textAlign = Paint.Align.CENTER
        cv.drawText("PICKUP", w / 2f, 14 * d, p)
        return bmp
    }

    /** Top-down vehicle sprite pointing north; rotated by heading at draw time. */
    private fun vehicle(t: String, busy: Boolean): Bitmap {
        val s = (46 * d).toInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val col = RS.vehicle(t).toArgb()
        val cx = s / 2f; val cy = s / 2f
        if (busy) { p.color = RS.Blue.copy(alpha = .18f).toArgb(); c.drawCircle(cx, cy, s / 2f, p) }
        fun body(w: Float, h: Float, r: Float) = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2).also {
            p.color = 0x40000000; c.drawRoundRect(RectF(it.left, it.top + 2 * d, it.right, it.bottom + 2 * d), r, r, p)
            p.color = android.graphics.Color.WHITE; c.drawRoundRect(RectF(it.left - 2 * d, it.top - 2 * d, it.right + 2 * d, it.bottom + 2 * d), r + 2 * d, r + 2 * d, p)
            p.color = col; c.drawRoundRect(it, r, r, p)
        }
        when (t) {
            "BOAT" -> {
                val path = Path().apply { moveTo(cx, cy - 18 * d); lineTo(cx + 9 * d, cy - 2 * d); lineTo(cx + 8 * d, cy + 15 * d)
                    quadTo(cx, cy + 19 * d, cx - 8 * d, cy + 15 * d); lineTo(cx - 9 * d, cy - 2 * d); close() }
                p.color = android.graphics.Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 4 * d; c.drawPath(path, p)
                p.style = Paint.Style.FILL; p.color = col; c.drawPath(path, p)
                p.color = 0xD9FFFFFF.toInt(); c.drawRoundRect(RectF(cx - 5 * d, cy, cx + 5 * d, cy + 9 * d), 2 * d, 2 * d, p)
            }
            "BICYCLE", "FOOT" -> {
                p.color = android.graphics.Color.WHITE; c.drawCircle(cx, cy, 11 * d, p)
                p.color = col; c.drawCircle(cx, cy, 8.5f * d, p)
                val arrow = Path().apply { moveTo(cx, cy - 6 * d); lineTo(cx + 5 * d, cy + 4 * d); lineTo(cx, cy + 1.5f * d); lineTo(cx - 5 * d, cy + 4 * d); close() }
                p.color = android.graphics.Color.WHITE; c.drawPath(arrow, p)
            }
            else -> {
                val truck = t == "4WD"
                val b = body(18 * d, if (truck) 32 * d else 28 * d, 5 * d)
                p.color = 0xE60B1220.toInt()
                c.drawRoundRect(RectF(b.left + 2.5f * d, b.top + 5 * d, b.right - 2.5f * d, b.top + 11 * d), 2 * d, 2 * d, p) // windshield
                if (truck) { p.color = 0x40FFFFFF; c.drawRoundRect(RectF(b.left + 3 * d, b.top + 15 * d, b.right - 3 * d, b.bottom - 3 * d), 1.5f * d, 1.5f * d, p) }
                else { p.color = 0xB30B1220.toInt(); c.drawRoundRect(RectF(b.left + 3 * d, b.bottom - 8 * d, b.right - 3 * d, b.bottom - 3 * d), 1.5f * d, 1.5f * d, p) }
                p.color = 0xFFFDE68A.toInt()
                c.drawRoundRect(RectF(b.left + 1 * d, b.top + 1 * d, b.left + 5 * d, b.top + 3 * d), 1 * d, 1 * d, p)
                c.drawRoundRect(RectF(b.right - 5 * d, b.top + 1 * d, b.right - 1 * d, b.top + 3 * d), 1 * d, 1 * d, p)
            }
        }
        return bmp
    }

    /** Expanding rings under active incidents. */
    private inner class PulseOverlay : Overlay() {
        var points: List<Pair<GeoPoint, Color>> = emptyList()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val pt = Point()
        override fun draw(c: Canvas, mv: MapView, shadow: Boolean) {
            if (shadow) return
            val t = (SystemClock.uptimeMillis() % 1800L) / 1800f
            points.forEach { (g, col) ->
                mv.projection.toPixels(g, pt)
                for (k in 0..1) {
                    val f = (t + k * 0.5f) % 1f
                    p.color = col.copy(alpha = (1f - f) * 0.55f).toArgb(); p.strokeWidth = 3 * d
                    c.drawCircle(pt.x.toFloat(), pt.y.toFloat() - 4 * d, (10 + 34 * f) * d, p)
                }
            }
        }
    }
}
