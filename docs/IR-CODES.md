# IR codes — making the buttons drive *your* TV

The physical buttons send infrared through Home Assistant, not directly from the app:

```
button press → app → HA remote.send_command → your IR blaster → TV
```

So the codes in `default_dashboard.json` are **specific to the TV they were captured from**
(an LG). Yours will differ unless you also own an LG. This page explains the format, three
ways to get your own codes, and a starting table by brand.

---

## 1. What a code actually is

A hotkey with an IR payload looks like this:

```json
{
  "key": "UP",
  "service": "remote.send_command",
  "entityId": "remote.your_ir_blaster",
  "data": { "command": "38000,9000,4500,560,560,560,1690,..." }
}
```

That comma-separated string is **raw timing in microseconds**:

| Position | Meaning |
|---|---|
| `38000` | Carrier frequency in Hz (38 kHz is near-universal; Sony uses 40 kHz) |
| `9000,4500` | Header: 9 ms pulse, 4.5 ms space — the NEC protocol's "wake up" marker |
| then pairs | `560,560` = bit **0** · `560,1690` = bit **1** |
| final `560` | Stop pulse |

The 32 payload bits are sent **least-significant bit first** in this order:

```
address, ~address, command, ~command
```

The inverted copies are a checksum — that's how you can sanity-check a code you found online.

Some integrations accept an opaque **base64 handle** instead
(`"command": "iSYSD81RvYVjbR6r1tF7Sw=="`). Those refer to a code stored inside the
integration, and only work on the device that learned them — they are not portable, so
prefer raw timings when sharing configs.

## 2. Getting your codes — three routes

### Route A: learn them (best — guaranteed correct)

If your IR integration supports learning (Broadlink does; some vendor integrations expose
learned codes as base64 handles), call the service in HA's **Developer Tools → Actions**:

```yaml
action: remote.learn_command
target:
  entity_id: remote.your_ir_blaster
data:
  device: tv
  command: power
  command_type: ir
```

Point your *original* TV remote at the blaster and press the button when HA prompts. Repeat
per button. This is the only route that's guaranteed right for your exact model.

### Route B: generate from a code table

If you know the protocol and the address/command bytes, generate the raw string:

```bash
python tools/nec_to_raw.py 0x04 0x08                    # LG power
python tools/nec_to_raw.py 0x07 0x02 --samsung          # Samsung power
python tools/nec_to_raw.py 0x04 0x40 --name UP \
       --entity remote.your_ir_blaster                  # ready-to-paste config block
```

The tables in section 3 give you the address and command bytes.

### Route C: public databases

- [irdb](https://github.com/probonopd/irdb) — thousands of devices as CSV (protocol, device, function, code)
- [LIRC remote database](https://lirc-remotes.sourceforge.net/remotes-table.html) — raw timing files
- [Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB) — well-maintained, includes raw captures

Match on protocol + address, then feed the command byte to the generator above.

## 3. Code tables by brand

> **Verification status, honestly:** the LG d-pad/OK/back/home rows below were captured from a
> working LG TV and reproduce byte-for-byte through `tools/nec_to_raw.py`. Everything else
> comes from public code databases and **has not been tested on hardware** — treat it as a
> strong starting guess, not gospel. Wrong codes are harmless: the TV just ignores them.

### LG (NEC, 38 kHz, address `0x04`)

| Button | Command | Verified |
|---|---|---|
| Up | `0x40` | ✅ captured |
| Down | `0x41` | ✅ captured |
| Left | `0x07` | ✅ captured |
| Right | `0x06` | ✅ captured |
| OK / Enter | `0x44` | ✅ captured |
| Back | `0x28` | ✅ captured |
| Home | `0x7C` | ✅ captured |
| Power | `0x08` | from table |
| Volume + / − | `0x02` / `0x03` | from table |
| Mute | `0x09` | from table |
| Channel + / − | `0x00` / `0x01` | from table |
| Input / Source | `0x0B` | from table |
| Menu / Settings | `0x43` | from table |
| Exit | `0x5B` | from table |

### Samsung (NEC variant, 38 kHz, **4500/4500 header**, address `0x07`)

| Button | Command |
|---|---|
| Power | `0x02` |
| Volume + / − | `0x07` / `0x0B` |
| Mute | `0x0F` |
| Channel + / − | `0x12` / `0x10` |
| Up / Down | `0x60` / `0x61` |
| Left / Right | `0x65` / `0x62` |
| Enter | `0x68` |
| Return | `0x58` |
| Home | `0x79` |
| Source | `0x01` |

Use `--samsung` with the generator — Samsung's header is 4.5 ms/4.5 ms, not 9 ms/4.5 ms.

### Also NEC-based (try address values in this order)

| Brand | Typical address | Notes |
|---|---|---|
| Vizio | `0x04` | Frequently shares LG's address space |
| Hisense | `0x00`, `0x04` | Varies by year |
| TCL / Roku TV | `0x04`, `0x57` | Roku-branded models differ from TCL's own |
| Xiaomi / Mi TV | `0x04` | |
| Toshiba | `0x02` | |
| Sharp | `0x02` | Some models use a 15-bit variant |

### Different protocols entirely (the NEC generator won't work)

| Brand | Protocol | Why it's different |
|---|---|---|
| **Sony** | SIRC, **40 kHz** | 2400/600 header, 12/15/20-bit frames, no inverted checksum. TV power = device 1, command `0x15`. |
| **Philips** | RC5 / RC6, 36 kHz | Biphase encoding — bits are transitions, not pulse lengths. Includes a toggle bit that must flip between presses. |
| **Panasonic** | Kaseikyo, 37 kHz | 48-bit frames with a vendor ID. |

For these, use **Route A (learn)** or grab a pre-built raw timing string from a database —
don't try to generate them with the NEC tool.

## 4. Wiring codes to buttons

Once you have a raw string, drop it into `hotkeys` (tap) or `longHotkeys` (1.5 s hold):

```json
{
  "key": "VOLUME_UP",
  "service": "remote.send_command",
  "entityId": "remote.your_ir_blaster",
  "data": { "command": "38000,9000,4500,..." }
}
```

`key` is the *logical* name — check [HARDWARE.md](HARDWARE.md) to map your device's physical
buttons to those names. Push the config and reopen the app; no rebuild.

## 5. When nothing happens

| Check | How |
|---|---|
| Does HA send it at all? | Call `remote.send_command` from Developer Tools → Actions with the same payload. If that fails, it's HA/blaster, not the app. |
| Is the blaster entity alive? | On the HA100 the IR entity is dead unless the **stock vendor app is running** — see [HARDWARE.md](HARDWARE.md). This bites everyone once. |
| Is the code valid? | Check the checksum: byte 2 must be `~`byte 1, byte 4 must be `~`byte 3. |
| Right protocol? | A Sony TV will ignore perfectly-formed NEC frames all day. |
| Line of sight? | IR needs it. Test from a foot away, pointed straight at the panel. |
