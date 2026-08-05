package com.astrion.remote.ha

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One WebSocket per HA connection, per the protocol in the build brief section 4:
 * auth_required -> auth -> auth_ok -> get_states -> subscribe_events(state_changed),
 * with an app-level ping every 30s and exponential-backoff reconnect.
 */
class HaClient(
    val host: String,
    val token: String,
    private val scope: CoroutineScope
) {
    private val client = OkHttpClient.Builder().build()

    private var webSocket: WebSocket? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val eventHandlers = ConcurrentHashMap<Int, (JsonObject) -> Unit>()
    private var pingJob: Job? = null
    private var reconnectAttempt = 0
    private var manuallyClosed = false

    private val jsonFmt = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _entities = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    val entities: StateFlow<Map<String, EntityState>> = _entities

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val status: StateFlow<ConnectionStatus> = _status

    fun connect() {
        manuallyClosed = false
        val wsUrl = toWsUrl(host)
        _status.value = ConnectionStatus.Connecting
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, listener)
    }

    fun close() {
        manuallyClosed = true
        pingJob?.cancel()
        failPending("connection closed")
        webSocket?.close(1000, "bye")
        webSocket = null
    }

    fun callService(domain: String, service: String, entityId: String? = null, data: JsonObject? = null) {
        val id = nextId.getAndIncrement()
        val payload = buildJsonObject {
            put("id", id)
            put("type", "call_service")
            put("domain", domain)
            put("service", service)
            if (entityId != null) {
                putJsonObject("target") { put("entity_id", entityId) }
            }
            if (data != null) put("service_data", data)
        }
        send(payload)
    }

    /**
     * Fetch a forecast via weather.get_forecasts (return_response), per build brief section 4.7.
     * Forecast data lives in HA's response payload, not on the entity's state attributes.
     */
    suspend fun getForecast(entityId: String, type: String = "daily"): List<ForecastDay> {
        val result = sendAndAwait { id ->
            buildJsonObject {
                put("id", id)
                put("type", "call_service")
                put("domain", "weather")
                put("service", "get_forecasts")
                put("return_response", true)
                putJsonObject("target") { put("entity_id", entityId) }
                putJsonObject("service_data") { put("type", type) }
            }
        }
        val forecastArray = result["result"]?.jsonObject
            ?.get("response")?.jsonObject
            ?.get(entityId)?.jsonObject
            ?.get("forecast")?.jsonArray
            ?: return emptyList()
        return forecastArray.mapNotNull { el ->
            val o = el.jsonObject
            val datetime = o["datetime"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ForecastDay(
                datetime = datetime,
                temperature = o["temperature"]?.jsonPrimitive?.doubleOrNull,
                templow = o["templow"]?.jsonPrimitive?.doubleOrNull,
                condition = o["condition"]?.jsonPrimitive?.contentOrNull
            )
        }
    }

    /**
     * Start an Assist voice pipeline run (stt -> intent -> tts). Pipeline events for this
     * run are delivered to [onEvent]; audio is streamed with [sendAssistAudio] using the
     * stt_binary_handler_id from the run-start event. Returns the run's message id.
     */
    fun startAssist(sampleRate: Int, pipeline: String?, onEvent: (JsonObject) -> Unit): Int {
        val id = nextId.getAndIncrement()
        eventHandlers[id] = onEvent
        send(buildJsonObject {
            put("id", id)
            put("type", "assist_pipeline/run")
            put("start_stage", "stt")
            put("end_stage", "tts")
            if (pipeline != null) put("pipeline", pipeline)
            putJsonObject("input") { put("sample_rate", sampleRate) }
        })
        return id
    }

    fun endAssist(id: Int) {
        eventHandlers.remove(id)
    }

    /** Binary WS frame: first byte is the stt handler id, rest is 16-bit PCM audio. */
    fun sendAssistAudio(handlerId: Int, data: ByteArray, length: Int) {
        val framed = ByteArray(length + 1)
        framed[0] = handlerId.toByte()
        System.arraycopy(data, 0, framed, 1, length)
        webSocket?.send(framed.toByteString(0, framed.size))
    }

    /** Empty payload with just the handler id byte = end of audio stream. */
    fun endAssistAudio(handlerId: Int) {
        webSocket?.send(byteArrayOf(handlerId.toByte()).toByteString(0, 1))
    }

    private suspend fun sendAndAwait(build: (Int) -> JsonObject): JsonObject {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        send(build(id))
        return deferred.await()
    }

    private fun failPending(reason: String) {
        val stale = pending.keys.toList()
        stale.forEach { id -> pending.remove(id)?.completeExceptionally(IllegalStateException(reason)) }
    }

    private fun send(obj: JsonObject) {
        webSocket?.send(obj.toString())
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WS open")
            reconnectAttempt = 0
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WS closed: $code $reason")
            _status.value = ConnectionStatus.Disconnected
            pingJob?.cancel()
            failPending("connection closed")
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WS failure: ${t.message}")
            _status.value = ConnectionStatus.Error(t.message ?: "connection failed")
            pingJob?.cancel()
            failPending("connection failed")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (manuallyClosed) return
        reconnectAttempt++
        val delayMs = (1000L * (1 shl minOf(reconnectAttempt, 5))).coerceAtMost(30_000L)
        scope.launch {
            delay(delayMs)
            if (!manuallyClosed) connect()
        }
    }

    private fun handleMessage(text: String) {
        // Any exception escaping onMessage kills the whole WebSocket (OkHttp treats it as
        // a stream failure), so one malformed/unexpected message must never propagate.
        try {
            handleMessageInner(text)
        } catch (e: Exception) {
            Log.w(TAG, "Error handling WS message: ${e.message}")
        }
    }

    private fun handleMessageInner(text: String) {
        val obj = try {
            jsonFmt.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            Log.w(TAG, "Bad WS message (unparseable)")
            return
        }
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "auth_required" -> send(buildJsonObject {
                put("type", "auth")
                put("access_token", token)
            })
            "auth_ok" -> {
                Log.i(TAG, "auth_ok")
                _status.value = ConnectionStatus.AuthOk
                requestStates()
                subscribeEvents()
                startPing()
            }
            "auth_invalid" -> {
                Log.w(TAG, "auth_invalid")
                _status.value = ConnectionStatus.Error("Auth invalid — check your token")
            }
            "result" -> handleResult(obj)
            "event" -> handleEvent(obj)
            "pong" -> { /* heartbeat ok */ }
        }
    }

    private fun requestStates() {
        scope.launch {
            try {
                val result = sendAndAwait { id ->
                    buildJsonObject {
                        put("id", id)
                        put("type", "get_states")
                    }
                }
                val success = result["success"]?.jsonPrimitive?.booleanOrNull ?: true
                if (!success) {
                    Log.w(TAG, "get_states failed")
                    return@launch
                }
                val resultArray = result["result"]?.jsonArray ?: return@launch
                val map = HashMap<String, EntityState>()
                for (el in resultArray) {
                    val e = el.jsonObject
                    val entityId = e["entity_id"]?.jsonPrimitive?.contentOrNull ?: continue
                    map[entityId] = EntityState(
                        entityId = entityId,
                        state = e["state"]?.jsonPrimitive?.contentOrNull ?: "",
                        attributes = e["attributes"]?.jsonObject ?: JsonObject(emptyMap()),
                        lastChanged = e["last_changed"]?.jsonPrimitive?.contentOrNull,
                        lastUpdated = e["last_updated"]?.jsonPrimitive?.contentOrNull
                    )
                }
                _entities.value = map
                Log.i(TAG, "Seeded ${map.size} entities")
                // Climate devices have a history of re-registering under new entity ids
                // (leaving the config pointing at a dead orphan) — log what HA actually has.
                map.keys.filter { it.startsWith("climate.") }.sorted().forEach {
                    Log.i(TAG, "climate entity: $it state=${map[it]?.state}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "get_states failed: ${e.message}")
            }
        }
    }

    private fun subscribeEvents() {
        val id = nextId.getAndIncrement()
        send(buildJsonObject {
            put("id", id)
            put("type", "subscribe_events")
            put("event_type", "state_changed")
        })
    }

    private fun handleResult(obj: JsonObject) {
        val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return
        pending.remove(id)?.complete(obj)
    }

    private fun handleEvent(obj: JsonObject) {
        val event = obj["event"] as? JsonObject ?: return
        // Assist pipeline (and other per-id subscriptions) route to their registered handler.
        val id = obj["id"]?.jsonPrimitive?.intOrNull
        if (id != null) {
            eventHandlers[id]?.let { handler ->
                handler(event)
                return
            }
        }
        if (event["event_type"]?.jsonPrimitive?.contentOrNull != "state_changed") return
        val data = event["data"] as? JsonObject ?: return
        // new_state is JSON null (not absent) when an entity is removed — as? handles both.
        val newState = data["new_state"] as? JsonObject
        if (newState == null) {
            val entityId = data["entity_id"]?.jsonPrimitive?.contentOrNull ?: return
            _entities.update { it - entityId }
            return
        }
        val entityId = newState["entity_id"]?.jsonPrimitive?.contentOrNull ?: return
        val updated = EntityState(
            entityId = entityId,
            state = newState["state"]?.jsonPrimitive?.contentOrNull ?: "",
            attributes = newState["attributes"]?.jsonObject ?: JsonObject(emptyMap()),
            lastChanged = newState["last_changed"]?.jsonPrimitive?.contentOrNull,
            lastUpdated = newState["last_updated"]?.jsonPrimitive?.contentOrNull
        )
        _entities.update { it + (entityId to updated) }
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(30_000)
                val id = nextId.getAndIncrement()
                send(buildJsonObject {
                    put("id", id)
                    put("type", "ping")
                })
            }
        }
    }

    private fun toWsUrl(httpHost: String): String {
        val trimmed = httpHost.trimEnd('/')
        val ws = when {
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            else -> "ws://$trimmed"
        }
        return "$ws/api/websocket"
    }

    companion object {
        private const val TAG = "AstrionHaClient"
    }
}
