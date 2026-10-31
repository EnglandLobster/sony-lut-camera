#!/usr/bin/env python3
"""Static TrueType for the camera; includes common Chinese LUT names and all UI text.
Source: pinned Noto Sans SC, SIL OFL. Follows the static-font approach reviewed
in Recipe Lab's tools/subset-font.py; no font is fetched during ordinary APK builds.
"""
from pathlib import Path
import hashlib,json
from fontTools import subset
from fontTools.varLib.instancer import instantiateVariableFont
ROOT=Path(__file__).resolve().parents[1]
source=ROOT/'vendor/font-source/NotoSansSC-VF.ttf'
expected='d68bafcb48a2707749396aa12bbbd833cb70401f3a9a689fd2902c7e0d295964'
assert hashlib.sha256(source.read_bytes()).hexdigest()==expected
chars=set(chr(c) for c in range(32,127))
for hi in range(0xA1,0xF8):
    for lo in range(0xA1,0xFF):
        try:chars.add(bytes([hi,lo]).decode('gb2312'))
        except UnicodeDecodeError:pass
for path in (ROOT/'android').rglob('*.java'):
    chars.update(c for c in path.read_text() if ord(c)>=32 and c not in '\ufeff')
options=subset.Options();options.layout_features=[];options.name_IDs=['*'];options.name_legacy=True;options.name_languages=['*']
options.notdef_glyph=True;options.notdef_outline=True;options.recommended_glyphs=True;options.recalc_bounds=True
font=subset.load_font(str(source),options)
# Subset before instancing to avoid processing unused variable outlines.
worker=subset.Subsetter(options=options);worker.populate(text=''.join(sorted(chars)));worker.subset(font)
instantiateVariableFont(font,{'wght':400},inplace=True)
family='Lut Camera Sans'
for nid,value in {1:family,2:'Regular',3:family+' Regular',4:family+' Regular',6:'LutCameraSans-Regular',16:family,17:'Regular'}.items():
    font['name'].setName(value,nid,3,1,0x409);font['name'].setName(value,nid,1,0,0)
missing=sorted(ord(c) for c in chars if ord(c) not in font.getBestCmap())
# GB2312 has a few spacing characters absent from Noto. UI text may not.
ui=set(''.join(p.read_text() for p in (ROOT/'android').rglob('*.java')))
assert not [c for c in ui if ord(c)>=32 and c!='\ufeff' and ord(c) not in font.getBestCmap()],missing
out=ROOT/'android-assets/UIFONT.TTF';font.save(out)
(ROOT/'android-assets/FONTINFO.json').write_text(json.dumps({'source_sha256':expected,'source_commit':'f8d157532fbfaeda587e826d4cd5b21a49186f7c','format':'static TrueType','glyphs':len(font.getBestCmap()),'bytes':out.stat().st_size,'sha256':hashlib.sha256(out.read_bytes()).hexdigest()},indent=2))
print(out,out.stat().st_size,'bytes; all UI glyphs present')
