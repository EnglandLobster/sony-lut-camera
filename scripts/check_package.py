#!/usr/bin/env python3
"""Inspect the offline artifacts; no camera connection."""
import argparse,datetime,hashlib,json,os,pathlib,re,subprocess,sys,zipfile
from audit_release_assets import audit_apk
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("--variant", choices=["debug","release"], default="debug")
variant=parser.parse_args().variant
root=pathlib.Path(__file__).resolve().parents[1]
ndk=pathlib.Path(os.environ.get('ANDROID_NDK',root/'vendor/toolchains/android-ndk-r16b'))
sdk=pathlib.Path(os.environ.get('ANDROID_SDK','/opt/homebrew/share/android-commandlinetools'))
ndk_host={'darwin':'darwin-x86_64','linux':'linux-x86_64','win32':'windows-x86_64'}.get(sys.platform)
if ndk_host is None:raise RuntimeError('Unsupported NDK build host: '+sys.platform)
readelf=ndk/('toolchains/arm-linux-androideabi-4.9/prebuilt/'+ndk_host+'/bin/arm-linux-androideabi-readelf')
apk=root/'dist/SonyLutTest.apk'
def elf(args):return subprocess.check_output([str(readelf)]+[str(x) for x in args],text=True)
report={'checked_at':datetime.datetime.now().astimezone().isoformat(),
        'variant':variant,'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
        'apk_bytes':apk.stat().st_size,'native':{}}
with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None
    assert z.read('classes.dex')[:8]==b'dex\n035\0'
    maps=json.loads((root/'config/capture-maps.json').read_text())['maps']
    map_assets={'assets/'+e['asset'] for e in maps if e.get('asset')}
    assert {n for n in z.namelist() if re.fullmatch(r'assets/MAP[A-Z0-9]*\.BIN',n)}==map_assets
    rows=[]
    for entry in maps:
        if entry.get('asset'):
            data=z.read('assets/'+entry['asset'])
            assert len(data)==entry['size'] and hashlib.sha256(data).hexdigest()==entry['sha256']
        rows.append('\t'.join(map(str,[entry['memory'],entry['pixels'],entry['revision'],entry['filename'],entry.get('asset') or '-',entry['size'],entry['sha256']])))
    assert z.read('assets/CAPMAP.TXT').decode().splitlines()[1:]==rows
    report['capture_maps']={'bundled':len(map_assets),'catalog_rows':len(maps),'firmware_blobs_packaged':False}
    catalog=json.loads(z.read('assets/CATALOG.JSN'))
    assert sum(item.get('default',False) for item in catalog)==1
    for item in catalog:
        assert re.fullmatch(r'[A-Z0-9_]{1,8}\.CUB',item['asset'])
        assert hashlib.sha256(z.read('assets/'+item['asset'])).hexdigest()==item['sha256']
        assert 2<=item['grid']<=65
    assert len({item['asset'] for item in catalog})==len(catalog)
    font=json.loads((root/'android-assets/FONTINFO.json').read_text())
    assert hashlib.sha256(z.read('assets/UIFONT.TTF')).hexdigest()==font['sha256']
    assert 'assets/FONT-OFL.txt' in z.namelist()
    assert z.read('assets/PREVIEW.JPG') == (root/'android-assets/PREVIEW.JPG').read_bytes()

    for notice in ['LICENSE','THIRD_PARTY_NOTICES.md','ASSET_LICENSES.md']:
        assert z.read('assets/'+notice)==(root/notice).read_bytes()
    if variant=='release':
        allowed_cubes={'assets/'+e['asset'] for e in catalog}
        assert {n for n in z.namelist() if n.endswith('.CUB')}==allowed_cubes
        assert not any(n.startswith('assets/native-preview/') or n.endswith('TEST24.JPG') for n in z.namelist())
        assert b'GraphicsDepthProbeActivity' not in z.read('classes.dex')
        assert b'PhotoActivity' in z.read('classes.dex') and b'PreJpegProbeActivity' in z.read('classes.dex')
    report['dex']='035'
    for abi,folder in [('armeabi','android-armv5'),('armeabi-v7a','android')]:
        data=z.read('lib/'+abi+'/libsonylut.so')
        assert data[:5]==b'\x7fELF\x01' and data[18:20]==b'\x28\0'
        library=root/'out'/folder/'libsonylut.so'
        attr=elf(['-A',library]);assert 'Advanced_SIMD' not in attr
        objects=[root/'out'/folder/'CMakeFiles/sonylut.dir'/name for name in ['core.cpp.o','jpeg_io.cpp.o','jni.cpp.o']]
        objattr=elf(['-A']+objects)
        expected='v5TE' if abi=='armeabi' else 'v7'
        assert re.findall(r'Tag_CPU_arch: (\S+)',objattr)==[expected]*3
        assert 'Advanced_SIMD' not in objattr
        report['native'][abi]={'bytes':len(data),'source_object_arch':expected,
            'linked_attributes':attr,'needed':re.findall(r'Shared library: \[(.*?)\]',elf(['-d',library]))}
    for name in ['assets/JPEG-LICENSE.txt','assets/JPEG-IJG.txt','assets/GNU-STL-NOTICE.txt']:
        assert name in z.namelist()
report['badging']=subprocess.check_output([str(sdk/'build-tools/36.0.0/aapt2'),'dump','badging',str(apk)],text=True)
assert re.search(r"(?:minSdkVersion|sdkVersion):'10'",report['badging'])
if variant=='release':
    audit_apk(apk)
    manifest=subprocess.check_output([str(sdk/'build-tools/36.0.0/aapt2'),'dump','xmltree',str(apk),'--file','AndroidManifest.xml'],text=True)
    assert re.search(r'android:debuggable[^\n]*=false',manifest), 'Release APK must not be debuggable'
    activities=re.findall(r'android:name[^\n]*="(\.[^"]*Activity)"',manifest)
    assert set(activities)=={'.LibraryActivity','.PhotoActivity','.GalleryActivity'},activities
    report['release_activities']=activities
report['armeabi_attribute_note']='NDK14 shared platform stubs contribute v7 attributes; source objects are v5TE. See HANDOFF.md.'
(root/'dist/BUILDINFO.json').write_text(json.dumps(report,indent=2))
print('PASS APK: zip, dex035, minSDK10, ARM32, v5TE/v7 source objects, no NEON attribute, licenses')
print('APK bytes:',report['apk_bytes'],'SHA256:',report['apk_sha256'])
