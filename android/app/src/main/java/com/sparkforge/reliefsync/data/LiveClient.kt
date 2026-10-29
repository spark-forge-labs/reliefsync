package com.sparkforge.reliefsync.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.math.min

enum class LinkState { CONNECTING, LIVE, OFFLINE }

/** Live agent-trace stream over WebSocket with exponential-backoff reconnect (spec 3.4 / 4.1). */
class LiveClient(private val scope: CoroutineScope, private val urlProvider: () -> String) {
    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 512)
    val events: SharedFlow<JSONObject> = _events
    private val _state = MutableStateFlow(LinkState.CONNECTING)
    val state: StateFlow<LinkState> = _state

    private var ws: WebSocket? = null
    private var attempt = 0
    private var reconnectJob: Job? = null
    @Volatile private var stopped = false

    fun start() { stopped = false; connect() }

    fun stop() { stopped = true; reconnectJob?.cancel(); ws?.close(1000, "bye"); ws = null }

    /** Force a fresh connection (e.g. network came back or server URL changed). */
    fun reconnectNow() { attempt = 0; ws?.cancel(); ws = null; reconnectJob?.cancel(); if (!stopped) connect() }

    private fun connect() {
        _state.value = LinkState.CONNECTING
        val url = urlProvider().replaceFirst("http", "ws") + "/ws"
        val req = try { Request.Builder().url(url).build() } catch (e: IllegalArgumentException) {
            _state.value = LinkState.OFFLINE; return
        }
        ws = Http.client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0; _state.value = LinkState.LIVE
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { JSONObject(text) }.getOrNull()?.let { _events.tryEmit(it) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = retry(webSocket)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = retry(webSocket)
        })
    }

    private fun retry(sock: WebSocket) {
        if (sock !== ws) return
        _state.value = LinkState.OFFLINE
        if (stopped) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(min(10_000L, 700L shl min(attempt++, 4)))
            if (!stopped) connect()
        }
    }
}
