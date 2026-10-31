#!/usr/bin/env python3
"""Cross-check bundled catalog, exact source identities and CUBE payloads."""
from pathlib import Path
import hashlib
import json
import math
import re

ROOT = Path(__file__).resolve().parents[1]
assets = ROOT / 'android-assets'
catalog = json.loads((assets / 'CATALOG.JSN').read_text())
assert len({e['asset'] for e in catalog}) == len(catalog)
assert len({e['sha256'] for e in catalog}) == len(catalog)
assert sum(e.get('default', False) for e in catalog) == 1
for entry in catalog:
    assert re.fullmatch(r'[A-Z0-9_]{1,8}\.CUB', entry['asset'])
    data = (assets / entry['asset']).read_bytes()
    assert hashlib.sha256(data).hexdigest() == entry['sha256'], entry['asset']
    size = None
    rows = 0
    for raw in data.splitlines():
        words = raw.split(b'#', 1)[0].split()
        if not words or words[0] == b'TITLE':
            continue
        if words[0] == b'LUT_3D_SIZE':
            assert size is None
            size = int(words[1])
        elif words[0] in (b'DOMAIN_MIN', b'DOMAIN_MAX'):
            assert len(words) == 4 and all(math.isfinite(float(x)) for x in words[1:])
        else:
            assert len(words) == 3 and all(math.isfinite(float(x)) for x in words)
            rows += 1
    assert 2 <= size <= 65 and size == entry['grid'] and rows == size ** 3, entry['asset']
print('PASS {} builtins: one default, exact hashes, grid/row counts and finite values'.format(len(catalog)))
