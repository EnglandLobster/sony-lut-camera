#!/usr/bin/env python3
"""Enforce the resource boundary for source trees, source archives and release APKs."""
import argparse
import json
from pathlib import Path, PurePosixPath
import subprocess
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CATALOG = json.loads((ROOT / 'android-assets/CATALOG.JSN').read_text())
CUBES = {item['asset'] for item in CATALOG}
RESOURCE_SUFFIXES = {'.prv', '.cube', '.cub', '.icc', '.icm', '.xmp', '.dcp'}


def check(name, data, apk=False):
    path = PurePosixPath(name)
    if not apk and path.parts[:1] == ('third-party-sources',):
        return  # Pinned dependency source, packaged separately from application resources.
    suffix = path.suffix.lower()
    expected = ('assets/' if apk else 'android-assets/') + path.name
    if suffix in RESOURCE_SUFFIXES:
        if not (suffix == '.cub' and name == expected and path.name in CUBES):
            raise ValueError('Unlisted color resource: ' + name)
    if data.lstrip().startswith((b'SONY_PREVIEW_V1\n', b'SONY_PREVIEW_V2\n')):
        raise ValueError('Static preview profile is not a source/release asset: ' + name)


def audit_tracked():
    names = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT)
    for raw in set(names.split(b'\0')):
        if raw:
            name = raw.decode('utf-8')
            path = ROOT / name
            if path.is_file():
                check(name, path.read_bytes())


def audit_apk(path):
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if not name.endswith('/'):
                check(name, archive.read(name), apk=True)


def audit_source(path):
    with tarfile.open(path) as archive:
        for entry in archive:
            if entry.isfile():
                name = entry.name.split('/', 1)[1]
                check(name, archive.extractfile(entry).read())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tracked', action='store_true')
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--source', type=Path)
    args = parser.parse_args()
    if not (args.tracked or args.apk or args.source):
        parser.error('Choose --tracked, --apk or --source.')
    if args.tracked:
        audit_tracked()
    if args.apk:
        audit_apk(args.apk)
    if args.source:
        audit_source(args.source)
    print('PASS source/release color-resource boundary')
