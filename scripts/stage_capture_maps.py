#!/usr/bin/env python3
"""Stage fingerprinted map assets; firmware-only maps remain on the camera."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def stage(out):
    entries = json.loads((ROOT / 'config/capture-maps.json').read_text())['maps']
    rows = ['# memory\tpixels\trevision\tfilename\tasset\tbytes\tsha256']
    lock = {e['path']: e for e in json.loads((ROOT / 'dependencies.lock.json').read_text())['files']}
    for entry in entries:
        asset = entry.get('asset')
        if asset:
            assert lock[entry['path']]['sha256'] == entry['sha256']
            data = (ROOT / entry['path']).read_bytes()
            assert len(data) == entry['size'] and hashlib.sha256(data).hexdigest() == entry['sha256']
            (out / asset).write_bytes(data)
        rows.append('\t'.join(map(str, [entry['memory'], entry['pixels'], entry['revision'],
                                      entry['filename'], asset or '-', entry['size'], entry['sha256']])))
    (out / 'CAPMAP.TXT').write_text('\n'.join(rows) + '\n')


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    stage(args.output)
