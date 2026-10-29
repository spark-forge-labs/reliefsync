package com.sparkforge.reliefsync.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sparkforge.reliefsync.audio.LiveTranscriber
import com.sparkforge.reliefsync.audio.MAX_SECONDS
import com.sparkforge.reliefsync.audio.VoiceRecorder
import com.sparkforge.reliefsync.data.Api
import com.sparkforge.reliefsync.data.Hazard
import com.sparkforge.reliefsync.data.Incident
import com.sparkforge.reliefsync.data.LinkState
import com.sparkforge.reliefsync.data.LiveClient
import com.sparkforge.reliefsync.data.Offer
import com.sparkforge.reliefsync.data.OfflineQueue
import com.sparkforge.reliefsync.data.Prefs
import com.sparkforge.reliefsync.data.Resource
import com.sparkforge.reliefsync.data.SmsMessage
import com.sparkforge.reliefsync.data.TraceEvent
import com.sparkforge.reliefsync.data.Volunteer
import com.sparkforge.reliefsync.data.mapObj
import com.sparkforge.reliefsync.data.parseHazard
import com.sparkforge.reliefsync.data.parseIncident
import com.sparkforge.reliefsync.data.parseResource
import com.sparkforge.reliefsync.data.parseSms
import com.sparkforge.reliefsync.data.parseTrace
import com.sparkforge.reliefsync.data.parseVolunteer
import com.sparkforge.reliefsync.notify.Notifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

enum class Tab { SOS, OPS, MAP, VOLUNTEER }
enum class CaptureMode { LIVE_TRANSCRIPT, VOICE_NOTE }
enum class RecState { IDLE, RECORDING, SENDING }

