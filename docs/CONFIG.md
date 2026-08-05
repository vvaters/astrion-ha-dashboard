# Configuration reference

Everything lives in one JSON file: `default_dashboard.json` (baked into the APK) which is
copied to `/sdcard/astrion/dashboard.json` on first launch. After that the sdcard copy wins
and is re-read every time the app resumes.

```bash
adb push dashboard.json /sdcard/astrion/dashboard.json   # then reopen the app
```

Unknown keys are ignored, so `_comment` fields are safe to leave in.

---

## Top level

```json
{
  "ha":       { "host": "http://homeassistant.local:8123", "token": "..." },
  "assist":   { "pipeline": null },
  "startPage": 1,
  "pages":     [ ... ],
  "hotkeys":   [ ... ],
  "longHotkeys": [ ... ],
  "keymap":    { "134": "LIGHT" }
}
```

| Key | Meaning |
|---|---|
| `ha.host` | Full URL including scheme and port. `http://` is fine on a LAN; `https://` works too. |
| `ha.token` | Long-lived access token. See [SETUP.md](SETUP.md#1-get-a-home-assistant-token). |
| `assist.pipeline` | Assist pipeline name for voice. `null` = HA's default pipeline. |
| `startPage` | Zero-based index of the page shown at launch. |
| `keymap` | Android keycode → logical key name. See [HARDWARE.md](HARDWARE.md). |

## Pages

```json
{
  "name": "Lights",
  "showTitle": true,
  "scroll": true,
  "cards": [ ... ]
}
```

| Key | Default | Meaning |
|---|---|---|
| `name` | required | Shown as the title, and the target for hotkey `"page"` jumps. |
| `showTitle` | `true` | Hide it when a card already dominates the page. |
| `scroll` | `true` | `false` = fixed page, content must fit. Use for floorplan/media pages. |

## Cards

Every card is `{"type": "...", "variant": null, "options": { ... }}`.

### `bubble_light`
Light row with brightness slider.

| Option | Meaning |
|---|---|
| `entity_id` | A single light or switch. |
| `entities` | *Or* a list — one row controlling several at once. |
| `name` | Display name. |

### `scene_grid`
Row of scene buttons.

| Option | Meaning |
|---|---|
| `scenes` | List of `{entity_id, name, color}`. `color` is `#AARRGGBB`. |
| `layout` | `"row"` or grid (default). |

### `climate`
Thermostat: setpoint, HVAC modes, presets, fan speeds. Fan lists that are percentage-style
(e.g. 5%–100% in steps) collapse into a slider automatically.

| Option | Meaning |
|---|---|
| `entity_id` | A `climate.*` entity. |
| `name` | Display name. |

### `cover`
Open/stop/close for blinds and curtains. Options: `entity_id`, `name`.

### `media_player`
Transport controls and artwork. Options: `entity_id`. Set `"variant": "full"` for the
large layout.

### `clock_weather`
Clock plus a three-day forecast. Option: `entity_id` (a `weather.*` entity).
Set `"variant": "compact"` to shrink it.

### `picture_elements` — the floorplan

```json
{
  "type": "picture_elements",
  "options": {
    "image": "/sdcard/astrion/floorplan.png",
    "full_bleed": true,
    "all_off": false,
    "elements": [
      { "entity_id": "light.floor_lamp", "x": 14, "y": 32 },
      { "entities": ["light.a", "light.b"], "icon_name": "lights", "x": 50, "y": 45 }
    ]
  }
}
```

| Option | Meaning |
|---|---|
| `image` | Path on the device. Push your own with `adb push`. |
| `full_bleed` | `true` = span the full screen width, no card margin. |
| `all_off` | `true` adds an "All off" button in the corner. |
| `elements` | The dots. |

Each element:

| Key | Meaning |
|---|---|
| `entity_id` / `entities` | One target, or several controlled by one dot. |
| `x`, `y` | **Percentages** of the image, `0`–`100`, from the top-left. |
| `icon_name` | `lights` (multi-light glyph, drawn ~10% larger), `coffee`, `tv`, `power`. Default is a bulb. |

Dots glow warm when any target is on. A multi-entity dot turns everything off if anything is
on, otherwise turns everything on — so it never flip-flops.

**Placing dots:** percentages are relative to the image, so the same numbers work if you swap
in a different render at a different resolution. Easiest method is to open the image, note
where each fixture sits as a percentage across and down, and iterate — it's a config push, so
each round trip is seconds.

### `button_grid`
Launcher tiles.

```json
{ "type": "button_grid", "options": { "buttons": [
  { "name": "Movie", "icon_name": "tv", "service": "scene.turn_on", "entity_id": "scene.movie" },
  { "name": "Media", "icon": "/sdcard/astrion/icons/plex.png", "service": "script.turn_on", "entity_id": "script.launch" }
]}}
```

`icon_name` uses a built-in glyph (`tv`, `spa`, …); `icon` points at your own PNG on the device.

### `plex`
Poster rows — On Deck and Recently Added — fetched straight from a Plex server. Tapping plays
on an HA media_player, falling back to opening the Plex app on the TV.

| Option | Meaning |
|---|---|
| `host` | `http://plex-server:32400` |
| `token` | Your `X-Plex-Token` ([how to find it](https://support.plex.tv/articles/204059436-finding-an-authentication-token-x-plex-token/)) |
| `play_entity` | The HA media_player for the Plex client |
| `media_entity` | The TV itself, for the `select_source` fallback |
| `source` | Source name to select on the TV. Default `"Plex"`. |

Leave `host`/`token` blank and the card shows a hint instead of failing.

---

## Hotkeys

`hotkeys` fire on tap; `longHotkeys` on a 1.5-second hold. Same shape:

```json
{ "key": "CUSTOM_1", "page": "Lights" }
{ "key": "CUSTOM_2", "service": "scene.turn_on", "entityId": "scene.movie" }
{ "key": "UP", "service": "remote.send_command", "entityId": "remote.ir_blaster",
  "data": { "command": "38000,9000,4500,..." } }
{ "key": "POWER", "device": "sleep" }
```

| Key | Meaning |
|---|---|
| `key` | Logical key name (see `keymap` in [HARDWARE.md](HARDWARE.md)). |
| `page` | Jump to the page with this `name`. |
| `service` | HA service as `domain.service`. |
| `entityId` | Target entity. |
| `data` | Extra service data — this is where IR payloads go. See [IR-CODES.md](IR-CODES.md). |
| `device` | Local action instead of an HA call. Supported: `"sleep"`. |

A key with no entry does nothing — which is how you disable a button you keep hitting by
accident.
