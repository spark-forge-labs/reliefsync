package com.sparkforge.reliefsync.audio

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

const val MAX_SECONDS = 30

/**
 * Edge recording engine: 30 s AAC voice note (16 kHz mono, 32 kbps ≈ 120 KB) — small enough to
 * squeeze through degraded links and to queue offline. Emits a normalised amplitude for the waveform.
 */
class VoiceRecorder(private val ctx: Context, private val scope: CoroutineScope) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var meter: Job? = null
    val level = MutableStateFlow(0f)
    val elapsed = MutableStateFlow(0f)

    fun start(): Boolean = try {
        val f = File(ctx.filesDir, "sos-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(16_000)
        r.setAudioEncodingBitRate(32_000)
        r.setMaxDuration(MAX_SECONDS * 1000)
        r.setOutputFile(f.absolutePath)
        r.prepare(); r.start()
        rec = r; file = f
        val t0 = System.currentTimeMillis()
        meter = scope.launch {
            while (isActive) {
                val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
                level.value = (amp / 22_000f).coerceIn(0f, 1f)
                elapsed.value = (System.currentTimeMillis() - t0) / 1000f
                delay(60)
            }
        }
        true
    } catch (e: Exception) {
        release(); false
    }

    /** Stops and returns the encoded file (null if it failed / was too short). */
    fun stop(): File? {
        meter?.cancel()
        val f = file
        val ok = runCatching { rec?.stop() }.isSuccess
        release()
        level.value = 0f
        return if (ok && f != null && f.length() > 1024) f else { f?.delete(); null }
    }

    private fun release() { runCatching { rec?.release() }; rec = null }
}

/**
 * Live speech-to-text. Uses the fully on-device recognizer on Android 12+ when available
 * (works with zero connectivity), else the platform recognizer. Exposes partial results and RMS.
 */
class LiveTranscriber(private val ctx: Context) {
    private var sr: SpeechRecognizer? = null
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text
    val level = MutableStateFlow(0f)
    val listening = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private var committed = ""
    private var keepGoing = false
    var onDevice = false; private set

    fun available() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start() {
        committed = ""; _text.value = ""; error.value = null; keepGoing = true
        listenOnce()
    }

    private fun listenOnce() {
        sr?.destroy()
        sr = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            onDevice = true; SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        } else {
            onDevice = false; SpeechRecognizer.createSpeechRecognizer(ctx)
        }
        sr?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { listening.value = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) { level.value = ((rms + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(code: Int) {
                // Silence timeouts: keep listening until the user taps stop (or 30 s cap).
                if (keepGoing && (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    listenOnce(); return
                }
                listening.value = false
                if (code != SpeechRecognizer.ERROR_CLIENT) error.value = "Speech recognizer error $code"
            }
            override fun onResults(b: Bundle?) {
                val best = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                committed = listOf(committed, best).filter { it.isNotBlank() }.joinToString(" ")
                _text.value = committed
                if (keepGoing) listenOnce() else listening.value = false
            }
            override fun onPartialResults(b: Bundle?) {
                val p = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                _text.value = listOf(committed, p).filter { it.isNotBlank() }.joinToString(" ")
            }
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        }
        sr?.startListening(intent)
    }

    fun stop(): String {
        keepGoing = false
        runCatching { sr?.stopListening() }
        listening.value = false
        level.value = 0f
        return _text.value
    }

    fun destroy() { keepGoing = false; sr?.destroy(); sr = null }
}
