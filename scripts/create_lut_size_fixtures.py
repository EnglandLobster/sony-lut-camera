#!/usr/bin/env python3
"""Generate a matched, non-identity 17/33 lattice pair for capture timing.

Both grids directly sample the existing COLOR17 analytic transform; COLOR33
is not an upsample of the17 lattice. No camera access or photo modification.
"""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile


def write_lut(path, size):
    nonidentity = 0
    with path.open('w') as stream:
        stream.write(f'LUT_3D_SIZE {size}\n')
        for blue in range(size):
            for green in range(size):
                for red in range(size):
                    r, g, b = (v / (size - 1) for v in (red, green, blue))
                    output = (r * r + g * .08, g * .9 + b * .05, b * .8 + r * .1)
                    nonidentity += any(abs(x - y) > 1e-9 for x, y in zip(output, (r, g, b)))
                    stream.write(' '.join(f'{value:.10f}' for value in output) + '\n')
    return {'file': path.name, 'lut_size': size, 'nodes': size ** 3,
            'nonidentity_nodes': nonidentity,
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--baseline-apk', type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    rows = [write_lut(args.out / f'COLOR{size}.CUB', size) for size in (17, 33)]
    with ZipFile(args.baseline_apk) as apk:
        if apk.read('assets/COLOR17.CUB') != (args.out / 'COLOR17.CUB').read_bytes():
            raise SystemExit('Generated17 fixture differs from the measured APK baseline.')
    report = {'formula': ['r*r + .08*g', '.9*g + .05*b', '.8*b + .1*r'],
              'domain': [0, 1], 'fixtures': rows, '17_matches_baseline_apk': True,
              'scope': 'Visible synthetic cross-channel color transform; not an identity or a claimed film emulation.'}
    (args.out / 'LUTS.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
