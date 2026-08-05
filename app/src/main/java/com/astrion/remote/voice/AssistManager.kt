package com.astrion.remote.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import com.astrion.remote.ha.HaClient
import com.astrion.remote.ha.int
import com.astrion.remote.ha.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

sealed class VoiceState {
    data object Idle : VoiceState()
    data object Listening : VoiceState()
    data class Thinking(val transcript: String?) : VoiceState()
    data class Answer(val transcript: String?, val speech: String?) : VoiceState()
    data class Error(val message: String) : VoiceState()
}

/**
 * Hold-to-talk voice sessions against HA's Assist pipeline (stt -> intent -> tts).
 * startListening() begins a pipeline run and streams mic audio; stopListening() ends the
 * audio, then HA transcribes (faster-whisper), runs the conversation agent, and returns
 * TTS (piper) which is played through the remote's speaker.
 */
class AssistManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    @Volatile
    var client: HaClient? = null

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state

    private var runId = -1
    private var sttHandlerId = 1
    private var audioJob: Job? = null
    private var watchdogJob: Job? = null

    @Volatile
    private var capturing = false

    /** True from button-down to button-up; run-start may arrive after release (quick taps). */
    @Volatile
    private var wantAudio = false
    private var transcript: String? = null
    private var speechText: String? = null
    private var responseType: String? = null
    private var ttsUrl: String? = null
    private var mediaPlayer: MediaPlayer? = null

    fun startListening(pipeline: String?) {
        val c = client ?: run {
            _state.value = VoiceState.Error("Not connected to Home Assistant")
            return
        }
        if (_state.value is VoiceState.Listening || _state.value is VoiceState.Thinking) return

        stopPlayback()
        transcript = null
        speechText = null
        responseType = null
        ttsUrl = null
        wantAudio = true
        _state.value = VoiceState.Listening
        runId = c.startAssist(SAMPLE_RATE, pipeline) { event -> onPipelineEvent(event) }

        // Safety net: never leave the UI stuck if HA stops emitting events.
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(45_000)
            if (_state.value is VoiceState.Listening || _state.value is VoiceState.Thinking) {
                fail("Voice request timed out")
            }
        }
    }

    fun stopListening() {
        wantAudio = false
        capturing = false // audio loop sends the end-of-stream marker in its finally block
    }

    fun dismiss() {
        stopPlayback()
        watchdogJob?.cancel()
        wantAudio = false
        capturing = false
        _state.value = VoiceState.Idle
    }

    private fun onPipelineEvent(event: JsonObject) {
        // "data" can be JSON null for some pipeline events — safe-cast everything.
        val data = event["data"] as? JsonObject
        when (event.str("type")) {
            "run-start" -> {
                sttHandlerId = (data?.get("runner_data") as? JsonObject)?.int("stt_binary_handler_id") ?: 1
                startCapture()
            }
            "stt-end" -> {
                transcript = (data?.get("stt_output") as? JsonObject)?.str("text")
                _state.value = VoiceState.Thinking(transcript)
            }
            "intent-end" -> {
                val response = (data?.get("intent_output") as? JsonObject)
                    ?.get("response") as? JsonObject
                responseType = response?.str("response_type")
                speechText = (((response?.get("speech") as? JsonObject)
                    ?.get("plain") as? JsonObject)
                    ?.str("speech"))
            }
            "tts-end" -> {
                ttsUrl = (data?.get("tts_output") as? JsonObject)?.str("url")
            }
            "run-end" -> {
                watchdogJob?.cancel()
                client?.endAssist(runId)
                when (responseType) {
                    // Action executed: the device changing state is the confirmation.
                    // No popup lingering, no spoken "Turned on the light".
                    "action_done" -> _state.value = VoiceState.Idle
                    "error" -> {
                        _state.value = VoiceState.Error(speechText ?: "Couldn't do that")
                        scheduleAutoDismiss()
                    }
                    // Questions: show and speak the answer.
                    else -> {
                        _state.value = VoiceState.Answer(transcript, speechText)
                        ttsUrl?.let { playTts(it) }
                        scheduleAutoDismiss()
                    }
                }
            }
            "error" -> {
                fail(data?.str("message") ?: "Voice pipeline error")
            }
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested at app startup
    private fun startCapture() {
        if (!wantAudio) {
            // Button already released before HA was ready (quick tap): end the audio
            // stage immediately instead of leaving the mic running.
            client?.endAssistAudio(sttHandlerId)
            return
        }
        capturing = true
        audioJob = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, CHUNK_BYTES * 2)
                )
            } catch (e: Exception) {
                fail("Microphone unavailable: ${e.message}")
                return@launch
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                fail("Microphone failed to initialize")
                return@launch
            }
            var totalBytes = 0
            try {
                recorder.startRecording()
                Log.i(TAG, "Audio capture started (handler=$sttHandlerId, recState=${recorder.recordingState})")
                val buf = ByteArray(CHUNK_BYTES) // ~100ms of 16kHz 16-bit mono
                // Tap-to-talk with client-side silence detection: this remote's mic button
                // emits an instant press+release, so we can't use hold-to-talk. Record until
                // ~1.2s of silence after speech began, 10s hard cap, or a second tap.
                var elapsedMs = 0
                var speechHeard = false
                var silenceMs = 0
                while (capturing && isActive) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n > 0) {
                        totalBytes += n
                        client?.sendAssistAudio(sttHandlerId, buf, n)
                        elapsedMs += n / 32 // 32 bytes per ms at 16kHz 16-bit mono
                        val rms = rms(buf, n)
                        if (rms > SPEECH_RMS) {
                            speechHeard = true
                            silenceMs = 0
                        } else if (speechHeard) {
                            silenceMs += n / 32
                        }
                        if ((speechHeard && silenceMs >= END_SILENCE_MS) || elapsedMs >= MAX_UTTERANCE_MS) {
                            Log.i(TAG, "Auto-stop: speech=$speechHeard silence=${silenceMs}ms total=${elapsedMs}ms")
                            break
                        }
                    } else if (n < 0) {
                        Log.w(TAG, "AudioRecord.read error: $n")
                        break
                    }
                }
            } finally {
                try {
                    recorder.stop()
                } catch (ignored: Exception) {
                }
                recorder.release()
                client?.endAssistAudio(sttHandlerId)
                Log.i(TAG, "Audio capture ended, sent $totalBytes bytes (~${totalBytes / 32}ms)")
            }
        }
    }

    private fun playTts(path: String) {
        val c = client ?: return
        val url = if (path.startsWith("http")) path else c.host.trimEnd('/') + path
        try {
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setDataSource(context, Uri.parse(url), mapOf("Authorization" to "Bearer ${c.token}"))
            mp.setOnPreparedListener { it.start() }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "TTS playback error $what/$extra")
                true
            }
            mp.setOnCompletionListener {
                it.release()
                if (mediaPlayer === it) mediaPlayer = null
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "TTS playback failed: ${e.message}")
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.let {
            try {
                it.stop()
                it.release()
            } catch (ignored: Exception) {
            }
        }
        mediaPlayer = null
    }

    private fun fail(message: String) {
        Log.w(TAG, "Assist error: $message")
        watchdogJob?.cancel()
        capturing = false
        client?.endAssist(runId)
        _state.value = VoiceState.Error(message)
        scheduleAutoDismiss()
    }

    private fun scheduleAutoDismiss() {
        scope.launch {
            delay(3_000)
            if (_state.value is VoiceState.Answer || _state.value is VoiceState.Error) {
                _state.value = VoiceState.Idle
            }
        }
    }

    private fun rms(buf: ByteArray, len: Int): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < len) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toLong() * sample
            i += 2
        }
        val samples = len / 2
        return if (samples == 0) 0 else Math.sqrt(sum.toDouble() / samples).toInt()
    }

    companion object {
        private const val TAG = "AstrionAssist"
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_BYTES = 3_200
        private const val SPEECH_RMS = 700
        private const val END_SILENCE_MS = 1_200
        private const val MAX_UTTERANCE_MS = 10_000
    }
}
