#!/usr/bin/env python3
"""One explicitly selected probe with bounded host-side observation.

No shutter, retries, reboot, or device process kills. The caller must review
each result before selecting another stage. Scalar and JPEG encoder logging
temporarily use INFO, then restore the levels read before launch.
Existing logs are kept. Observation follows this run's events, so waiting for
the physical shutter does not consume the capture's diagnostic window.
Initialization is the default. --capture permits the accepted Fine control or
a fresh EXTRA FINE control/pair using the now-verified reference memory map.
The failed firmware00 EXTRA FINE and paired combinations stay blocked.
--fresh-session checks process absence without starting a prior init-only pass.
"""
import argparse
import json
from pathlib import Path
import queue
import socket
import subprocess
import threading
import time
import uuid

from collect_link_state import sanitize
from prejpeg_observation import ObservationWindow, filter_levels


def snapshot_pending_store(host, pid, path):
    """Read current buffer ownership/queues while the blocked call still exists."""
    commands = [
        'cat /proc/uptime',
        'cat /proc/sys/kernel/random/boot_id',
        'cat /proc/udm/dmm_info',
        'cat /proc/udm/dmm_units',
        'cat /proc/udm/dmm_units2',
        'cat /proc/udm/dmm_extents',
        'cat /proc/udm/dmm_local_que',
        'cat /proc/udm/dmm_global_que',
        'cat /proc/udm/dmm_reserve',
        'cat /proc/udm/dmm_log',
        f'cat /proc/{int(pid)}/task/*/wchan',
    ]
    marker = 'PREJPEG_SNAPSHOT_END'
    # This firmware shell has echo, but neither printf nor the od applet.
    command = '; '.join(commands + ['echo', 'echo ' + marker])
    received = bytearray()
    with socket.create_connection((host, 23), timeout=3) as connection:
        connection.settimeout(.2)
        connection.sendall((command + '\n').encode())
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and len(received) < 2 * 1024 * 1024:
            try:
                chunk = connection.recv(65536)
            except socket.timeout:
                continue
            if not chunk:
                break
            received.extend(chunk)
            # The shell also echoes the command: require a standalone result.
            if ('\n' + marker + '\r\n').encode() in received or (
                    '\n' + marker + '\n').encode() in received:
                break
    path.write_text(sanitize(received.decode('utf-8', 'replace')))
    return {'commands': commands, 'bytes': len(received),
            'complete': marker in received.decode('utf-8', 'replace').splitlines()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='192.168.5.218')
    parser.add_argument('--stage', choices=['defaults', 'configured'], default='defaults')
    parser.add_argument('--phase', choices=['preview', 'handoff', 'develop', 'develop-xfine', 'lut'], default='handoff')
    parser.add_argument('--map-revision', choices=['firmware00', 'reference01'], default='firmware00')
    parser.add_argument('--normalize-zoom', action='store_true')
    parser.add_argument('--capture', action='store_true',
                        help='Arm the accepted Fine control; never triggers a shutter')
    parser.add_argument('--fresh-session', action='store_true',
                        help='Require absent LUT process before a single-image capture')
    parser.add_argument('--restore-smart-zoom', action='store_true',
                        help='Restore the recorded original smart-zoom setting in preview only')
    parser.add_argument('--wait-shutter-seconds', type=int, default=600,
                        help='Bounded wait after ready; capture then has its own 40-second limit')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.capture and (args.stage != 'configured' or
            (args.phase != 'develop' and not (
                args.phase in ('develop-xfine', 'lut') and args.map_revision == 'reference01' and args.fresh_session))):
        parser.error('--capture requires Fine develop or fresh EXTRA FINE/reference01; firmware00 EXTRA FINE and pairs remain blocked')
    if args.fresh_session and not args.capture:
        parser.error('--fresh-session requires --capture (no preceding init-only pass)')
    if args.restore_smart_zoom and (args.capture or args.phase != 'preview'):
        parser.error('--restore-smart-zoom requires init-only preview')
    if not 30 <= args.wait_shutter_seconds <= 1800:
        parser.error('--wait-shutter-seconds must be 30..1800')
    args.out.mkdir(parents=True, exist_ok=False)
    base = ['adb', '-s', args.host + ':5555']
    record = {'stage': args.stage, 'phase': args.phase, 'normalize_zoom': args.normalize_zoom, 'init_only': not args.capture, 'fresh_session': args.fresh_session, 'restore_smart_zoom': args.restore_smart_zoom, 'auto_shutter': False,
              'map_revision': args.map_revision,
              'probe_token': ('capture-' if args.capture else 'init-') + str(uuid.uuid4()), 'commands': []}

    def adb(command, timeout=12):
        cmd = base + command
        try:
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
            r = {'command': cmd, 'rc': p.returncode,
                 'stdout': sanitize(p.stdout), 'stderr': sanitize(p.stderr)}
        except subprocess.TimeoutExpired:
            r = {'command': cmd, 'timeout': timeout}
        record['commands'].append(r)
        (args.out / 'RESULT.json').write_text(json.dumps(record, indent=2))
        return r

    state = adb(['get-state'])
    if state.get('rc') or state.get('stdout', '').strip() != 'device':
        raise SystemExit('Device not ready; no probe launched')
    record['boot_before'] = adb(['shell', 'cat', '/proc/sys/kernel/random/boot_id'])
    if args.fresh_session:
        processes = adb(['shell', 'ps'])
        if processes.get('rc') != 0 or not processes.get('stdout', '').strip():
            raise SystemExit('Unable to verify fresh process; no probe launched')
        names = [line.split()[-1] for line in processes['stdout'].splitlines() if line.split()]
        if any(name == 'org.hongen.sonylutprobe' or name.startswith('org.hongen.sonylutprobe:') for name in names):
            raise SystemExit('LUT process already exists; close and stop it before this explicit control')
    sock = socket.create_connection((args.host, 23), timeout=5)
    sock.settimeout(.2)
    native = (args.out / 'NATIVE_LIVE.txt').open('x')
    total = 0
    pending_native = ''
    native_limit = 32 * 1024 * 1024

    def native_read(seconds):
        nonlocal total, pending_native
        collected = []
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            try:
                chunk = sock.recv(65536)
            except socket.timeout:
                continue
            if not chunk:
                raise ConnectionError('native terminal closed')
            total += len(chunk)
            if total > native_limit:
                raise OSError('native observation exceeded 32 MiB; stopping')
            decoded = chunk.decode('utf-8', 'replace')
            collected.append(decoded)
            # Sanitize complete lines; a credential token can cross TCP chunks.
            pending_native += decoded
            lines = pending_native.split('\n')
            pending_native = lines.pop()
            if lines:
                native.write(sanitize('\n'.join(lines)))
            native.flush()
        return ''.join(collected)

    logcat = None
    prior_levels = {}
    changed_filters = []
    events = queue.Queue()
    try:
        native_read(.3)
        sock.sendall(b'ulogio\n')
        native_read(.3)
        sock.sendall(b'filter info\n')
        prior_levels = filter_levels(native_read(.5), (0x150, 0x55))
        record['native_filter_levels_before'] = {hex(k): v for k, v in prior_levels.items()}
        for subsystem in prior_levels:
            # Register restoration before sending: an interrupted send may apply.
            changed_filters.append(subsystem)
            sock.sendall(f'filter on {subsystem:#x} INFO\n'.encode())
            native_read(.25)
        for command in ['filter info', 'term on', 'rewind']:
            sock.sendall((command + '\n').encode())
            native_read(.25)
        logcat = subprocess.Popen(base + ['logcat', '-v', 'threadtime', '*:I'],
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, errors='replace')

        def read_logcat():
            with (args.out / 'ANDROID_LIVE.txt').open('x') as output:
                for line in logcat.stdout:
                    output.write(sanitize(line))
                    output.flush()
                    if 'SonyPreJpeg:' in line:
                        events.put((time.monotonic(), line))

        reader = threading.Thread(target=read_logcat, daemon=True)
        reader.start()
        uptime = adb(['shell', 'cat /proc/uptime'])
        if uptime.get('rc') != 0:
            raise ValueError('cannot read uptime; no probe launched')
        cutoff_ms = int(float(uptime['stdout'].split()[0]) * 1000)
        record['event_cutoff_ms'] = cutoff_ms
        window = ObservationWindow(cutoff_ms, time.monotonic(), args.capture,
                                   args.wait_shutter_seconds)
        print('Observation ready; launching one ' + ('user-shutter ' + args.phase if args.capture else 'init-only') + ' ' + args.stage + ' control', flush=True)
        record['launch'] = adb(['shell', 'am', 'start', '-W', '-n',
            'org.hongen.sonylutprobe/.PreJpegProbeActivity', '--es', 'phase', args.phase,
            '--es', 'map_revision', args.map_revision,
            '--ez', 'init_only', 'false' if args.capture else 'true', '--es', 'init_stage', args.stage,
            '--ez', 'normalize_zoom', 'true' if args.normalize_zoom else 'false',
            '--ez', 'fresh_session', 'true' if args.fresh_session else 'false',
            '--ez', 'restore_smart_zoom', 'true' if args.restore_smart_zoom else 'false',
            '--es', 'probe_token', record['probe_token']])
        record['probe_events'] = []
        while True:
            native_read(.2)
            while not events.empty():
                at, line = events.get_nowait()
                event = window.feed(line, at)
                if event:
                    record['probe_events'].append(event)
            now = time.monotonic()
            if window.needs_snapshot(now):
                try:
                    record['pending_store_snapshot'] = snapshot_pending_store(
                        args.host, window.pid, args.out / 'DMM_PENDING.txt')
                except OSError as exc:
                    record['pending_store_snapshot_error'] = str(exc)
            reason = window.end_reason(now)
            if logcat.poll() is not None:
                reason = 'android_log_disconnected'
            if reason:
                record['observation_end'] = reason
                break
        record['native_connected_at_end'] = True
    except (OSError, ValueError) as exc:
        record['observer_error'] = str(exc)
    finally:
        # A full capture buffer must not prevent restoring logging afterwards.
        native_limit = total + 2 * 1024 * 1024
        try:
            restore = ['term off'] + [
                f'filter on {key:#x} {prior_levels[key]}' for key in changed_filters
            ] + ['filter info', 'exit']
            record['logging_restore_commands'] = restore
            restore_response = ''
            for command in restore:
                sock.sendall((command + '\n').encode())
                restore_response += native_read(.3)
            if changed_filters:
                actual = filter_levels(restore_response, tuple(prior_levels))
                record['logging_restored'] = actual == prior_levels
                if actual != prior_levels:
                    record['logging_restore_error'] = 'filter readback differs from prelaunch levels'
        except (OSError, ValueError) as exc:
            record['logging_restore_error'] = str(exc)
        if pending_native:
            native.write(sanitize(pending_native))
        native.close()
        sock.close()
        if logcat:
            logcat.terminate()
            try:
                logcat.wait(timeout=3)
            except subprocess.TimeoutExpired:
                logcat.kill()
                logcat.wait()
            reader.join(timeout=2)
        (args.out / 'RESULT.json').write_text(json.dumps(record, indent=2))
    record['boot_after'] = adb(['shell', 'cat', '/proc/sys/kernel/random/boot_id'], 6)
    (args.out / 'RESULT.json').write_text(json.dumps(record, indent=2))
    print(json.dumps({key: record.get(key) for key in
        ['stage', 'boot_before', 'boot_after', 'observer_error']}, indent=2))


if __name__ == '__main__':
    main()
