#!/usr/bin/env python3
"""Bounded, read-only capture observation. Never presses a shutter or changes settings."""
import argparse
import json
from pathlib import Path
import queue
import re
import subprocess
import threading
import time
from prejpeg_observation import EVENT


def metric(detail, key):
    match = re.search(r'(?:^|\s)' + re.escape(key) + r'=(\d+)(?:\s|$)', detail)
    return int(match[1]) if match else None


class Session:
    def __init__(self, cutoff):
        self.cutoff, self.pid, self.run = cutoff, None, None
        self.events, self.shots = [], {}

    def feed(self, line):
        match = EVENT.match(line.strip())
        if not match:
            return None
        pid, elapsed, event, detail = match.groups()
        pid, elapsed, detail = int(pid), int(elapsed), detail or ''
        if elapsed < self.cutoff:
            return None
        if self.pid is None:
            run = re.search(r'(?:^|\s)run=(\S+)', detail)
            if event != 'prepare' or not run:
                return None
            self.pid, self.run = pid, run[1]
        if pid != self.pid:
            return None
        shot = metric(detail, 'shot') or 0
        row = {'pid': pid, 'elapsed_ms': elapsed, 'event': event, 'shot': shot, 'detail': detail}
        self.events.append(row)
        if shot:
            result = self.shots.setdefault(shot, {'shot': shot})
            if event == 'armed':
                origin = re.search(r'(?:^|\s)origin=(\S+)', detail)
                overlap = re.search(r'(?:^|\s)overlap_original=(true|false)(?:\s|$)', detail)
                if origin:
                    result['timing_origin'] = origin[1]
                if overlap:
                    result['overlap_original'] = overlap[1] == 'true'
            keys = {'first_frame': ('displayed_ms', 'shutter_to_displayed_ms'),
                    'next_ready': ('next_ready_ms', 'shutter_to_ready_ms'),
                    'original_saved': ('original_saved_ms', 'from_press_ms'),
                    'graded_saved': ('graded_saved_ms', 'from_press_ms')}
            if event in keys:
                target, source = keys[event]
                result[target] = metric(detail, source)
            if event in ('original_saved', 'graded_saved'):
                path = re.search(r'(?:^|\s)file=(\S+)', detail)
                if path:
                    result[event + '_file'] = path[1]
            if event == 'lut_done':
                result['lut_wall_ms'] = metric(detail, 'wall_ms')
                stats = re.search(r'\{.*\}', detail)
                if stats:
                    result['lut_stats'] = json.loads(stats[0])
            if event == 'payload_verification_skipped':
                result['full_verification'] = 'disabled'
            if event == 'pair_payloads_verified':
                result['full_verification'] = 'passed'
                result['verification_ms'] = metric(detail, 'after_display_ms')
        return row


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='192.168.5.218:5555')
    parser.add_argument('--shots', type=int, default=3)
    parser.add_argument('--seconds', type=int, default=900)
    parser.add_argument('--stop-event', choices=['source_reference_exported', 'pair_payloads_verified'],
                        help='Finish a one-shot diagnostic after this current-session event')
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--resume-result', type=Path,
                        help='Continue observing the same live run without reopening the camera or losing its cache')
    args = parser.parse_args()
    if args.shots < 1 or not 1 <= args.seconds <= 1800:
        parser.error('invalid observation bounds')
    args.out.mkdir(parents=True, exist_ok=False)
    def shell(command):
        return subprocess.check_output(['adb', '-s', args.serial, 'shell', command], timeout=8, text=True).strip()
    boot = shell('cat /proc/sys/kernel/random/boot_id')
    cutoff = int(float(shell('cat /proc/uptime').split()[0]) * 1000)
    session = Session(cutoff)
    if args.resume_result:
        prior = json.loads(args.resume_result.read_text())
        if prior['boot_after'] != boot or not prior['events'] or not prior['pid'] or not prior['run']:
            raise SystemExit('Cannot resume: boot changed or previous run metadata is incomplete.')
        session.cutoff = prior['events'][-1]['elapsed_ms'] + 1
        session.pid, session.run = prior['pid'], prior['run']
        session.events = list(prior['events'])
        session.shots = {row['shot']: dict(row) for row in prior['shots']}
        cutoff = session.cutoff
    process = subprocess.Popen(['adb', '-s', args.serial, 'logcat', '-v', 'threadtime',
                                'SonyPreJpeg:I', 'SonyReview:I', 'AndroidRuntime:E',
                                'libc:E', 'DEBUG:I', 'dalvikvm:E', 'System.err:W', '*:S'],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace')
    lines = queue.Queue()
    def read():
        for line in process.stdout:
            lines.put(line)
        lines.put(None)
    threading.Thread(target=read, daemon=True).start()
    start, reason, deadline = time.monotonic(), 'deadline', None
    print(json.dumps({'observing': True, 'boot': boot, 'cutoff': cutoff}), flush=True)
    try:
        with (args.out/'ANDROID_LIVE.txt').open('w') as log:
            while time.monotonic() - start < args.seconds:
                try:
                    line = lines.get(timeout=1)
                except queue.Empty:
                    if deadline and time.monotonic() >= deadline:
                        break
                    continue
                if line is None:
                    reason = 'logcat_disconnected'
                    break
                log.write(line)
                log.flush()
                row = session.feed(line)
                if not row:
                    continue
                if row['event'] in ('ready', 'first_frame', 'next_ready', 'failed', 'timeout', 'closed', 'source_reference_exported'):
                    print(json.dumps(row, ensure_ascii=False), flush=True)
                if args.stop_event and row['event'] == args.stop_event and deadline is None:
                    reason, deadline = row['event'], time.monotonic() + 2
                if row['event'] in ('failed', 'timeout', 'closed') and deadline is None:
                    reason, deadline = row['event'], time.monotonic() + 2
                if sum(s.get('next_ready_ms') is not None for s in session.shots.values()) >= args.shots and deadline is None:
                    reason, deadline = 'requested_shots_ready', time.monotonic() + 2
                if deadline and time.monotonic() >= deadline:
                    break
    finally:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        try:
            after = shell('cat /proc/sys/kernel/random/boot_id')
        except Exception as error:
            after = {'read_error': str(error)}
        result = {'reason': reason, 'boot_before': boot, 'boot_after': after,
                  'pid': session.pid, 'run': session.run,
                  'shots': list(session.shots.values()), 'events': session.events,
                  'shutter_triggered_by_observer': False}
        (args.out/'RESULT.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
        print(json.dumps({k:v for k,v in result.items() if k != 'events'}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
