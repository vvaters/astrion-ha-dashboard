package com.astrion.remote.input

import android.util.Log
import android.view.KeyEvent
import com.astrion.remote.config.DashboardConfig
import com.astrion.remote.config.HotkeyConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Intercepts physical remote key events before Android's default handling, per build brief
 * section 7. Every event is logged under tag "AstrionKey" (Learning Mode) regardless of whether
 * it's mapped, so `adb logcat | grep -i astrionkey` always shows keyCode/scanCode for unknown
 * buttons. Tap = key up within LONG_PRESS_MS. Long-press = held past LONG_PRESS_MS.
 *
 * Keys with no longHotkeys binding fire their tap action on every repeat event too, so
 * held D-pad/volume buttons behave like a normal remote (repeat while held) instead of only
 * firing once on release.
 */
class HardwareKeyRouter(
    private val scope: CoroutineScope,
    private var config: DashboardConfig,
    private val onNavigate: (String) -> Unit,
    private val onService: (HotkeyConfig) -> Unit
) {
    private val longPressJobs = mutableMapOf<Int, Job>()
    private val longPressFired = mutableMapOf<Int, Boolean>()

    fun updateConfig(newConfig: DashboardConfig) {
        config = newConfig
    }

    /** Returns true if this key was mapped and handled (caller should consume the event). */
    fun onKeyDown(event: KeyEvent): Boolean {
        logEvent(event, "DOWN")
        val logicalKey = resolveLogicalKey(event.keyCode) ?: return false
        val hasLongBinding = config.longHotkeys.any { it.key == logicalKey }

        if (event.repeatCount == 0) {
            longPressFired[event.keyCode] = false
            if (hasLongBinding) {
                longPressJobs[event.keyCode]?.cancel()
                longPressJobs[event.keyCode] = scope.launch {
                    delay(LONG_PRESS_MS)
                    longPressFired[event.keyCode] = true
                    config.longHotkeys.find { it.key == logicalKey }?.let { fire(it) }
                }
            } else {
                config.hotkeys.find { it.key == logicalKey }?.let { fire(it) }
            }
        } else if (!hasLongBinding && event.repeatCount % REPEAT_DIVIDER == 0) {
            // Native-style repeat: re-fire the tap action while held, same as a real remote.
            // Android emits repeats ~every 50ms; dividing gives a sane ~5/sec rate so the
            // IR blaster and HA aren't flooded with 20 commands a second.
            config.hotkeys.find { it.key == logicalKey }?.let { fire(it) }
        }
        return true
    }

    /** Returns true if this key was mapped and handled (caller should consume the event). */
    fun onKeyUp(event: KeyEvent): Boolean {
        logEvent(event, "UP")
        val logicalKey = resolveLogicalKey(event.keyCode) ?: return false
        longPressJobs.remove(event.keyCode)?.cancel()
        val wasLongPress = longPressFired[event.keyCode] == true
        val hasLongBinding = config.longHotkeys.any { it.key == logicalKey }

        if (!wasLongPress && hasLongBinding) {
            // Released before the long-press threshold: fire the tap action now.
            config.hotkeys.find { it.key == logicalKey }?.let { fire(it) }
        }
        longPressFired.remove(event.keyCode)
        return true
    }

    private fun fire(hotkey: HotkeyConfig) {
        if (hotkey.page != null) {
            onNavigate(hotkey.page)
        } else {
            onService(hotkey)
        }
    }

    private fun resolveLogicalKey(keyCode: Int): String? =
        config.keymap[keyCode.toString()] ?: DEFAULT_KEYMAP[keyCode]

    private fun logEvent(event: KeyEvent, phase: String) {
        Log.i(
            TAG,
            "$phase keyCode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)}) " +
                "scanCode=${event.scanCode} repeatCount=${event.repeatCount} " +
                "resolved=${resolveLogicalKey(event.keyCode) ?: "unmapped"}"
        )
    }

    companion object {
        private const val TAG = "AstrionKey"
        private const val LONG_PRESS_MS = 1500L
        private const val REPEAT_DIVIDER = 4

        /** Best-guess defaults for standard Android keycodes; override per-device via config "keymap". */
        val DEFAULT_KEYMAP: Map<Int, String> = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to "UP",
            KeyEvent.KEYCODE_DPAD_DOWN to "DOWN",
            KeyEvent.KEYCODE_DPAD_LEFT to "LEFT",
            KeyEvent.KEYCODE_DPAD_RIGHT to "RIGHT",
            KeyEvent.KEYCODE_DPAD_CENTER to "CENTER",
            KeyEvent.KEYCODE_ENTER to "CENTER",
            KeyEvent.KEYCODE_BACK to "BACK",
            KeyEvent.KEYCODE_VOLUME_UP to "VOLUME_UP",
            KeyEvent.KEYCODE_VOLUME_DOWN to "VOLUME_DOWN",
            KeyEvent.KEYCODE_VOLUME_MUTE to "MUTE",
            KeyEvent.KEYCODE_PAGE_UP to "PAGE_UP",
            KeyEvent.KEYCODE_PAGE_DOWN to "PAGE_DOWN",
            KeyEvent.KEYCODE_POWER to "POWER",
            KeyEvent.KEYCODE_CHANNEL_UP to "CHANNEL_UP",
            KeyEvent.KEYCODE_CHANNEL_DOWN to "CHANNEL_DOWN"
        )
    }
}
