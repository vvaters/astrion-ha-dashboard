# Recovery

For when the remote has been factory reset, the battery died hard enough to corrupt storage,
or you just want a clean reinstall.

The APK is a **complete restore unit**: config, floorplan, and icons are baked in and rebuild
themselves on first launch. You need the APK and nothing else.

---

## One-click (Windows)

1. Build the APK if you don't have one:

   ```powershell
   .\gradlew.bat assembleRelease
   ```

2. Copy `app\build\outputs\apk\release\app-release.apk` into this folder, renamed to
   **`astrion-remote.apk`**.
3. On the remote, enable USB debugging (Settings → System → About phone → tap **Build
   number** 7×, then Developer options → **USB debugging**). Plug in USB and accept the prompt.
4. Double-click **`restore.bat`**.

The script finds ADB, installs, grants permissions, sets the app as launcher, enables the
Home-button rescue, disables the firmware's over-sensitive wake gestures, AOT-compiles, and
launches.

## Manual (any OS)

```bash
adb install -r astrion-remote.apk

adb shell pm grant com.astrion.remote android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant com.astrion.remote android.permission.WRITE_EXTERNAL_STORAGE
adb shell pm grant com.astrion.remote android.permission.RECORD_AUDIO
adb shell pm grant com.astrion.remote android.permission.WRITE_SECURE_SETTINGS

adb shell cmd package set-home-activity com.astrion.remote/.MainActivity
adb shell settings put secure enabled_accessibility_services com.astrion.remote/com.astrion.remote.input.KeyRescueService
adb shell settings put secure accessibility_enabled 1

adb shell settings put secure wake_gesture_enabled 0
adb shell settings put secure double_tap_to_wake 0

adb shell dpm set-active-admin com.astrion.remote/.input.SleepAdminReceiver
adb shell cmd package compile -m speed -f com.astrion.remote
adb shell am start -n com.astrion.remote/.MainActivity
```

## Keeping a spare

Keep a copy of your built APK **off the project folder** — an external drive or another
machine. If the device dies while your working tree is mid-edit, you want a known-good build
to fall back on.

⚠️ **Your built APK contains your Home Assistant token in plain text.** Treat it like a
password: don't upload it anywhere public, don't attach it to a GitHub release, don't share
it with someone you wouldn't hand your HA admin login to.

## After a factory reset

USB debugging is off again and must be re-enabled by hand on the touchscreen (step 3 above)
before ADB can reach the device. On the HA100 the USB-C port is inside the case — two screws.

## If the config is broken rather than missing

The app falls back to its baked-in config and shows an error banner. To replace just the
config without reinstalling:

```bash
adb push dashboard.json /sdcard/astrion/dashboard.json
```

To force a full re-seed, delete the folder and relaunch:

```bash
adb shell rm -rf /sdcard/astrion
adb shell am force-stop com.astrion.remote
adb shell am start -n com.astrion.remote/.MainActivity
```
