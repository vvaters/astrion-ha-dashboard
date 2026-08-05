# Architecture

Kotlin + Jetpack Compose, one WebSocket to Home Assistant, one JSON config. No Lovelace, no
WebView, no vendor cloud.

---

## Map

```
app/src/main/java/com/astrion/remote/
  MainActivity.kt          activity, theme, dispatchKeyEvent, IR-bridge kick,
                           accessibility self-heal, sleep-on-hold-power
  ha/
    HaClient.kt            WebSocket: auth → get_states → subscribe_events;
                           callService; getForecast; Assist audio; reconnect w/ backoff
    BatteryReporter.kt     pushes device battery to HA via POST /api/states/
    HaJsonExt.kt           str()/double()/int()/stringList()/rgbColor() helpers
    HaModels.kt            EntityState, ConnectionStatus, ForecastDay
  config/
    ConfigModels.kt        serializable config classes
    ConfigRepository.kt    loads config, self-seeds /sdcard/astrion/ on first launch
  input/
    HardwareKeyRouter.kt   keycode → logical key → hotkey / longHotkey (tap vs 1.5s hold)
    KeyRescueService.kt    accessibility: Home-button rescue, stock-app bounce, 4s sweep
    PickupWakeService.kt   strict pickup-to-wake; babysits the accessibility binding
    StockGate.kt           temporary permission for intentional stock-app launches
    SleepAdminReceiver.kt  device-admin receiver so hold-power can sleep the screen
  voice/
    AssistManager.kt       tap-to-talk, silence detection, STT → LLM → TTS
    VoiceOverlay.kt        the listening UI
  ui/
    Dashboard.kt           HorizontalPager + page dots; per-page scroll/title; full_bleed
    NetworkImage.kt        authenticated image loading + LRU cache
    cards/CardRegistry.kt  type string → composable
    cards/*.kt             one file per card type

app/src/main/res/raw/
  default_dashboard.json   the config, baked in and self-seeded to /sdcard
  floorplan.png            demo floorplan (replace with your own)
  icon_plex.png            neutral launcher icon
```

## How data flows

```
HaClient (WebSocket)
   │  auth → get_states → subscribe_events(state_changed)
   ▼
StateFlow<Map<String, EntityState>>      ← single source of truth
   │
   ▼
Dashboard (Compose) → CardRegistry → individual cards
   │
   └── taps → haClient.callService(domain, service, entityId, data)
```

One client, one entity map, one subscription. Cards are pure functions of
`(config, entities)` — they hold no state of their own beyond animation and caches.

## The config lifecycle

1. `default_dashboard.json` is compiled into the APK as a raw resource.
2. On first launch `ConfigRepository` copies it to `/sdcard/astrion/dashboard.json`, along
   with `floorplan.png` and `icons/plex.png`. **Existing files are never overwritten**, so
   your customizations survive app updates.
3. Every `onResume` re-reads the sdcard copy. Push a new file, reopen the app, done.
4. If the JSON fails to parse, the app shows an error banner and falls back to the baked
   copy rather than dying.

This is why the APK is a complete restore unit: sideload it onto a wiped device and it
rebuilds its whole world on first launch.

## Adding a card type

Two steps:

1. Write `ui/cards/MyCard.kt`:

   ```kotlin
   @Composable
   fun MyCard(card: CardConfig, entities: Map<String, EntityState>, haClient: HaClient) {
       val entityId = card.options.str("entity_id") ?: return
       val state = entities[entityId]
       // ...
   }
   ```

2. Register it in `CardRegistry.kt`:

   ```kotlin
   "my_card" -> MyCard(card, entities, haClient)
   ```

Then use `{"type": "my_card", "options": {...}}` in the config. Read options through the
`HaJsonExt` helpers (`str`, `double`, `stringList`) — they tolerate missing keys and JSON
nulls, which HA sends more often than you'd expect.

## Input routing

`MainActivity.dispatchKeyEvent` intercepts before Compose sees anything (the framework's
keyboard-navigation would otherwise steal the d-pad). `HardwareKeyRouter` translates the
raw keycode through `keymap`, then decides tap versus 1.5-second hold and looks the action up
in `hotkeys` / `longHotkeys`.

Actions are one of: `page` (navigate), `service` + `entityId` + `data` (call HA), or
`device` (local action such as `sleep`).

## Staying alive

This firmware fights you. Three mechanisms push back:

- **`KeyRescueService`** (accessibility) — bounces the stock app when the firmware launches
  it, and rescues the Home button.
- **`PickupWakeService`** (foreground service) — always running, so it doubles as the watchdog
  that re-binds accessibility and re-disables the firmware's wake gestures every 60 s.
- **Launcher role** — set the app as home activity so reboots and crashes land back on the
  dashboard rather than the vendor UI.

## Wake detection

Naive motion detection false-wakes constantly on a couch. The detector requires evidence:

- **Sustained motion:** ≥5 jerky accelerometer samples within 1.2 s at 10 Hz, **and**
- **A real tilt:** gravity rotated ≥30° from a slowly-updated resting baseline,
- **or** sustained motion within 2 s of a proximity change (a hand grabbing it),
- **or** two sharp spikes within 600 ms — a deliberate shake, the manual override.

Proximity alone never wakes the screen; blankets and passing feet trigger it constantly.
Sensors register only while the screen is off, so it costs nothing during use.

All thresholds are constants at the bottom of `PickupWakeService.kt`.
