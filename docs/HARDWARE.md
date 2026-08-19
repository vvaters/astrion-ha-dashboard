# Hardware notes and gotchas

Everything here cost real debugging time on a **Sanytron Astrion HA100** (Android 8.1,
MediaTek MT6580, 1 GB RAM, 480×800). If you have a different remote, the principles hold but
the specifics — especially keycodes — will differ.

---

## Physical access

The **USB-C port is inside the case**: two screws on the back. There's no external data port,
which means you cannot flash or debug the device without opening it. After a factory reset,
USB debugging has to be re-enabled by hand on the touchscreen before ADB works again.

> ⚠️ **The case screws strip easily.** They are tiny, soft, and shallow — several openings is
> enough to round one out, and then you are drilling or gluing. Use a properly fitting
> precision driver (PH000 / JIS 000), push down hard while turning slowly, and do not
> over-torque on the way back in. If you plan to develop against this thing, consider
> replacing the screws with fresh ones early, while they still come out cleanly.

### Set up wireless ADB the first time you have it open

Because opening the case is the expensive part, spend one USB session enabling ADB over
Wi-Fi. With the remote connected by USB:

```bash
adb tcpip 5555
```

Find its IP (or read it from the remote's Wi-Fi settings screen):

```bash
adb shell ip -f inet addr show wlan0
```

Then unplug, close it up, and connect over the network:

```bash
adb connect 192.168.1.50:5555
```

From then on every `adb install`, `adb push`, and `adb shell screencap` works wirelessly —
no screws.

**Making it permanent (rooted units only).** Some HA100 units ship with `su` available —
check with `adb shell "su 0 id"`. If that returns `uid=0(root)`, one property makes wireless
ADB survive reboots for good:

```bash
adb shell "su 0 setprop persist.adb.tcp.port 5555"
```

**One honest limitation:**

- **Without root it does not survive a reboot.** `adb tcpip` restarts the ADB daemon in TCP
  mode, and a reboot puts it back to USB-only. In practice you still get wireless access
  until the next reboot — usually plenty, since the app is what you're iterating on.
- **Both machines must be able to reach each other on the network.** If your laptop is on a
  different subnet from the remote (common when a router hands out `192.168.1.x` while your
  IoT gear sits on `192.168.86.x`), `adb connect` will simply time out. Check with
  `ipconfig` / `ip addr` on the laptop against the remote's Wi-Fi settings screen.

This device runs Android 8.1, which predates the "Wireless debugging" pairing screen added in
Android 11 — so the `adb tcpip` route above is the only option, not the newer QR pairing.

## Keycodes

Confirmed against this device's firmware:

| Key | Code | | Key | Code |
|---|---|---|---|---|
| D-pad up/down/left/right | 19 / 20 / 21 / 22 | | Home | **131** |
| OK | 23 | | Power | **132** |
| Back | 4 | | Voice | **133** |
| Volume +/− | 24 / 25 | | Light | 134 |
| Channel +/− | 92 / 93 | | Curtain | 135 |
| Mute | 164 | | Scene | 136 |
| | | | AC | 137 |
| | | | Colour row | 138–141 |

Codes 131–141 are **non-standard** — that's why the config has a `keymap` section translating
raw codes into logical names:

```json
"keymap": { "131": "HOME", "134": "LIGHT", "138": "CUSTOM_1" }
```

Logical names the router understands: `UP` `DOWN` `LEFT` `RIGHT` `CENTER` `BACK` `HOME`
`POWER` `VOICE` `SETTINGS` `VOLUME_UP` `VOLUME_DOWN` `MUTE` `PAGE_UP` `PAGE_DOWN`
`CHANNEL_UP` `CHANNEL_DOWN` `LIGHT` `CURTAIN` `SCENE` `AC` `CUSTOM_1`–`CUSTOM_4`.

### Finding your keycodes

```bash
adb shell getevent -l
```

Press each button and watch the output. Or filter the app's own logging:

```bash
adb logcat -s AstrionKeys:*
```

Map whatever you see into `keymap` and the rest of the config works unchanged.

---

## The gotchas

### 1. Every reinstall kills the accessibility service
`adb install -r` silently disables it. The Home-button rescue and stock-app bounce stop
working until re-enabled. Re-run the two `settings put secure` commands from
[SETUP.md](SETUP.md#5-install) after **every** install. The app self-heals ~5 s after launch
if it has `WRITE_SECURE_SETTINGS`, and re-checks every 60 s.

### 2. The stock vendor app is the IR driver
On this device `com.aiks.HaRemote` owns the IR hardware. Home Assistant's `remote.*` entity
for the blaster is **dead unless that app has run since boot**. This app kicks it once per
boot and then reclaims the foreground.

**Never uninstall the stock app.** You will lose IR entirely and it's not obvious why.

### 3. The pull-down "refresh" button launches the stock app
A firmware shortcut that yanks you out of the dashboard. `KeyRescueService` bounces you back
(event-driven, plus a 4-second sweep). `StockGate` grants temporary passes when *we* launch
the stock app on purpose.

### 4. The firmware has its own hair-trigger wake gestures
Hidden kernel-level motion wake, enabled by default and invisible in the UI. Turn both off:

```bash
adb shell settings put secure wake_gesture_enabled 0
adb shell settings put secure double_tap_to_wake 0
```

The app re-applies these every 60 seconds, because a reboot can bring them back. Our own
pickup detection (`PickupWakeService`) requires *sustained* motion plus a real tilt, so it
doesn't fire on cushion wobble.

If the screen still wakes on its own after this, the cause is the vendor app rather than the
firmware — see gotcha 11, which is the one that actually fixed it here.

### 5. Physical d-pad presses used to scroll the page
Android's keyboard-navigation mode was focusing cards and stealing the d-pad.
Fixed with `FOCUS_BLOCK_DESCENDANTS` on the decor view — don't re-enable focus.

### 6. Compose animations must use `rememberCoroutineScope()`
Never a background scope — you get `MonotonicFrameClock is not available` crashes.

### 7. Any exception in `HaClient.onMessage` kills the WebSocket
All parsing is wrapped in try/catch. Use `as? JsonObject` rather than `.jsonObject`, because
HA sends JSON nulls that will throw.

### 8. Kotlin incremental compilation goes stale
After scripted multi-file edits you get bogus "unresolved reference" errors.
`./gradlew clean assembleRelease` fixes it.

### 9. Some LG Plex clients ignore seek/offset
Playback always starts from the beginning regardless of resume position. Four approaches were
tested, including Plex's own companion protocol. It's a client-side limitation — a different
endpoint (Shield, Apple TV) behaves correctly.

### 10. Mic buttons may emit instant press+release
On this device the voice button sends press and release together, so hold-to-talk is
impossible. Hence tap-to-talk with client-side silence detection.

### 11. The vendor app runs its own pickup-wake — and it overrides yours
`com.aiks.HaRemote` runs as the **system user**, so its wakes log as
`uid=1000 reason=rmt:screen`. It has a private preference enabling its own accelerometer
pickup-wake, and it is far more trigger-happy than anything in this app: with it on, the
screen turns on constantly while the remote sits untouched on a table or couch, no matter
how strict `PickupWakeService` is. It is invisible from the UI and survives everything
except editing the preference directly. **Requires root:**

```bash
PREF=/data/data/com.aiks.HaRemote/shared_prefs/DisplaySettings.xml
adb shell "su 0 cp $PREF $PREF.bak"
adb shell am force-stop com.aiks.HaRemote
adb shell "su 0 sed -i 's/name=\"wakeup_enabled\" value=\"true\"/name=\"wakeup_enabled\" value=\"false\"/' $PREF"
```

**Editing the file is not enough — the app writes the setting back.** It reverted here within
hours. Lock the file read-only so it cannot (`chattr` is absent on this build, so use mode
bits):

```bash
adb shell "su 0 chown root:root $PREF; su 0 chmod 444 $PREF"
```

Verified to survive a full vendor-app restart with IR still working. It is not absolutely
bulletproof — the app owns the directory, so it could in principle delete and recreate the
file — so re-check the value if phantom wakes ever return.

Restart the vendor app afterwards (`adb shell cmd package resolve-activity --brief
com.aiks.HaRemote` gives the launcher activity) — it is the IR driver and must keep running.

Confirm the fix by leaving the device asleep and untouched for ten minutes:

```bash
adb logcat -d -b all | grep "Waking up from sleep"
```

Anything reading `reason=rmt:screen` is the vendor app. Wakes from this app read
`reason=astrion:pickupWake`; the power button reads `android.policy:POWER`.

The same app also registers an `RTC_WAKEUP` alarm, `ACTION_DAILY_UPDATE_CHECK`, which phones
home to the vendor. It re-registers on launch and has no off switch, but in testing it only
turned the screen on when it actually found an update. Note that this app also caches your
Home Assistant configuration and credentials locally.

---

## Performance

1 GB of RAM and a 2014-era SoC. What matters:

- **Always build release.** Debug is ~14× slower here — it feels like a broken device.
- **AOT compile after install:** `adb shell cmd package compile -m speed -f com.astrion.remote`
- Decode images once and cache them; re-decoding the floorplan on every swipe caused visible jank.
- Keep the entity map flat and avoid recomposing whole pages on every state event.

## Battery

The remote reports its own battery to HA as `sensor.astrion_remote_battery` (every 5 minutes
and on charger plug/unplug). Build an automation on it — a fully dead battery on this hardware
can corrupt storage badly enough to force a factory reset.

The entity is stateless: it disappears if HA restarts and reappears at the next report. Key
automations on the numeric state, not on availability.
