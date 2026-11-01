#!/usr/bin/env python3
"""Audit capture reflection contracts against locally extracted Sony ODEX files.

Requires androguard. Reads declarations and inheritance, never executes firmware.
No vendor binary is copied into the application or the repository.
"""
import argparse
import json
import struct
import zipfile
from pathlib import Path
from loguru import logger
from androguard.core.dex import DEX

logger.disable('androguard')
C = 'Lcom/sony/scalar/hardware/CameraEx'
S = 'Lcom/sony/scalar/hardware/CameraSequence'
I = 'Lcom/sony/scalar/graphics/OptimizedImage;'
D = 'Lcom/sony/scalar/hardware/avio/DisplayManager'
G = 'Lcom/sony/scalar/sysutil/didep/Gpelibrary'


def audit(root):
    classes = {}
    inputs = []
    for name in ['sony.cameraex', 'framework', 'com.sony.scalar.hardware.avio.DisplayManager',
                 'com.sony.scalar.sysutil.didep.Gpelibrary', 'com.sony.scalar.sysutil.Property',
                 'com.sony.scalar.database_library']:
        path = root / 'framework' / (name + '.odex')
        if path.exists():
            raw = path.read_bytes()
            if not raw.startswith(b'dey\n'):
                raise ValueError('invalid ODEX: ' + str(path))
            offset, length = struct.unpack_from('<II', raw, 8)
            if offset + length > len(raw):
                raise ValueError('truncated ODEX: ' + str(path))
            dex = DEX(raw[offset:offset + length])
        else:
            path = path.with_suffix('.jar')
            if not path.exists():
                continue
            with zipfile.ZipFile(path) as archive:
                if 'classes.dex' not in archive.namelist():
                    continue
                dex = DEX(archive.read('classes.dex'))
        inputs.append(str(path.relative_to(root)))
        classes.update((c.get_name(), c) for c in dex.get_classes())

    def method(owner, name, args):
        seen = set()
        while owner in classes and owner not in seen:
            seen.add(owner)
            c = classes[owner]
            for m in c.get_methods():
                if m.get_name() == name and m.get_descriptor().replace(' ', '').startswith('(' + args + ')') and m.get_access_flags() & 1:
                    return True
            if name == '<init>':
                return False
            owner = c.get_superclassname()
        return False

    required = []
    fields = []

    def field(owner, name, descriptor, public=True):
        original, seen, found = owner, set(), False
        while owner in classes and owner not in seen:
            seen.add(owner)
            c = classes[owner]
            if any(f.get_name() == name and f.get_descriptor() == descriptor and
                   (not public or f.get_access_flags() & 1) for f in c.get_fields()):
                found = True
                break
            owner = c.get_superclassname()
        fields.append({'owner': original, 'field': name, 'descriptor': descriptor, 'present': found})

    def need(owner, name, args=''):
        required.append({'owner': owner, 'method': name, 'args': args,
                         'present': method(owner, name, args)})

    need(C + '$OpenOptions;', '<init>')
    need(C + '$OpenOptions;', 'setPreview', 'Z')
    for name, args in [('open', 'I' + C + '$OpenOptions;'), ('getNormalCamera', ''),
                       ('startDirectShutter', ''), ('stopDirectShutter', C + '$DirectShutterStoppedCallback;'),
                       ('createParametersModifier', 'Landroid/hardware/Camera$Parameters;'),
                       ('createGammaTable', ''), ('getExtendedGammaTable', ''),
                       ('setExtendedGammaTable', C + '$GammaTable;')]:
        need(C + ';', name, args)
    for callback in ['ShutterListener', 'PreviewStartListener']:
        need(C + ';', 'set' + callback, C + '$' + callback + ';')
    for name, args in [('getSize', ''), ('write', 'Ljava/io/InputStream;'), ('read', '[B'),
                       ('release', ''), ('setPictureEffectGammaForceOff', 'Z')]:
        need(C + '$GammaTable;', name, args)
    need(C + '$ParametersModifier;', 'setRGBMatrix', '[I')
    need(S + ';', 'open', C + ';')
    need(S + ';', 'setShutterSequenceCallback', S + '$ShutterSequenceCallback;' + S + '$Options;')
    need(S + ';', 'setReleaseLock', 'Z')
    need(C + ';', 'release')
    need(S + ';', 'release')
    need(S + '$RawData;', 'isValid')
    need(S + '$RawData;', 'release')
    for source in [I, S + '$RawData;']:
        need(S + ';', 'storeImage', source + 'Z')
    need(S + '$Options;', '<init>')
    for arg in ['I', 'Z', 'Ljava/lang/String;']:
        need(S + '$Options;', 'setOption', 'Ljava/lang/String;' + arg)
    for name, args in [('<init>', ''), ('isSupported', ''), ('setSource', S + '$RawData;Z'),
                       ('execute', ''), ('getOutput', ''), ('release', '')]:
        need(S + '$DefaultDevelopFilter;', name, args)
    for name in ['getWidth', 'getHeight', 'getImageFormat', 'isValid', 'release']:
        need(I, name)
    for name, args in [('<init>', ''), ('getActiveDevice', ''), ('getDeviceInfo', 'Ljava/lang/String;'),
                       ('setDisplayStatusListener', D + '$DisplayEventListener;'),
                       ('releaseDisplayStatusListener', ''), ('controlGraphicsOutputAll', 'Z'), ('finish', '')]:
        need(D + ';', name, args)
    need(G + ';', 'changeFrameBufferPixel', G + '$GS_FRAMEBUFFER_TYPE;')
    need('Lcom/sony/scalar/sysutil/ScalarProperties;', 'getString', 'Ljava/lang/String;')
    field(I, 'mNativeMemory', 'I', public=False)
    field(D + ';', 'DEVICE_ID_NONE', 'Ljava/lang/String;')
    field(D + '$DeviceInfo;', 'aspect', 'I')
    for name in ['ASPECT_RATIO_3_2', 'ASPECT_RATIO_4_3', 'ASPECT_RATIO_5_3', 'ASPECT_RATIO_11_9', 'ASPECT_RATIO_16_9']:
        field(D + ';', name, 'I')
    for name in ['ABGR8888', 'RGBA4444']:
        field(G + '$GS_FRAMEBUFFER_TYPE;', name, G + '$GS_FRAMEBUFFER_TYPE;')
    modern = method(C + ';', 'setStoreImageCompleteListener', C + '$StoreImageCompleteListener;')
    if modern:
        need(C + '$StoreImageCompleteListener;', 'onDone', 'I' + C + '$StoreImageInfo;' + C + ';')
        field(C + '$StoreImageInfo;', 'DirectoryName', 'Ljava/lang/String;')
        field(C + '$StoreImageInfo;', 'FileName', 'Ljava/lang/String;')
    legacy = method(C + ';', 'setJpegListener', C + '$JpegListener;')
    media = classes.get('Lcom/sony/scalar/provider/AvindexStore$Images$Media;')
    columns = classes.get('Lcom/sony/scalar/provider/AvindexStore$Images$ImageColumns;')
    index = media is not None and columns is not None and any(
        f.get_name() == 'EXTERNAL_CONTENT_URI' and f.get_descriptor() == 'Landroid/net/Uri;' and f.get_access_flags() & 1
        for f in media.get_fields()) and {'DATA', 'REC_ORDER', 'DCF_FOLDER_NUMBER', 'DCF_FILE_NUMBER'}.issubset(
            {f.get_name() for f in columns.get_fields() if f.get_access_flags() & 1})
    preview_methods = {'createGammaTable', 'getExtendedGammaTable', 'setExtendedGammaTable', 'setRGBMatrix'}
    missing_preview = [r for r in required if not r['present'] and
                       ('$GammaTable;' in r['owner'] or r['method'] in preview_methods)]
    return {'system': str(root), 'inputs': inputs, 'required_methods': len(required),
            'missing': [r for r in required if not r['present']],
            'missing_capture_methods': [r for r in required if not r['present'] and r not in missing_preview],
            'native_color_preview_available': not missing_preview,
            'missing_fields': [f for f in fields if not f['present']], 'fields': fields,
            'named_store_callback': modern, 'legacy_jpeg_callback': legacy,
            'legacy_index_available': index,
            'store_protocol': 'named' if modern else 'indexed_file' if index else 'unsupported',
            'contracts': required}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('system_roots', nargs='+', type=Path)
    reports = [audit(p) for p in parser.parse_args().system_roots]
    print(json.dumps(reports, indent=2))
    raise SystemExit(int(any(r['missing'] or r['missing_fields'] or r['store_protocol'] == 'unsupported' for r in reports)))
