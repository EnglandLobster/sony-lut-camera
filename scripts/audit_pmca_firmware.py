#!/usr/bin/env python3
"""Inventory extracted Sony firmware; static contracts are not shooting tests.

Takes collection.json from collect_pmca_firmware.py. Firmware stays local; the
result is a small, source-addressed report suitable for reviewing model gaps.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import yaml
from elftools.elf.elffile import ELFFile
from audit_sony_capture_api import audit

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inspect(row):
    result = {k: row[k] for k in ['model', 'url', 'sha256', 'status'] if k in row}
    if row['status'] != 'extracted':
        result['reason'] = row.get('error', 'no firmware package on checked Sony page')
        return result
    root = Path(row['system'])
    api = audit(root)
    result['java'] = {k: v for k, v in api.items() if k not in ['system', 'contracts', 'fields']}
    result['inputs'] = {p: digest(root / p) for p in api['inputs']}
    props = dict(line.split('=', 1) for line in (root / 'build.prop').read_text().splitlines()
                 if '=' in line and not line.startswith('#'))
    result['android'] = {k: props.get(k) for k in ['ro.product.model', 'ro.build.version.release',
                        'ro.build.version.sdk', 'ro.product.cpu.abi', 'dalvik.vm.heapsize']}
    library = root / 'lib/libcameraseq.so'
    result['inputs']['lib/libcameraseq.so'] = digest(library)
    with library.open('rb') as stream:
        elf = ELFFile(stream)
        table = elf.get_section_by_name('.dynsym')
        symbols = {s.name for s in table.iter_symbols() if s['st_shndx'] != 'SHN_UNDEF'}
        result['elf'] = {'machine': elf['e_machine'], 'bits': elf.elfclass}
    header = (ROOT / 'native/device_memory_api.hpp').read_text()
    required_part = header.split('deviceMemoryRequiredSymbols[] = {', 1)[1].split('};', 1)[0]
    required = re.findall(r'"(_Z[^\"]+)"', required_part)
    modern = '_ZN6scalar18DeviceMemoryDiadem21getPhysicalMemoryInfoERjS1_'
    result['native'] = {'missing': sorted(set(required) - symbols),
                        'memory_access': 'physical_memory_info' if modern in symbols else 'legacy_memory_properties'}
    # A firmware may contain alternatives for several sensors. These are
    # candidates, never a claim that a body selects every string seen here.
    backup = next((p for p in root.parents if (p / 'config.yaml').is_file()), root)
    if (backup / 'config.yaml').is_file():
        result['firmware_version'] = yaml.safe_load((backup / 'config.yaml').read_text())['fdat']['version']
    candidates = set()
    for path in backup.rglob('*.bin'):
        if '0110_backup' in str(path) and path.stat().st_size < 16 * 1024 * 1024:
            candidates.update(x.decode() for x in re.findall(rb'(?:ILDCSC|DSCSC)_[A-Za-z0-9]+', path.read_bytes()))
    maps = []
    for path in root.rglob('*PictureEffectPlus*'):
        if path.is_file():
            maps.append({'path': str(path.relative_to(root)), 'bytes': path.stat().st_size, 'sha256': digest(path)})
    result['backup_memory_strings'] = sorted(candidates)
    result['firmware_picture_effect_maps'] = maps
    result['static_api_complete'] = not(api['missing'] or api['missing_fields'] or
                                       api['store_protocol'] == 'unsupported' or result['native']['missing'])
    result['physical_capture'] = 'not tested in this audit'
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('collection', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    reports = []
    for row in json.loads(args.collection.read_text()):
        try:
            report = inspect(row)
        except Exception as error:
            report = {'model': row['model'], 'status': 'audit_error', 'error': str(error)}
        reports.append(report)
        print(row['model'], report.get('static_api_complete', report['status']), flush=True)
    args.output.write_text(json.dumps({'schema': 1, 'kind': 'static firmware evidence', 'models': reports}, indent=2) + '\n')
    raise SystemExit(int(any(r['status'] == 'audit_error' for r in reports)))
