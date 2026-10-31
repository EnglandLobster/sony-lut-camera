#!/usr/bin/env python3
"""Bounded live Sony diagnostic output via the already-enabled Tweak Telnet.

Uses ulogio's terminal output only. Restores terminal output to off on exit;
never changes log filters, clears logs, sends camera commands, or accesses
memory addresses. Run before a separately controlled app probe.
"""
import argparse
from pathlib import Path
import socket
import time

from collect_link_state import sanitize


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='192.168.5.218')
    parser.add_argument('--seconds', type=int, default=45)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.seconds <= 120:
        parser.error('--seconds must be 1..120')
    args.out.parent.mkdir(parents=True, exist_ok=True)
    sock = socket.create_connection((args.host, 23), timeout=5)
    sock.settimeout(.25)
    stream = args.out.open('x', encoding='utf-8')
    count = 0
    pending = b''
    # A new ulogio session has terminal forwarding off. No shell state persists
    # when this Telnet connection closes; explicitly turn forwarding off too.
    try:
        sock.sendall(b'\n/usr/bin/ulogio\n')
        time.sleep(.25)
        sock.sendall(b'term on\nrewind\n')
        print('Native terminal capture started', flush=True)
        deadline = time.monotonic() + args.seconds
        while time.monotonic() < deadline and count < 2 * 1024 * 1024:
            try:
                data = sock.recv(65536)
            except socket.timeout:
                continue
            if not data:
                stream.write('\n[connection closed by device]\n')
                break
            count += len(data)
            pending += data
            lines = pending.split(b'\n')
            pending = lines.pop()
            for line in lines:
                stream.write(sanitize(line.decode('utf-8', 'replace')))
            stream.flush()
    finally:
        if pending:
            stream.write(sanitize(pending.decode('utf-8', 'replace')))
        stream.close()
        try:
            sock.sendall(b'term off\nexit\n')
        except OSError:
            pass
        sock.close()
    print('Native terminal capture finished; bytes=' + str(count), flush=True)


if __name__ == '__main__':
    main()
