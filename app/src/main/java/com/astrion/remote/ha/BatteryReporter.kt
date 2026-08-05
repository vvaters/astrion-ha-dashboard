package com.astrion.remote.ha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Pushes the remote's own battery level to HA as sensor.astrion_remote_battery
 * via POST /api/states/ (the WebSocket API has no state-set command). A dead
 * battery once corrupted /sdcard and forced a factory reset — this exists so HA
 * can warn before that happens again.
 *
 * Reports every [INTERVAL_MS] and immediately on charger plug/unplug. The
 * entity is stateless on the HA side (vanishes on HA restart) but reappears at
 * the next report, so automations should key on state, not availability.
 */
class BatteryReporter(
    private val context: Context,
    private val host: String,
    private val token: String,
    private val scope: CoroutineScope
) {
    private val client = OkHttpClient.Builder().build()
    private var job: Job? = null
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (job != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                kick.trySend(Unit)
            }
        }.also {
            context.registerReceiver(it, IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            })
        }
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    report()
                } catch (e: Exception) {
                    Log.w(TAG, "battery report failed: ${e.message}")
                }
                // Wait out the interval, but let a charger event cut it short.
                withTimeoutOrNull(INTERVAL_MS) { kick.receive() }
            }
        }
    }

    fun stop() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
        job?.cancel()
        job = null
    }

    private fun report() {
        // Sticky broadcast: null receiver returns the last battery state immediately.
        val batt = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batt.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale
        val status = batt.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val body = buildJsonObject {
            put("state", pct.toString())
            putJsonObject("attributes") {
                put("unit_of_measurement", "%")
                put("device_class", "battery")
                put("friendly_name", "Astrion Remote Battery")
                put("charging", charging)
            }
        }.toString()

        val request = Request.Builder()
            .url("${host.trimEnd('/')}/api/states/sensor.astrion_remote_battery")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) {
                Log.i(TAG, "reported $pct% (charging=$charging) HTTP ${resp.code}")
            } else {
                Log.w(TAG, "battery report HTTP ${resp.code}")
            }
        }
    }

    private companion object {
        const val TAG = "BatteryReporter"
        const val INTERVAL_MS = 5 * 60_000L
    }
}
