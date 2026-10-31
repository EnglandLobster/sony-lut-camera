#!/usr/bin/env python3
"""Send a key or chord to the authorized Windows 11 VM via Parallels.

Default is a dry run. --send requires explicit user authorization for this
input transport. Observe the current guest screen before sending keys.
"""
import argparse
import json
import subprocess
import time

PRLCTL = "/Applications/Parallels Desktop.app/Contents/MacOS/prlctl"
VM = "{522d2494-e4f0-4e53-b77d-fe2460d58c6e}"
SCAN = {
    "esc": 0x01, "tab": 0x0f, "enter": 0x1c, "ctrl": 0x1d,
    "shift": 0x2a, "alt": 0x38, "space": 0x39, "f4": 0x3e,
    "a": 0x1e, "b": 0x30, "c": 0x2e, "d": 0x20, "e": 0x12,
    "f": 0x21, "g": 0x22, "h": 0x23, "i": 0x17, "j": 0x24,
    "k": 0x25, "l": 0x26, "m": 0x32, "n": 0x31, "o": 0x18,
    "p": 0x19, "q": 0x10, "r": 0x13, "s": 0x1f, "t": 0x14,
    "u": 0x16, "v": 0x2f, "w": 0x11, "x": 0x2d, "y": 0x15,
    "z": 0x2c, "numlock": 0x45, "kp8": 0x48, "kp2": 0x50,
    "kp4": 0x4b, "kp6": 0x4d, "kp5": 0x4c,
}


def sequence(chord):
    names = chord.lower().split("+")
    if len(names) != len(set(names)) or any(name not in SCAN for name in names):
        raise ValueError("Unknown or repeated key in chord")
    if any(name not in ("ctrl", "alt", "shift") for name in names[:-1]):
        raise ValueError("Only modifiers may precede the final key")
    return names


def emit(name, event):
    subprocess.run([PRLCTL, "send-key-event", VM, "--scancode",
                    str(SCAN[name]), "--event", event], check=True, timeout=5,
                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("chord", help="For example alt+o, tab, or esc")
    parser.add_argument("--send", action="store_true", help="Send to the pinned VM; otherwise print only")
    parser.add_argument("--hold-ms", type=int, default=80, help="Final key hold duration (1..3000 ms)")
    args = parser.parse_args()
    if not 1 <= args.hold_ms <= 3000:
        parser.error("--hold-ms must be between 1 and 3000")
    names = sequence(args.chord)
    events = [(name, "press") for name in names] + [(name, "release") for name in reversed(names)]
    print(json.dumps({"vm": VM, "transport": "Parallels virtual keyboard", "send": args.send,
                      "events": [{"key": n, "scancode": SCAN[n], "event": e} for n, e in events]}), flush=True)
    if not args.send:
        return
    held = []
    try:
        for name in names:
            # A host timeout may occur after the guest receives the event.
            held.append(name)
            emit(name, "press")
            time.sleep(args.hold_ms / 1000 if name == names[-1] else 0.08)
    finally:
        failures = []
        for name in reversed(held):
            try:
                emit(name, "release")
            except Exception as error:
                failures.append(f"{name}: {error}")
        if failures:
            raise RuntimeError("Unable to release keys: " + "; ".join(failures))


if __name__ == "__main__":
    main()
