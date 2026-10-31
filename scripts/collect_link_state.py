#!/usr/bin/env python3
"""Read-only A6300 link/lifecycle snapshot. Never enables Wi-Fi or changes settings.

Run once before and once after a reproduced exit. Boot ID/uptime distinguish a
kernel reboot; PS and activity/event logs identify Android process restarts.
Does not read supplicant configuration files or request saved credentials.
"""
import argparse
import concurrent.futures
import datetime
import json
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parents[1]
SECRET_LINE = re.compile(r'psk|password|passphrase|wep.?key|private.?key|client.?cert', re.I)
WIFI_SUMMARY = re.compile(
    r'^\s*(Wi-Fi is |Wifi is |Stay-awake|mWifiInfo|mNetworkInfo|'
    r'mLastSignalLevel|mLastBssid|mLastNetworkId|mWifiState|mOperationalMode|'
    r'Supplicant state|Current state|NetworkInfo|WifiInfo|'
    r'ID:\s*\d|\*\s*ID:\s*\d|-\s*ID:\s*\d)'
)


def sanitize(text):
    # Drop whole credential-related log lines, including masked ones. Do not
    # persist raw dumps; some Sony system logs have verbose Wi-Fi diagnostics.
    return '\n'.join(
        '[credential-related line omitted]' if SECRET_LINE.search(line) else line
        for line in text.replace('\r', '').splitlines()
    ) + '\n'


def collect(serial, command, wifi_summary=False):
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    try:
        result = subprocess.run(
            ['adb', '-s', serial, 'shell', *command],
            capture_output=True, text=True, errors='replace', timeout=12,
        )
        output = result.stdout
        if wifi_summary:
            output = '\n'.join(line for line in output.splitlines() if WIFI_SUMMARY.search(line))
        return sanitize(output), {
            'started_utc': started, 'command': command,
            'returncode': result.returncode, 'stderr': sanitize(result.stderr).strip(),
        }
    except (OSError, subprocess.TimeoutExpired) as exc:
        return '', {'started_utc': started, 'command': command, 'error': str(exc)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--label', default='snapshot')
    parser.add_argument('--out', type=Path, default=ROOT / 'results' / 'link-20261018')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,48}', args.label):
        parser.error('--label must use 1-48 letters, digits, underscores or hyphens')
    state = subprocess.run(['adb', '-s', args.serial, 'get-state'],
                           capture_output=True, text=True, timeout=8)
    if state.returncode or state.stdout.strip() != 'device':
        raise SystemExit('Camera is not connected; no device actions performed.')
    stamp = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8)))
    dest = args.out / (stamp.strftime('%Y%m%d-%H%M%S-%f') + '-' + args.label)
    dest.mkdir(parents=True, exist_ok=False)
    commands = {
        'BOOT_ID.TXT': ['cat', '/proc/sys/kernel/random/boot_id'],
        'UPTIME.TXT': ['cat', '/proc/uptime'],
        'PS.TXT': ['ps'],
        'BATTERY.TXT': ['dumpsys', 'battery'],
        'POWER.TXT': ['dumpsys', 'power'],
        'ACTIVITIES.TXT': ['dumpsys', 'activity', 'activities'],
        'WIFI_SUMMARY.TXT': ['dumpsys', 'wifi'],
        'LOG_MAIN.TXT': ['logcat', '-b', 'main', '-d', '-v', 'threadtime', '-t', '1800'],
        'LOG_SYSTEM.TXT': ['logcat', '-b', 'system', '-d', '-v', 'threadtime', '-t', '1800'],
        'LOG_EVENTS.TXT': ['logcat', '-b', 'events', '-d', '-v', 'threadtime', '-t', '1000'],
        'DMESG.TXT': ['dmesg'],
    }
    report = {'serial': args.serial, 'captured_at': stamp.isoformat(),
              'device_settings_changed': False, 'commands': {}}
    # A small bounded pool avoids spending a minute in serial IPC while the
    # camera's Wi-Fi link or native power management may disappear.
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        futures = {pool.submit(collect, args.serial, cmd, name == 'WIFI_SUMMARY.TXT'): name
                   for name, cmd in commands.items()}
        for future in concurrent.futures.as_completed(futures):
            name = futures[future]
            output, status = future.result()
            (dest / name).write_text(output, encoding='utf-8')
            report['commands'][name] = status
            (dest / 'COLLECTION.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(dest)
    for name, status in report['commands'].items():
        if status.get('error') or status.get('returncode'):
            print(name + ': capture failed; see COLLECTION.json')


if __name__ == '__main__':
    main()
