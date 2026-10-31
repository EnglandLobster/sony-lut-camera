#!/usr/bin/env python3
"""Create local release artifacts from a committed, checked release build. No upload."""
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import xml.etree.ElementTree as ET
from audit_release_assets import audit_tracked, audit_apk, audit_source

ROOT = Path(__file__).resolve().parents[1]
A = '{http://schemas.android.com/apk/res/android}'


def git(*args, cwd=ROOT):
    return subprocess.check_output(['git', '-C', str(cwd), *args])


def package():
    if git('status', '--porcelain').strip():
        raise SystemExit('Commit reviewed source before packaging a release.')
    audit_tracked()
    manifest = ET.parse(ROOT / 'android/AndroidManifest.xml').getroot()
    version = manifest.get(A + 'versionName')
    commit = git('rev-parse', 'HEAD').decode().strip()
    report = json.loads((ROOT / 'dist/BUILDINFO.json').read_text())
    apk = ROOT / 'dist/SonyLutTest.apk'
    audit_apk(apk)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if report.get('variant') != 'release' or report['apk_sha256'] != digest:
        raise SystemExit('Build/check the release APK first; current APK does not match release report.')
    if "versionName='" + version + "'" not in report['badging']:
        raise SystemExit('APK version differs from committed source.')
    dest = ROOT / 'dist/releases' / ('v' + version)
    dest.mkdir(parents=True, exist_ok=True)
    name = 'SonyLutCamera-v' + version
    shutil.copyfile(apk, dest / (name + '.apk'))
    for src, target in [('docs/USER_GUIDE.md', 'USER_GUIDE.md'), ('LICENSE', 'LICENSE'),
                        ('ASSET_LICENSES.md', 'ASSET_LICENSES.md'),
                        ('THIRD_PARTY_NOTICES.md', 'THIRD_PARTY_NOTICES.md')]:
        shutil.copyfile(ROOT / src, dest / target)
    # Include exactly committed app source and pinned libjpeg source, not .git,
    # private signing material, a mutable working tree, SDKs or unlicensed map.
    lock = json.loads((ROOT / 'dependencies.lock.json').read_text())
    jpeg = next(e for e in lock['git'] if e['path'] == 'vendor/libjpeg-turbo')
    dep = ROOT / jpeg['path']
    assert git('rev-parse', 'HEAD', cwd=dep).decode().strip() == jpeg['commit']
    assert not git('status', '--porcelain', cwd=dep).strip()
    with tarfile.open(dest / (name + '-source.tar.gz'), 'w:gz') as output:
        for cwd, revision, prefix in [(ROOT, commit, name + '/'),
                                      (dep, jpeg['commit'], name + '/third-party-sources/libjpeg-turbo/')]:
            data = git('archive', '--format=tar', '--prefix=' + prefix, revision, cwd=cwd)
            with tarfile.open(fileobj=io.BytesIO(data), mode='r:') as archive:
                for entry in archive:
                    output.addfile(entry, archive.extractfile(entry) if entry.isfile() else None)
    audit_source(dest / (name + '-source.tar.gz'))
    info = {'version': version, 'version_code': int(manifest.get(A + 'versionCode')),
            'source_commit': commit, 'variant': 'release', 'apk_sha256': digest,
            'apk_bytes': apk.stat().st_size, 'libjpeg_commit': jpeg['commit'],
            'verified_camera': 'Sony A6300 firmware 2.01',
            'public_apk_status': 'draft pending Sony memory-map redistribution clarification'}
    (dest / 'BUILDINFO.json').write_text(json.dumps(info, indent=2) + '\n')
    files = sorted(p for p in dest.iterdir() if p.is_file() and p.name != 'SHA256SUMS')
    (dest / 'SHA256SUMS').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n' for p in files))
    print(dest)


if __name__ == '__main__':
    package()
