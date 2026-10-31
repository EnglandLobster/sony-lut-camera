#!/usr/bin/env python3
"""Read-only runtime inventory for a Wi-Fi-bootstrapped wired debugging link.

No setprop, module loading, USB reconfiguration, service changes or firmware
writes. Missing paths and permission errors are evidence, not silent success.
"""
import argparse
import concurrent.futures
import datetime
import gzip
import json
from pathlib import Path
import re
import subprocess
import zlib

from collect_link_state import collect


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--out', type=Path,
                        default=Path(__file__).resolve().parents[1] / 'results' / 'wired-20261018')
    args = parser.parse_args()
    state = subprocess.run(['adb', '-s', args.serial, 'get-state'],
                           capture_output=True, text=True, timeout=8)
    if state.returncode or state.stdout.strip() != 'device':
        raise SystemExit('ADB is not connected; no device actions performed.')
    now = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8)))
    dest = args.out / now.strftime('runtime-%Y%m%d-%H%M%S-%f')
    dest.mkdir(parents=True, exist_ok=False)
    commands = {
        'IDENTITY.TXT': ['id'],
        'KERNEL.TXT': ['uname', '-a'],
        'BOOT_ID.TXT': ['cat', '/proc/sys/kernel/random/boot_id'],
        'UPTIME.TXT': ['cat', '/proc/uptime'],
        'USB_NODES.TXT': ['ls', '-ld', '/dev/android_adb', '/dev/android',
                         '/dev/usb-ffs', '/dev/usb-ffs/adb', '/dev/gadget',
                         '/sys/class/android_usb', '/sys/class/udc',
                         '/sys/kernel/config', '/config/usb_gadget'],
        'SYS_CLASS.TXT': ['ls', '/sys/class'],
        'PLATFORM_DRIVERS.TXT': ['ls', '/sys/bus/platform/drivers'],
        'MODULES.TXT': ['cat', '/proc/modules'],
        'DEVICES.TXT': ['cat', '/proc/devices'],
        'MOUNTS.TXT': ['cat', '/proc/mounts'],
        'FILESYSTEMS.TXT': ['cat', '/proc/filesystems'],
        'INIT_LIST.TXT': ['ls', '-l', '/init*.rc'],
        'INIT_RC.TXT': ['cat', '/init.rc'],
        'INIT_USB_RC.TXT': ['cat', '/init.usb.rc'],
        'INIT_PLATFORM_RC.TXT': ['cat', '/init.arm-cxd90014.rc'],
        'PROP_USB_CONFIG.TXT': ['getprop', 'sys.usb.config'],
        'PROP_USB_STATE.TXT': ['getprop', 'sys.usb.state'],
        'PROP_USB_PERSIST.TXT': ['getprop', 'persist.sys.usb.config'],
        'PROP_ADB_TCP.TXT': ['getprop', 'service.adb.tcp.port'],
        'PROP_ADB_TCP_PERSIST.TXT': ['getprop', 'persist.adb.tcp.port'],
    }
    report = {'serial': args.serial, 'captured_at': now.isoformat(),
              'device_settings_changed': False, 'commands': {}}
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        pending = {pool.submit(collect, args.serial, cmd): name for name, cmd in commands.items()}
        for future in concurrent.futures.as_completed(pending):
            name = pending[future]
            output, status = future.result()
            if re.search(r'Permission denied|Operation not permitted|No such file|not found', output, re.I):
                status['unavailable_or_denied'] = True
            (dest / name).write_text(output, encoding='utf-8')
            report['commands'][name] = status
    # A binary cat through this old ADB shell can map LF to CRLF. Try the
    # untouched gzip first, and undo shell CR insertion only if necessary.
    try:
        result = subprocess.run(['adb', '-s', args.serial, 'shell', 'cat', '/proc/config.gz'],
                                capture_output=True, timeout=10)
        data = result.stdout
        if not data.startswith(b'\x1f\x8b'):
            report['kernel_config'] = {'available': False, 'message': data.decode(errors='replace').strip()}
        else:
            try:
                config = gzip.decompress(data)
            except (OSError, EOFError, zlib.error):
                config = gzip.decompress(data.replace(b'\r\n', b'\n'))
            (dest / 'KERNEL_CONFIG.TXT').write_bytes(config)
            selected = [line for line in config.decode(errors='replace').splitlines()
                        if re.search(r'CONFIG_(USB|CONFIGFS|IKCONFIG|ANDROID|FUSB)', line)]
            (dest / 'USB_KERNEL_FLAGS.TXT').write_text('\n'.join(selected) + '\n')
            report['kernel_config'] = {'available': True}
    except (OSError, EOFError, zlib.error, subprocess.TimeoutExpired) as exc:
        report['kernel_config'] = {'available': False, 'error': str(exc)}
    (dest / 'COLLECTION.json').write_text(json.dumps(report, indent=2) + '\n')
    print(dest)


if __name__ == '__main__':
    main()
