#!/usr/bin/env python3
"""Download Sony update packages for OFFLINE extraction; never run an updater.

Supply a local fwtool.py checkout and its Python environment. All large/vendor
outputs remain under the explicitly supplied ignored directory. A completed
receipt is needed to reuse an extraction; partial runs are not called success.
"""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
from urllib.parse import urlparse
import urllib.request


def embedded_dat(data):
    """Locate a complete UFU container in a newer Sony PE wrapper."""
    magic = b'\x89UFU\r\n\x1a\n'
    start = data.find(magic)
    if start < 0:
        return None
    offset = start + len(magic)
    while offset + 8 <= len(data):
        size, kind = struct.unpack_from('>I4s', data, offset)
        offset += 8 + size
        if offset > len(data):
            raise ValueError('truncated UFU container')
        if kind == b'DEND':
            return data[start:offset]
    raise ValueError('missing UFU end chunk')


def collect(row, output, fwtool, python):
    model = row['model']
    if not re.fullmatch(r'[A-Z0-9-]+', model):
        raise ValueError('invalid model directory name')
    if not row.get('updates'):
        return {'model': model, 'status': 'no_package_on_checked_page'}
    folder = output / model
    folder.mkdir(parents=True, exist_ok=True)
    receipt = folder / 'receipt.json'
    url = row['updates'][0]
    if receipt.exists():
        previous = json.loads(receipt.read_text())
        if (previous.get('status') == 'extracted' and previous.get('url') == url
                and (Path(previous['system']) / 'build.prop').is_file()):
            return previous
    report = {'model': model, 'url': url, 'status': 'failed'}
    try:
        parsed = urlparse(url)
        if parsed.scheme != 'https' or parsed.hostname != 'support.d-imaging.sony.co.jp':
            raise ValueError('expected official Sony HTTPS firmware URL')
        package = folder / Path(parsed.path).name
        if not package.exists():
            part = package.with_suffix('.part')
            with urllib.request.urlopen(url, timeout=60) as response, part.open('wb') as stream:
                while True:
                    block = response.read(1024 * 1024)
                    if not block:
                        break
                    stream.write(block)
            part.replace(package)
        data = package.read_bytes()
        if not data.startswith(b'MZ'):
            raise ValueError('download is not a Windows updater container')
        report.update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        payload = embedded_dat(data)
        source = package
        if payload is not None:
            source = folder / 'FirmwareData.dat'
            source.write_bytes(payload)
        del data, payload
        with (folder / 'unpack.log').open('w') as log:
            subprocess.run([python, str(fwtool), 'unpack', '-f', str(source),
                            '-o', str(folder / 'extracted')], stdout=log,
                           stderr=subprocess.STDOUT, check=True, timeout=600)
        roots = [p.parent for p in (folder / 'extracted').rglob('build.prop')
                 if (p.parent / 'framework' / 'sony.cameraex.odex').is_file()]
        if len(roots) != 1:
            raise ValueError('expected one Sony Android system, found %s' % roots)
        report.update(status='extracted', system=str(roots[0]))
    except Exception as error:
        report['error'] = str(error)
    receipt.write_text(json.dumps(report, indent=2) + '\n')
    print(model + ': ' + report['status'], flush=True)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=Path('docs/compatibility/firmware-sources.json'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--fwtool', type=Path, required=True)
    parser.add_argument('--python', default=sys.executable)
    parser.add_argument('--models', nargs='*')
    parser.add_argument('--jobs', type=int, choices=range(1, 5), default=2)
    args = parser.parse_args()
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    rows = json.loads(args.manifest.read_text())['models']
    if args.models:
        rows = [r for r in rows if r['model'] in args.models]
        if {r['model'] for r in rows} != set(args.models):
            parser.error('unknown model')
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        reports = list(pool.map(lambda r: collect(r, args.output, args.fwtool.resolve(), args.python), rows))
    (args.output / 'collection.json').write_text(json.dumps(reports, indent=2) + '\n')
    raise SystemExit(int(any(r['status'] == 'failed' for r in reports)))
