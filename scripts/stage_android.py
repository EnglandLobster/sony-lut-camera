#!/usr/bin/env python3
"""Stage an explicit development or release APK without touching signing keys."""
import argparse
import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET
from generate_preview_fixture import generate
from stage_capture_maps import stage as stage_maps

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
PRODUCT_ACTIVITIES = {'.LibraryActivity', '.PhotoActivity', '.GalleryActivity'}


def stage(variant):
    out = ROOT / 'out/apk'
    assets = out / 'assets'
    # This is generated staging only, never out/ itself or out/test.keystore.
    if assets.exists():
        shutil.rmtree(assets)
    assets.mkdir(parents=True)
    stage_maps(assets)
    catalog = json.loads((ROOT / 'android-assets/CATALOG.JSN').read_text())
    names = [e['asset'] for e in catalog]
    names += ['CATALOG.JSN', 'PREVIEW.JPG', 'UIFONT.TTF', 'FONT-OFL.txt']
    for name in names:
        shutil.copyfile(ROOT / 'android-assets' / name, assets / name)
    for name in ['LICENSE', 'THIRD_PARTY_NOTICES.md', 'ASSET_LICENSES.md']:
        shutil.copyfile(ROOT / name, assets / name)
    if variant == 'debug':
        generate(assets / 'native-preview')
        for name in ['ID33.CUB', 'COLOR17.CUB']:
            shutil.copyfile(ROOT / 'out/tests' / name, assets / name)
        shutil.copyfile(ROOT / 'out/tests/IN6000.JPG', assets / 'TEST24.JPG')
    ET.register_namespace('android', ANDROID[1:-1])
    tree = ET.parse(ROOT / 'android/AndroidManifest.xml')
    app = tree.getroot().find('application')
    if variant == 'release':
        app.set(ANDROID + 'debuggable', 'false')
        app.set(ANDROID + 'allowBackup', 'false')
        for activity in list(app.findall('activity')):
            if activity.get(ANDROID + 'name') not in PRODUCT_ACTIVITIES:
                app.remove(activity)
    tree.write(out / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
    sources = sorted(ROOT.glob('android/**/*.java'))
    if variant == 'release':
        sources = [p for p in sources if 'diagnostics' not in p.relative_to(ROOT).parts]
    (out / 'sources.txt').write_text(''.join(str(p.relative_to(ROOT)) + '\n' for p in sources))
    (out / 'variant.json').write_text(json.dumps({'variant': variant, 'source_count': len(sources)}))
    print('Staged', variant, 'APK:', len(sources), 'Java sources;', len(catalog), 'built-in LUTs')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--variant', choices=['debug', 'release'], default='debug')
    stage(parser.parse_args().variant)
