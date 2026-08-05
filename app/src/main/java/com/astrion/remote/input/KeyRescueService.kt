package com.astrion.remote.input

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.astrion.remote.MainActivity

/**
 * Global rescue hatch for the physical Home button (this remote emits keyCode 131 / F1,
 * not Android's real HOME, so the OS can't handle it and other apps just swallow it).
 *
 * While ANY other app is foreground, pressing that button returns to the Astrion
 * dashboard. While our own app is foreground, the event is passed through untouched so
 * MainActivity's HardwareKeyRouter keeps sending the TV IR home command.
 *
 * Enable once via adb (survives reboots):
 *   adb shell settings put secure enabled_accessibility_services com.astrion.remote/com.astrion.remote.input.KeyRescueService
 *   adb shell settings put secure accessibility_enabled 1
 */
class KeyRescueService : AccessibilityService() {

    @Volatile
    private var foregroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        Log.i(TAG, "KeyRescueService connected")
        // Periodic sweep: the window-event bounce misses the case where the stock app
        // fronted during a grace window and simply stays put after it expires.
        sweepHandler.postDelayed(sweep, SWEEP_INTERVAL_MS)
    }

    private val sweepHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sweep = object : Runnable {
        override fun run() {
            if (foregroundPackage == STOCK_APP && !StockGate.isAllowed(this@KeyRescueService)) {
                val now = System.currentTimeMillis()
                if (now - lastBounceAt > BOUNCE_DEBOUNCE_MS) {
                    lastBounceAt = now
                    Log.i(TAG, "Sweep: stock app lingering past its window — bouncing")
                    startActivity(
                        Intent(this@KeyRescueService, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                }
            }
            sweepHandler.postDelayed(this, SWEEP_INTERVAL_MS)
        }
    }

    override fun onDestroy() {
        running = false
        sweepHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { pkg ->
                foregroundPackage = pkg
                // The firmware's pull-down "refresh" button launches the stock HaRemote
                // uninvited. Bounce back to the dashboard unless a grant is active
                // (boot-time IR-bridge kick, or the user's own launcher button).
                if (pkg == STOCK_APP && !StockGate.isAllowed(this)) {
                    val now = System.currentTimeMillis()
                    if (now - lastBounceAt > BOUNCE_DEBOUNCE_MS) {
                        lastBounceAt = now
                        Log.i(TAG, "Stock app fronted uninvited — bouncing back to dashboard")
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        )
                    }
                }
            }
        }
    }

    @Volatile
    private var lastBounceAt = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != RESCUE_KEYCODE) return false
        val inOurApp = foregroundPackage == packageName
        if (inOurApp) return false // let MainActivity handle it (TV IR home)

        // Consume both DOWN and UP so the foreground app never reacts; act on UP.
        if (event.action == KeyEvent.ACTION_UP) {
            Log.i(TAG, "Rescue: Home pressed in $foregroundPackage — returning to dashboard")
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
        return true
    }

    override fun onInterrupt() {}

    companion object {
        /** True while the system has this accessibility service bound. */
        @Volatile
        var running = false
            private set

        private const val TAG = "AstrionRescue"
        private const val RESCUE_KEYCODE = 131 // this remote's physical Home button (F1)
        private const val STOCK_APP = "com.aiks.HaRemote"
        private const val BOUNCE_DEBOUNCE_MS = 3_000L
        private const val SWEEP_INTERVAL_MS = 4_000L

        /**
         * Re-toggles the accessibility setting to force a rebind (this firmware randomly
         * drops it). Needs WRITE_SECURE_SETTINGS, granted once over adb. Safe to call
         * repeatedly; does nothing while the service is healthy.
         */
        fun ensureBound(context: android.content.Context) {
            if (running) return
            try {
                val resolver = context.contentResolver
                val svc = "${context.packageName}/${KeyRescueService::class.java.name}"
                android.provider.Settings.Secure.putString(
                    resolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, ""
                )
                android.provider.Settings.Secure.putString(
                    resolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, svc
                )
                android.provider.Settings.Secure.putInt(
                    resolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1
                )
                Log.i(TAG, "Re-toggled accessibility setting to rebind service")
            } catch (e: SecurityException) {
                Log.w(TAG, "Service unbound and WRITE_SECURE_SETTINGS not granted")
            }
        }

        /**
         * The firmware's own wake gestures (kernel-level motion/prox, reason=rmt:screen)
         * are hair-trigger sensitive and false-woke the screen constantly on the couch.
         * PickupWakeService is the sole wake path now; keep the firmware's disabled in
         * case a reboot or factory state turns them back on.
         */
        fun ensureFirmwareWakeGesturesOff(context: android.content.Context) {
            try {
                val resolver = context.contentResolver
                for (key in listOf("wake_gesture_enabled", "double_tap_to_wake")) {
                    if (android.provider.Settings.Secure.getInt(resolver, key, 0) != 0) {
                        android.provider.Settings.Secure.putInt(resolver, key, 0)
                        Log.i(TAG, "Disabled firmware $key")
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot disable firmware wake gestures — WRITE_SECURE_SETTINGS not granted")
            }
        }
    }
}
