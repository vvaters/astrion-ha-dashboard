# Setup

From a factory remote to a working dashboard. Budget an hour the first time.

---

## 0. What you need

- The remote (Sanytron Astrion HA100 or similar Android device)
- **JDK 17** and the **Android SDK** (Android Studio installs both)
- **ADB** ([platform-tools](https://developer.android.com/tools/releases/platform-tools))
- A **USB cable**. On the HA100 the USB-C port is **inside the case** — two screws on the back.
- Home Assistant reachable on your LAN

---

## 1. Get a Home Assistant token

In HA: click your user (bottom left) → **Security** tab → **Long-lived access tokens** →
**Create token**. Copy it now; HA shows it exactly once.

Read the security note in the [README](../README.md#security) before using your admin
account — this token is full control of your HA.

## 2. Enable USB debugging on the remote

Settings → System → About phone → tap **Build number** seven times → back → **Developer
options** → **USB debugging** on. Plug in USB and accept the "Allow USB debugging?" prompt
on the remote's screen.

Confirm your computer sees it:

```bash
adb devices
```

You want a line ending in `device`. If it says `unauthorized`, accept the prompt on the remote.

> 💡 **On the HA100 the USB port is inside the case, and the case screws strip easily.** While
> you have it open, run `adb tcpip 5555` to enable ADB over Wi-Fi so later installs don't
> need the screwdriver. Setup and caveats: [HARDWARE.md](HARDWARE.md#set-up-wireless-adb-the-first-time-you-have-it-open).

## 3. Put your details in the config

Edit `app/src/main/res/raw/default_dashboard.json`. At minimum, replace:

```json
"ha": {
  "host": "http://YOUR_HA_HOST:8123",
  "token": "YOUR_LONG_LIVED_ACCESS_TOKEN"
}
```

Then replace the placeholder entity IDs (`light.living_room`, `scene.relax`, …) with your own.
Full reference: **[CONFIG.md](CONFIG.md)**. You can also start minimal — one page, one light —
and grow it later without rebuilding.

## 4. Build

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleRelease
```

Windows PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
$env:ANDROID_HOME = "$env:USERPROFILE\AndroidSdk"
.\gradlew.bat assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`. Takes 2–6 minutes.

> **Always `assembleRelease`.** The debug build is unusably slow on this hardware.

If Gradle reports bogus "unresolved reference" errors after editing several files, Kotlin's
incremental compiler has gone stale: `./gradlew clean assembleRelease`.

## 5. Install

```bash
adb install -r app/build/outputs/apk/release/app-release.apk

# These two are NOT optional — see the warning below
adb shell pm grant com.astrion.remote android.permission.WRITE_SECURE_SETTINGS
adb shell settings put secure enabled_accessibility_services com.astrion.remote/com.astrion.remote.input.KeyRescueService
adb shell settings put secure accessibility_enabled 1

# Ahead-of-time compile — makes a real difference on this chip
adb shell cmd package compile -m speed -f com.astrion.remote

adb shell am start -n com.astrion.remote/.MainActivity
```

> ⚠️ **Every `adb install -r` silently disables the accessibility service.** The Home-button
> rescue and stock-app bounce stop working until you re-run those commands. The app also
> self-heals about 5 seconds after launch (that's what `WRITE_SECURE_SETTINGS` is for), but
> run them anyway.

On Windows, `recovery/restore.bat` does this whole sequence in one double-click.

## 6. Make it the launcher (optional but recommended)

So the dashboard *is* the device, and comes back after every reboot:

```bash
adb shell cmd package set-home-activity com.astrion.remote/.MainActivity
```

Hold-power-to-sleep needs device-admin:

```bash
adb shell dpm set-active-admin com.astrion.remote/.input.SleepAdminReceiver
```

## 7. Verify

The dashboard should open showing live states. If it doesn't:

```bash
adb logcat -s AstrionHaClient:* AstrionPickup:*
```

| Symptom | Cause |
|---|---|
| "Auth invalid — check your token" | Token wrong, or copied with whitespace |
| Nothing connects, no error | Wrong host/port, or the remote is on a different subnet than HA |
| Card shows "No image at /sdcard/…" | Floorplan didn't seed — reinstall, or push the PNG manually |
| Buttons do nothing | Your keycodes differ — see [HARDWARE.md](HARDWARE.md#finding-your-keycodes) |

---

## Making changes afterwards

Most edits need **no rebuild**:

```bash
adb push dashboard.json /sdcard/astrion/dashboard.json
```

…then reopen the app. The config is re-read on every resume. Rebuild only for new card
*types* or Kotlin behavior changes.

Custom images live alongside it:

```bash
adb push my-floorplan.png /sdcard/astrion/floorplan.png
adb push my-icon.png      /sdcard/astrion/icons/plex.png
```

Existing files are never overwritten by the app, so your customizations survive updates.
