#!/usr/bin/env python3
"""Summarize an exported REPORT.ZIP without extracting files or executing content."""
import argparse
import json
import re
import zipfile
from collections import defaultdict
from pathlib import Path

MAX_TOTAL = 2 * 1024 * 1024
MEMBER = re.compile(r"(?:internal|sd)/(?:CAP[0-5]|UI[0-1])\.TXT\Z")


def analyze(path):
    sessions = defaultdict(dict)
    malformed = 0
    summary = ""
    empty = []
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if len(entries) > 17 or sum(e.file_size for e in entries) > MAX_TOTAL:
            raise ValueError("diagnostic archive exceeds size/member limit")
        for entry in entries:
            if entry.filename != "SUMMARY.TXT" and not MEMBER.fullmatch(entry.filename):
                raise ValueError("unexpected archive member: " + entry.filename)
            if entry.file_size > 65536:
                raise ValueError("diagnostic member exceeds limit")
            text = archive.read(entry).decode("utf-8", errors="replace")
            if entry.filename != "SUMMARY.TXT" and entry.file_size == 0:
                empty.append(entry.filename)
            if entry.filename == "SUMMARY.TXT":
                summary = text
                continue
            for line in text.splitlines():
                try:
                    event = json.loads(line)
                    if (event.get("schema") != 1 or not isinstance(event.get("session"), str)
                            or not isinstance(event.get("seq"), int)
                            or not isinstance(event.get("event"), str)
                            or not isinstance(event.get("detail"), str)):
                        raise ValueError("invalid event")
                    sessions[event["session"]][event["seq"]] = event
                except (ValueError, TypeError, AttributeError):
                    malformed += 1
    result = []
    for session, records in sessions.items():
        events = [records[key] for key in sorted(records)]
        failures = [e for e in events if e["event"] in ("failed", "capture_interrupted")]
        closed = any(e["event"] == "closed" for e in events)
        role = events[-1].get("role", "unknown")
        outcome = "error recorded" if failures else "closed" if closed else "no close recorded" if role == "CAP" else "UI activity"
        result.append({"session": session, "role": role, "outcome": outcome,
                       "header": next((e["detail"] for e in events if e["event"] == "session_header"), ""),
                       "events": len(events), "last_event": events[-1], "failures": failures,
                       "capture_parameters": [e["detail"] for e in events if e["event"] == "camera_parameters"],
                       "save_events": [e for e in events if e["event"] in ("original_saved", "graded_saved", "pair_catalogued", "next_ready")],
                       "previous_session": next((e["detail"] for e in events if e["event"] == "previous_session"), ""),
                       "power_events": [e for e in events if e["event"] in POWER_EVENTS or e["event"].startswith("activity_")]})
    return {"summary": summary, "malformed_lines": malformed, "empty_members": empty, "sessions": result}


POWER_EVENTS = ("system_suspend", "system_resume", "screen_off", "screen_on", "stale_capture_process", "capture_restart")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    result = analyze(args.report)
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print(result["summary"].strip())
        print("\nSessions:")
        for session in result["sessions"]:
            last = session["last_event"]
            print(f"- {session['role']} {session['session']}: {session['outcome']}; {session['events']} events; last={last['event']}")
            if session["previous_session"]:
                print("  before it: " + session["previous_session"])
            for event in session["power_events"]:
                if not event["event"].startswith("activity_resume"):
                    print(f"  {event.get('uptime_ms', '?')} {event['event']} {event['detail']}")
            for error in session["failures"]:
                print("  " + error["detail"].splitlines()[0])
        if result["empty_members"]:
            print("0-byte journals (open when the camera cold-booted; versions before 2026-11-17 kept journals open): "
                  + ", ".join(result["empty_members"]))
        if result["malformed_lines"]:
            print(f"Partial/invalid lines: {result['malformed_lines']}")
        print("No close recorded means incomplete evidence, not necessarily a crash.")


if __name__ == "__main__":
    main()
