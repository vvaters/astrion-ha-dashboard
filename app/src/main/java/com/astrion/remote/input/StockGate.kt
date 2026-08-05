package com.astrion.remote.input

import android.content.Context

/**
 * Tracks whether the stock HaRemote app is allowed in the foreground. The firmware's
 * pull-down "refresh" button (and possibly other firmware paths) launches it uninvited;
 * KeyRescueService bounces back to our dashboard unless something granted a window here
 * (the once-per-boot IR-bridge kick, or the user's launcher button).
 */
object StockGate {
    private const val PREFS = "astrion"
    private const val KEY = "stock_allowed_until"

    fun allow(context: Context, durationMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY, System.currentTimeMillis() + durationMs).apply()
    }

    fun isAllowed(context: Context): Boolean =
        System.currentTimeMillis() <= context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY, 0L)
}
