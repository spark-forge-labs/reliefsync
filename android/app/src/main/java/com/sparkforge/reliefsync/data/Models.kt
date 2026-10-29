package com.sparkforge.reliefsync.data

import org.json.JSONArray
import org.json.JSONObject

data class LatLon(val lat: Double, val lon: Double)

data class TraceEvent(
    val seq: Long,
    val incidentId: String,
    val ts: Double,
    val agent: String,
    val kind: String,
    val name: String,
    val detail: String,
)

data class Incident(
    val id: String,
    val status: String,
    val transcription: String,
    val category: String?,
    val urgency: String?,
    val location: LatLon?,
    val locationLabel: String?,
    val etaMin: Int?,
    val token: String?,
    val volunteerName: String?,
    val volunteerTransport: String?,
    val resourceName: String?,
    val route: List<LatLon>,
    val blocked: List<String>,
    val createdAt: Double,
    val source: String?,
    val pickupIdx: Int? = null,
    val miles: Double? = null,
    val routeEngine: String? = null,
    val caller: String? = null,
)

data class Volunteer(
    val id: String,
    val name: String,
    val phone: String,
    val transport: String,
    val pos: LatLon,
    val assignedIncident: String?,
)

data class Resource(val id: String, val provider: String, val item: String, val qty: Int, val pos: LatLon)

data class Hazard(val id: String, val name: String, val polygon: List<LatLon>)

data class SmsMessage(val seq: Long, val ts: Double, val direction: String, val phone: String, val body: String, val channel: String)

data class Offer(val incidentId: String, val volunteerId: String, val body: String, val timeoutS: Double, val receivedAt: Long)

// ---------------------------------------------------------------- parsing
private fun JSONObject.str(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)
private fun JSONObject.dbl(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k)

fun parseTrace(o: JSONObject) = TraceEvent(
    seq = o.optLong("seq"), incidentId = o.optString("incident_id"), ts = o.optDouble("ts"),
    agent = o.optString("agent"), kind = o.optString("kind"), name = o.optString("name"),
    detail = o.optString("detail"),
)

fun parseIncident(o: JSONObject): Incident {
    val lat = o.dbl("latitude"); val lon = o.dbl("longitude")
    val route = o.optJSONObject("route")
    val path = mutableListOf<LatLon>()
    route?.optJSONArray("path")?.let { arr ->
        for (i in 0 until arr.length()) arr.optJSONArray(i)?.let { path += LatLon(it.optDouble(0), it.optDouble(1)) }
    }
    val blocked = route?.optJSONArray("blocked")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
    val vol = o.optJSONObject("volunteer")
    return Incident(
        id = o.optString("id"), status = o.optString("status"), transcription = o.optString("transcription"),
        category = o.str("category"), urgency = o.str("urgency"),
        location = if (lat != null && lon != null) LatLon(lat, lon) else null,
        locationLabel = o.str("location_label"), etaMin = if (o.isNull("eta_min")) null else o.optInt("eta_min"),
        token = o.str("confirm_token"), volunteerName = vol?.optString("name"), volunteerTransport = vol?.optString("transport"),
        resourceName = o.optJSONObject("resource")?.optString("item_name"), route = path, blocked = blocked,
        createdAt = o.optDouble("created_at"), source = o.str("source"),
        pickupIdx = route?.let { if (it.isNull("pickup_idx") || !it.has("pickup_idx")) null else it.optInt("pickup_idx") },
        miles = route?.let { if (it.has("miles")) it.optDouble("miles") else null },
        routeEngine = route?.optString("engine")?.takeIf { it.isNotBlank() },
        caller = o.str("caller"),
    )
}

fun parseVolunteer(o: JSONObject) = Volunteer(
    id = o.optString("id"), name = o.optString("name"), phone = o.optString("phone_number"),
    transport = o.optString("transport_type"), pos = LatLon(o.optDouble("current_latitude"), o.optDouble("current_longitude")),
    assignedIncident = o.str("assigned_incident_id"),
)

fun parseResource(o: JSONObject) = Resource(
    o.optString("id"), o.optString("provider_name"), o.optString("item_name"), o.optInt("quantity"),
    LatLon(o.optDouble("latitude"), o.optDouble("longitude")),
)

fun parseHazard(o: JSONObject): Hazard {
    val poly = o.optJSONArray("polygon") ?: JSONArray()
    return Hazard(o.optString("id"), o.optString("name"),
        (0 until poly.length()).map { val p = poly.getJSONArray(it); LatLon(p.getDouble(0), p.getDouble(1)) })
}

fun parseSms(o: JSONObject) = SmsMessage(
    o.optLong("seq"), o.optDouble("ts"), o.optString("direction"), o.optString("phone"), o.optString("body"), o.optString("channel"),
)

inline fun <T> JSONArray?.mapObj(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it)?.let(f) }
