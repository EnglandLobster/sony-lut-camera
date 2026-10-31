#!/usr/bin/env python3
"""Generate an identity preview fixture from the project's mathematical LUT."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def generate(destination):
    destination.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256((ROOT / 'android-assets/IDENT33.CUB').read_bytes()).hexdigest()
    profile = 'SONY_PREVIEW_V1\n' + digest + '\nmatrix_gamma\n'
    profile += '1024 0 0 0 1024 0 0 0 1024\n'
    profile += ' '.join(str(i) for i in range(1024)) + '\n'
    (destination / 'IDENT.PRV').write_text(profile, encoding='ascii')
    catalog = [{'asset': 'IDENT.PRV', 'sha256': digest, 'label': 'Identity',
                'order_hypothesis': 'matrix_gamma', 'hardware_validated': False}]
    (destination / 'CATALOG.json').write_text(json.dumps(catalog, indent=2) + '\n')


if __name__ == '__main__':
    generate(ROOT / 'out/tests/native-preview')
