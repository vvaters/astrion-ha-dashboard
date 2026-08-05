#!/usr/bin/env python3
"""
Convert an NEC infrared address + command into the raw timing string this
dashboard sends via Home Assistant's `remote.send_command`.

Most TV remotes (LG, Samsung*, Xiaomi, many others) use the NEC protocol:
a 9000us/4500us header, then 32 bits sent LSB-first as
  0 -> 560us pulse + 560us space
  1 -> 560us pulse + 1690us space
in the order address, ~address, command, ~command, then a final 560us pulse.

Usage:
    python nec_to_raw.py 0x04 0x08            # LG TV power
    python nec_to_raw.py 0x04 0x40 --name UP  # print as a config hotkey block

*Samsung TVs use a 4500/4500 header. Pass --samsung for that variant.

Verify before trusting: point the remote at the TV and test. Code tables in
docs/IR-CODES.md are a starting point, not a guarantee.
"""
import argparse
import json


def nec_raw(address: int, command: int, freq: int = 38000,
            header=(9000, 4500), pulse: int = 560,
            zero: int = 560, one: int = 1690) -> str:
    """Build the comma-separated raw timing string for one NEC frame."""
    payload = [address & 0xFF, (~address) & 0xFF, command & 0xFF, (~command) & 0xFF]
    out = [freq, header[0], header[1]]
    for byte in payload:
        for bit in range(8):                      # LSB first
            out.append(pulse)
            out.append(one if (byte >> bit) & 1 else zero)
    out.append(pulse)                             # stop bit
    return ",".join(str(v) for v in out)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("address", help="device address, e.g. 0x04 for LG TVs")
    p.add_argument("command", help="button command, e.g. 0x08 for power")
    p.add_argument("--name", help="wrap the output in a ready-to-paste hotkey block")
    p.add_argument("--entity", default="remote.YOUR_REMOTE_ENTITY",
                   help="the HA remote entity that blasts the code")
    p.add_argument("--samsung", action="store_true",
                   help="use Samsung's 4500/4500 header instead of NEC's 9000/4500")
    args = p.parse_args()

    header = (4500, 4500) if args.samsung else (9000, 4500)
    raw = nec_raw(int(args.address, 0), int(args.command, 0), header=header)

    if args.name:
        print(json.dumps({
            "key": args.name,
            "service": "remote.send_command",
            "entityId": args.entity,
            "data": {"command": raw},
        }, indent=2))
    else:
        print(raw)


if __name__ == "__main__":
    main()
