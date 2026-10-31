#!/usr/bin/env python3
"""Audit capture reflection contracts against locally extracted Sony ODEX files.

Requires androguard. Reads declarations and inheritance, never executes firmware.
No vendor binary is copied into the application or the repository.
"""
import argparse
import json
import struct
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
    for name in ['sony.cameraex', 'framework', 'com.sony.scalar.hardware.avio.DisplayManager',
                 'com.sony.scalar.sysutil.didep.Gpelibrary', 'com.sony.scalar.sysutil.Property',
                 'com.sony.scalar.database_library']:
        raw = (root / 'framework' / (name + '.odex')).read_bytes()
        offset, length = struct.unpack_from('<II', raw, 8)
        dex = DEX(raw[offset:offset + length])
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
    modern = method(C + ';', 'setStoreImageCompleteListener', C + '$StoreImageCompleteListener;')
    legacy = method(C + ';', 'setJpegListener', C + '$JpegListener;')
    media = classes.get('Lcom/sony/scalar/provider/AvindexStore$Images$Media;')
    columns = classes.get('Lcom/sony/scalar/provider/AvindexStore$Images$ImageColumns;')
    index = media is not None and columns is not None and any(
        f.get_name() == 'EXTERNAL_CONTENT_URI' and f.get_descriptor() == 'Landroid/net/Uri;' and f.get_access_flags() & 1
        for f in media.get_fields()) and {'DATA', 'REC_ORDER', 'DCF_FOLDER_NUMBER', 'DCF_FILE_NUMBER'}.issubset(
            {f.get_name() for f in columns.get_fields() if f.get_access_flags() & 1})
    return {'system': str(root), 'required_methods': len(required),
            'missing': [r for r in required if not r['present']],
            'named_store_callback': modern, 'legacy_jpeg_callback': legacy,
            'legacy_index_available': index,
            'store_protocol': 'named' if modern else 'indexed_file' if index else 'unsupported',
            'contracts': required}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('system_roots', nargs='+', type=Path)
    reports = [audit(p) for p in parser.parse_args().system_roots]
    print(json.dumps(reports, indent=2))
    raise SystemExit(int(any(r['missing'] or r['store_protocol'] == 'unsupported' for r in reports)))
