package com.astrion.remote.input

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pickup-to-wake with strict false-positive rejection. The v1 detector (single
 * accel jerk OR any proximity change, like the stock firmware's WakeupUtils)
 * constantly false-woke while the remote sat on the couch during movies —
 * cushion motion and blankets/feet passing over the prox sensor both fired it.
 *
 * Now the accelerometer (10Hz, screen-off only) must see *sustained* motion,
 * confirmed by a real tilt or a fresh proximity change; a sharp double-spike
 * shake always works as a manual override. See onAccelSample for the rules.
 *
 * Sensors are registered only while the screen is off, so this costs nothing
 * during use. Runs as a foreground service so the OS doesn't kill it.
 */
class PickupWakeService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private var accel: Sensor? = null
    private var prox: Sensor? = null

    private var lastMagnitude = -1f
    private var lastProx = -1f
    private var lastWakeAt = 0L
    private var armedAt = 0L

    // Strict pickup detection state (see onSensorChanged): recent jerk history,
    // a slow "resting" gravity baseline, and the last proximity-change time.
    private val jerkTimes = ArrayDeque<Long>()
    private var restGravity = floatArrayOf(0f, 0f, 0f)
    private var currGravity = floatArrayOf(0f, 0f, 0f)
    private var hasBaseline = false
    private var lastBigJerkAt = 0L
    private var proxChangedAt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> startSensors()
                Intent.ACTION_SCREEN_ON -> stopSensors()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        prox = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        startForeground(NOTIF_ID, buildNotification())
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        if (!powerManager.isInteractive) startSensors()
        // This service is always alive, so it also babysits the accessibility binding,
        // which the firmware randomly drops.
        healHandler.postDelayed(heal, HEAL_INTERVAL_MS)
        Log.i(TAG, "PickupWakeService running (accel=${accel != null}, prox=${prox != null})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startSensors() {
        // Ignore sensor noise for the first moment after arming — the act of the
        // screen turning off shouldn't immediately wake it again.
        armedAt = System.currentTimeMillis()
        lastMagnitude = -1f
        lastProx = -1f
        jerkTimes.clear()
        hasBaseline = false
        lastBigJerkAt = 0L
        proxChangedAt = 0L
        accel?.let { sensorManager.registerListener(this, it, SENSOR_PERIOD_US) }
        prox?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        Log.i(TAG, "Armed pickup detection")
    }

    private fun stopSensors() {
        sensorManager.unregisterListener(this)
        Log.i(TAG, "Disarmed pickup detection")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (System.currentTimeMillis() - armedAt < ARM_GRACE_MS) {
            // Still record baselines during the grace period.
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    lastMagnitude = magnitude(event)
                    for (i in 0..2) {
                        currGravity[i] = event.values[i]
                        restGravity[i] = event.values[i]
                    }
                    hasBaseline = true
                }
                Sensor.TYPE_PROXIMITY -> lastProx = event.values[0]
            }
            return
        }
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> onAccelSample(event)
            Sensor.TYPE_PROXIMITY -> {
                // Proximity never wakes on its own (a blanket or foot passing over
                // the sensor on the couch was a constant false trigger). It only
                // opens a short window in which sustained motion needs no tilt —
                // the hand covering the sensor while grabbing the remote.
                val v = event.values[0]
                val prev = lastProx
                lastProx = v
                if (prev >= 0 && v != prev) proxChangedAt = System.currentTimeMillis()
            }
        }
    }

    /**
     * Strict pickup detection — a couch is a noisy place, so single-sample jerks
     * must never wake the screen. Three ways to wake:
     *  1. Sustained motion AND a real tilt: ≥[SUSTAIN_HITS] jerky samples inside
     *     [SUSTAIN_WINDOW_MS] while gravity has rotated ≥[TILT_DEGREES]° away from
     *     the resting baseline. This is a hand lifting the remote.
     *  2. Sustained motion right after a proximity change ([PROX_CONFIRM_MS]):
     *     a hand covered the sensor while grabbing — allows a flat, tilt-less lift.
     *  3. Two sharp spikes (≥[SHAKE_THRESHOLD]) within [SHAKE_PAIR_MS]: a deliberate
     *     wrist flick, the guaranteed manual override. Cushion plops are damped and
     *     don't double-spike this hard.
     */
    private fun onAccelSample(event: SensorEvent) {
        val now = System.currentTimeMillis()
        val m = magnitude(event)
        val prev = lastMagnitude
        lastMagnitude = m

        // Track gravity: fast EMA for "now", slow EMA for the resting baseline
        // that only updates while the remote is still.
        for (i in 0..2) {
            currGravity[i] += GRAVITY_ALPHA * (event.values[i] - currGravity[i])
        }
        if (prev < 0) return
        val jerk = abs(m - prev)

        val quiet = jerkTimes.isEmpty() && jerk < QUIET_THRESHOLD
        if (!hasBaseline || quiet) {
            for (i in 0..2) {
                restGravity[i] += BASELINE_ALPHA * (currGravity[i] - restGravity[i])
            }
            hasBaseline = true
        }

        // Rolling window of jerky samples.
        if (jerk > JERK_THRESHOLD) jerkTimes.addLast(now)
        while (jerkTimes.isNotEmpty() && now - jerkTimes.first() > SUSTAIN_WINDOW_MS) {
            jerkTimes.removeFirst()
        }

        // Rule 3: deliberate shake.
        if (jerk > SHAKE_THRESHOLD) {
            if (now - lastBigJerkAt < SHAKE_PAIR_MS) {
                wake("shake (Δ=%.1f m/s²)".format(jerk))
                return
            }
            lastBigJerkAt = now
        }

        if (jerkTimes.size < SUSTAIN_HITS) return
        val proxRecent = now - proxChangedAt < PROX_CONFIRM_MS
        val tilted = hasBaseline && gravityAngleDeg() > TILT_DEGREES
        // Rules 1 and 2: sustained motion, confirmed by tilt or a proximity grab.
        if (tilted || proxRecent) {
            wake("pickup (hits=${jerkTimes.size}, tilt=%.0f°, prox=$proxRecent)".format(gravityAngleDeg()))
        }
    }

    /** Angle in degrees between the current gravity vector and the resting baseline. */
    private fun gravityAngleDeg(): Float {
        val dot = currGravity[0] * restGravity[0] + currGravity[1] * restGravity[1] + currGravity[2] * restGravity[2]
        val magC = sqrt(currGravity[0] * currGravity[0] + currGravity[1] * currGravity[1] + currGravity[2] * currGravity[2])
        val magR = sqrt(restGravity[0] * restGravity[0] + restGravity[1] * restGravity[1] + restGravity[2] * restGravity[2])
        if (magC < 0.1f || magR < 0.1f) return 0f
        val cos = (dot / (magC * magR)).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(cos).toDouble()).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @Suppress("DEPRECATION") // FULL_WAKE_LOCK is the only way to light the screen pre-API-27 turnScreenOn
    private fun wake(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastWakeAt < WAKE_DEBOUNCE_MS) return
        lastWakeAt = now
        Log.i(TAG, "Waking screen: $reason")
        val wl = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "astrion:pickupWake"
        )
        wl.acquire(1500)
    }

    private fun magnitude(event: SensorEvent): Float {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        return sqrt(x * x + y * y + z * z)
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID, "Pickup wake", NotificationManager.IMPORTANCE_MIN
        ).apply { description = "Keeps pickup-to-wake armed" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pickup wake armed")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    private val healHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val heal = object : Runnable {
        override fun run() {
            KeyRescueService.ensureBound(this@PickupWakeService)
            KeyRescueService.ensureFirmwareWakeGesturesOff(this@PickupWakeService)
            healHandler.postDelayed(this, HEAL_INTERVAL_MS)
        }
    }

    override fun onDestroy() {
        healHandler.removeCallbacksAndMessages(null)
        stopSensors()
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AstrionPickup"
        private const val NOTIF_ID = 42
        private const val CHANNEL_ID = "pickup_wake"
        private const val SENSOR_PERIOD_US = 100_000 // 10Hz — better pickup discrimination than stock's 5Hz
        private const val JERK_THRESHOLD = 0.7f      // m/s² change that counts as a "jerky" sample
        private const val QUIET_THRESHOLD = 0.3f     // below this the remote is considered at rest
        private const val SUSTAIN_HITS = 5           // jerky samples required inside the window
        private const val SUSTAIN_WINDOW_MS = 1_200L
        private const val TILT_DEGREES = 30f         // gravity rotation that counts as a real lift
        private const val PROX_CONFIRM_MS = 2_000L   // window after a prox change where tilt isn't required
        private const val SHAKE_THRESHOLD = 5.0f     // m/s² spike for the deliberate-shake override
        private const val SHAKE_PAIR_MS = 600L
        private const val GRAVITY_ALPHA = 0.25f      // fast EMA — follows the hand
        private const val BASELINE_ALPHA = 0.05f     // slow EMA — remembers how it was resting
        private const val ARM_GRACE_MS = 2_000L
        private const val WAKE_DEBOUNCE_MS = 3_000L
        private const val HEAL_INTERVAL_MS = 60_000L
    }
}