data class UiState(
    val tab: Tab = Tab.SOS,
    val link: LinkState = LinkState.CONNECTING,
    val online: Boolean = true,
    val incidents: List<Incident> = emptyList(),
    val trace: List<TraceEvent> = emptyList(),
    val volunteers: List<Volunteer> = emptyList(),
    val resources: List<Resource> = emptyList(),
    val hazards: List<Hazard> = emptyList(),
    val messages: List<SmsMessage> = emptyList(),
    val selected: String? = null,
    val myIncidents: Set<String> = emptySet(),
    val volPositions: Map<String, Pair<Double, Double>> = emptyMap(),
    val liveEta: Map<String, Int> = emptyMap(),
    val offer: Offer? = null,
    val rec: RecState = RecState.IDLE,
    val mode: CaptureMode = CaptureMode.LIVE_TRANSCRIPT,
    val transcript: String = "",
    val elapsed: Float = 0f,
    val queued: Int = 0,
    val toast: String? = null,
    val serverConfig: JSONObject? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    val prefs = Prefs(app)
    private val api = Api(prefs)
    private val queue = OfflineQueue.get(app)
    private val live = LiveClient(viewModelScope) { prefs.server }
    private val recorder = VoiceRecorder(app, viewModelScope)
    private val asr = LiveTranscriber(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Audio level 0..1 for the waveform (from MediaRecorder or SpeechRecognizer RMS). */
    val level = MutableStateFlow(0f)
    private var levelJob: Job? = null
    private var gps: Location? = null

    init {
        viewModelScope.launch { live.state.collect { s -> _ui.update { it.copy(link = s) }; if (s == LinkState.LIVE) flushQueue() } }
        viewModelScope.launch { live.events.collect(::onEvent) }
        viewModelScope.launch { queue.count.collect { c -> _ui.update { it.copy(queued = c) } } }
        viewModelScope.launch { asr.text.collect { t -> if (_ui.value.rec == RecState.RECORDING) _ui.update { it.copy(transcript = t) } } }
        watchNetwork()
        live.start()
    }

    // ------------------------------------------------------------------ live events
    private fun onEvent(ev: JSONObject) {
        when (ev.optString("type")) {
            "snapshot" -> {
                val st = ev.getJSONObject("state")
                val tr = ev.optJSONArray("trace")
                _ui.update { u ->
                    u.copy(
                        incidents = st.optJSONArray("incidents").mapObj(::parseIncident),
                        volunteers = st.optJSONArray("volunteers").mapObj(::parseVolunteer),
                        resources = st.optJSONArray("resources").mapObj(::parseResource),
                        hazards = st.optJSONArray("hazards").mapObj(::parseHazard),
                        messages = st.optJSONArray("messages").mapObj(::parseSms),
                        trace = if (tr != null) tr.mapObj(::parseTrace) else u.trace,
                        serverConfig = st.optJSONObject("config"),
                        volPositions = emptyMap(),
                    )
                }
            }
            "trace" -> {
                val e = parseTrace(ev.getJSONObject("event"))
                _ui.update { u -> u.copy(trace = (u.trace + e).takeLast(1200), selected = u.selected ?: e.incidentId) }
            }
            "incident" -> {
                val inc = parseIncident(ev.getJSONObject("incident"))
                val prev = _ui.value.incidents.firstOrNull { it.id == inc.id }
                _ui.update { u ->
                    val list = listOf(inc) + u.incidents.filter { it.id != inc.id }
                    u.copy(incidents = list.sortedByDescending { it.createdAt },
                        selected = if (prev == null && u.tab == Tab.OPS || u.selected == null) inc.id else u.selected)
                }
                if (inc.id in _ui.value.myIncidents) notifyMine(inc, prev)
                if (prev?.status != inc.status && (inc.status == "EN_ROUTE" || inc.status == "RESOLVED")) refreshState()
            }
            "sms" -> _ui.update { it.copy(messages = (it.messages + parseSms(ev.getJSONObject("message"))).takeLast(300)) }
            "offer" -> {
                val vid = ev.optString("volunteer_id")
                if (vid == prefs.volunteerId) {
                    val offer = Offer(ev.optString("incident_id"), vid, ev.optString("body"), ev.optDouble("timeout_s", 180.0), System.currentTimeMillis())
                    _ui.update { it.copy(offer = offer) }
                    Notifier.showOffer(ctx, vid, offer.body, offer.timeoutS)
                    buzz(longArrayOf(0, 400, 150, 400, 150, 800))
                }
                refreshState()
            }
            "volunteer_pos" -> {
                val vid = ev.optString("volunteer_id")
                _ui.update { u ->
                    u.copy(volPositions = u.volPositions + (vid to (ev.optDouble("lat") to ev.optDouble("lon"))),
                        liveEta = u.liveEta + (ev.optString("incident_id") to ev.optInt("eta_min")))
                }
            }
        }
    }

    private fun notifyMine(inc: Incident, prev: Incident?) {
        if (prev?.status == inc.status) return
        val (title, text, prog) = when (inc.status) {
            "TRANSCRIBED", "TRIAGED" -> Triple("SOS received", "Agents are assessing your request…", 20)
            "DISPATCHING" -> Triple("Finding a volunteer", "${inc.urgency ?: ""} ${inc.category ?: ""} · contacting nearest helpers", 45)
            "EN_ROUTE" -> Triple("Help is on the way", "${inc.volunteerName} en route · ETA ${inc.etaMin} min · code ${inc.token}", 75)
            "ON_SCENE" -> Triple("Volunteer has arrived", "Show code ${inc.token}", 95)
            "RESOLVED" -> Triple("Request resolved", "Your personal data has been erased.", 100)
            "ESCALATED_MUNICIPAL" -> Triple("Escalated to emergency services", "Municipal responders have your request.", 60)
            else -> return
        }
        Notifier.showStatus(ctx, title, text, prog, inc.status !in setOf("RESOLVED"))
        if (inc.status == "EN_ROUTE") buzz(longArrayOf(0, 80, 60, 80))
    }

    // ------------------------------------------------------------------ SOS capture
    fun setMode(m: CaptureMode) = _ui.update { it.copy(mode = m) }
    fun setTranscript(t: String) = _ui.update { it.copy(transcript = t) }

    fun toggleRecord() {
        when (_ui.value.rec) {
            RecState.IDLE -> startRecording()
            RecState.RECORDING -> stopRecording()
            RecState.SENDING -> Unit
        }
    }

    private fun startRecording() {
        refreshGps()
        val mode = if (_ui.value.mode == CaptureMode.LIVE_TRANSCRIPT && !asr.available()) CaptureMode.VOICE_NOTE else _ui.value.mode
        val ok = if (mode == CaptureMode.LIVE_TRANSCRIPT) { asr.start(); true } else recorder.start()
        if (!ok) { toast("Microphone unavailable – type your SOS"); return }
        _ui.update { it.copy(rec = RecState.RECORDING, transcript = "", elapsed = 0f, mode = mode) }
        buzz(longArrayOf(0, 40))
        val t0 = System.currentTimeMillis()
        levelJob?.cancel()
        levelJob = viewModelScope.launch {
            while (isActive) {
                level.value = if (mode == CaptureMode.LIVE_TRANSCRIPT) asr.level.value else recorder.level.value
                val el = (System.currentTimeMillis() - t0) / 1000f
                _ui.update { it.copy(elapsed = el) }
                if (el >= MAX_SECONDS) { stopRecording(); break }
                delay(50)
            }
        }
    }

    private fun stopRecording() {
        levelJob?.cancel(); level.value = 0f
        buzz(longArrayOf(0, 30, 40, 30))
        val mode = _ui.value.mode
        _ui.update { it.copy(rec = RecState.SENDING) }
        viewModelScope.launch {
            var audio: File? = null
            if (mode == CaptureMode.LIVE_TRANSCRIPT) { asr.stop(); delay(900) } else audio = recorder.stop()
            val text = _ui.value.transcript.ifBlank { asr.text.value }
            submit(text, audio)
        }
    }

    fun sendText() {
        if (_ui.value.transcript.isBlank()) { toast("Describe what is happening first"); return }
        refreshGps()
        _ui.update { it.copy(rec = RecState.SENDING) }
        viewModelScope.launch { submit(_ui.value.transcript, null) }
    }

    fun useDemoScript() = setTranscript(DEMO_SCRIPT)

    private suspend fun submit(text: String?, audio: File?) {
        if (text.isNullOrBlank() && audio == null) {
            toast("Nothing captured – try again or type it"); _ui.update { it.copy(rec = RecState.IDLE) }; return
        }
        // Device GPS only when the message has no landmark (landmark geocoding is more precise indoors).
        val hasLandmark = Regex("(street|\\bst\\b|avenue|\\bave\\b|near|church|park|school|station|bridge|hospital)", RegexOption.IGNORE_CASE)
            .containsMatchIn(text.orEmpty())
        val lat = gps?.latitude?.takeIf { !hasLandmark }; val lon = gps?.longitude?.takeIf { !hasLandmark }
        val clientId = java.util.UUID.randomUUID().toString()
        val sent = _ui.value.online && runCatching {
            val id = api.sendSos(text, audio, lat, lon, clientId, prefs.phone)
            _ui.update { it.copy(myIncidents = it.myIncidents + id, selected = id, tab = Tab.OPS) }
            audio?.delete()
        }.isSuccess
        if (sent) {
            toast("SOS sent – agents engaged"); buzz(longArrayOf(0, 60))
        } else {
            queue.add(text, audio, lat, lon)
            OfflineQueue.scheduleSync(ctx)
            toast("Offline – SOS saved and will auto-send. SMS fallback available.")
        }
        _ui.update { it.copy(rec = RecState.IDLE, transcript = if (sent) "" else it.transcript) }
    }

    /** SMS fallback (spec 4.2): opens the SMS app pre-filled for the gateway — no data needed. */
    fun smsIntent(text: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${prefs.gateway}"))
            .putExtra("sms_body", "HELP ${text.ifBlank { "need assistance" }}".take(300))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun flushQueue() {
        if (queue.count.value > 0) OfflineQueue.scheduleSync(ctx)
    }

    // ------------------------------------------------------------------ volunteer
    fun chooseVolunteer(id: String) { prefs.volunteerId = id; _ui.update { it.copy(offer = null) } }

    fun answerOffer(accept: Boolean) {
        val o = _ui.value.offer ?: return
        _ui.update { it.copy(offer = null) }
        Notifier.cancelOffer(ctx)
        viewModelScope.launch {
            runCatching { api.respond(o.volunteerId, accept) }
                .onSuccess { toast(if (accept) "Accepted – follow the safe route" else "Passed to next volunteer") ; if (accept) { _ui.update { it.copy(tab = Tab.MAP, selected = o.incidentId) } } }
                .onFailure { toast("Offline – reply ${if (accept) "YES" else "NO"} by SMS to ${prefs.gateway}") }
        }
    }

    fun resolve(id: String) = viewModelScope.launch {
        runCatching { api.resolve(id, null) }.onSuccess { toast("Delivered · victim PII scrubbed") }.onFailure { toast("Could not reach server") }
    }

    // ------------------------------------------------------------------ misc
    fun select(id: String?) = _ui.update { it.copy(selected = id) }
    fun go(tab: Tab) = _ui.update { it.copy(tab = tab) }
    fun consumeToast() = _ui.update { it.copy(toast = null) }
    fun toast(msg: String) = _ui.update { it.copy(toast = msg) }

    fun runDemo() = viewModelScope.launch {
        runCatching { api.demo() }.onSuccess { _ui.update { it.copy(tab = Tab.OPS, selected = null) } }.onFailure { toast("Server unreachable at ${prefs.server}") }
    }

    fun resetDemo() = viewModelScope.launch {
        runCatching { api.reset() }.onSuccess { _ui.update { it.copy(trace = emptyList(), selected = null, myIncidents = emptySet(), offer = null) }; toast("Scenario reset") }
    }

    fun updateSettings(server: String, gateway: String, phone: String) {
        prefs.server = server; prefs.gateway = gateway; prefs.phone = phone
        live.reconnectNow()
    }

    private fun refreshState() = viewModelScope.launch {
        runCatching { api.state() }.onSuccess { st ->
            _ui.update { u ->
                u.copy(volunteers = st.optJSONArray("volunteers").mapObj(::parseVolunteer),
                    resources = st.optJSONArray("resources").mapObj(::parseResource))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshGps() {
        runCatching {
            val lm = ctx.getSystemService(LocationManager::class.java)
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { lm.isProviderEnabled(it) }
            gps = providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } ?: gps
            if (Build.VERSION.SDK_INT >= 30 && providers.isNotEmpty())
                lm.getCurrentLocation(providers.first(), null, ctx.mainExecutor) { l -> if (l != null) gps = l }
        }
    }

    private fun watchNetwork() {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val online = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        _ui.update { it.copy(online = online) }
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _ui.update { it.copy(online = true) }; live.reconnectNow(); OfflineQueue.scheduleSync(ctx)
            }
            override fun onLost(network: Network) { _ui.update { it.copy(online = false) } }
        })
    }

    private fun buzz(pattern: LongArray) {
        runCatching {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    override fun onCleared() { live.stop(); asr.destroy() }

    companion object {
        const val DEMO_SCRIPT = "Water is coming into our living room on 12th Street near St. Jude's church! " +
            "My mother's oxygen concentrator battery is down to 10% and we have no power!"
    }
}
