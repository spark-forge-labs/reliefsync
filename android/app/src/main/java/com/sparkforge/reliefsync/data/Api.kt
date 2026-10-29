package com.sparkforge.reliefsync.data

import android.content.Context
import com.sparkforge.reliefsync.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** User-tunable settings (server URL, SMS gateway, volunteer identity). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("reliefsync", Context.MODE_PRIVATE)
    var server: String
        get() = sp.getString("server", BuildConfig.DEFAULT_SERVER)!!.trimEnd('/')
        set(v) = sp.edit().putString("server", v.trim().trimEnd('/')).apply()
    var gateway: String
        get() = sp.getString("gateway", BuildConfig.SMS_GATEWAY)!!
        set(v) = sp.edit().putString("gateway", v.trim()).apply()
    var volunteerId: String?
        get() = sp.getString("volunteer", null)
        set(v) = sp.edit().putString("volunteer", v).apply()
    var phone: String
        get() = sp.getString("phone", "")!!
        set(v) = sp.edit().putString("phone", v.trim()).apply()
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)   // keeps the live WebSocket honest on flaky 4G
        .retryOnConnectionFailure(true)
        .build()
}

class Api(private val prefs: Prefs) {
    private val json = "application/json".toMediaType()

    suspend fun state(): JSONObject = get("/api/state")

    suspend fun incident(id: String): JSONObject = get("/api/incidents/$id")

    /** Multipart SOS upload: AAC voice note + on-device transcript + GPS. */
    suspend fun sendSos(text: String?, audio: File?, lat: Double?, lon: Double?, clientId: String, phone: String?): String =
        withContext(Dispatchers.IO) {
            val b = MultipartBody.Builder().setType(MultipartBody.FORM)
            if (!text.isNullOrBlank()) b.addFormDataPart("transcript", text)
            if (audio != null && audio.exists() && audio.length() > 0)
                b.addFormDataPart("audio", audio.name, audio.asRequestBody("audio/mp4".toMediaType()))
            if (lat != null && lon != null) { b.addFormDataPart("lat", lat.toString()); b.addFormDataPart("lon", lon.toString()) }
            if (!phone.isNullOrBlank()) b.addFormDataPart("phone", phone)
            b.addFormDataPart("client_id", clientId)
            val req = Request.Builder().url(prefs.server + "/api/sos").post(b.build()).build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                JSONObject(r.body!!.string()).getString("incident_id")
            }
        }

    suspend fun respond(volunteerId: String, accept: Boolean) =
        post("/api/dispatch/respond", JSONObject().put("volunteer_id", volunteerId).put("answer", if (accept) "ACCEPT" else "DECLINE"))

    suspend fun resolve(incidentId: String, token: String?) =
        post("/api/incidents/$incidentId/resolve", JSONObject().apply { if (token != null) put("token", token) })

    suspend fun demo() = post("/api/demo/scenario", JSONObject())
    suspend fun reset() = post("/api/demo/reset", JSONObject())

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        Http.client.newCall(Request.Builder().url(prefs.server + path).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            JSONObject(r.body!!.string())
        }
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(prefs.server + path).post(body.toString().toRequestBody(json)).build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val s = r.body?.string().orEmpty()
            if (s.startsWith("{")) JSONObject(s) else JSONObject()
        }
    }
}
