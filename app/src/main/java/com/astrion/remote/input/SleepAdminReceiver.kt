package com.astrion.remote.input

import android.app.admin.DeviceAdminReceiver

/**
 * Empty device-admin receiver: being an active admin with the force-lock policy is
 * what lets the app turn the screen off (DevicePolicyManager.lockNow) — the remote's
 * power button is a generic keycode, not a real POWER key, so the OS won't do it.
 * Activated once via: adb shell dpm set-active-admin com.astrion.remote/.input.SleepAdminReceiver
 */
class SleepAdminReceiver : DeviceAdminReceiver()
