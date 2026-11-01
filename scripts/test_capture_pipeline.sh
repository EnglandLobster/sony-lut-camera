#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
java_home="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
mkdir -p out/capture-pipeline/classes out/capture-pipeline/assets
python3 scripts/stage_capture_maps.py out/capture-pipeline/assets
"$java_home/bin/javac" --release 7 -Xlint:-options -encoding UTF-8 -d out/capture-pipeline/classes \
    android/capture/CaptureGeometry.java android/capture/CaptureMemoryMap.java \
    android/capture/CaptureSetup.java android/capture/SimpleExposure.java android/capture/PreJpegGate.java \
    android/capture/PhotoFiles.java android/capture/ArwFile.java android/capture/CaptureStoreCompletion.java android/capture/CaptureRecoveryFence.java \
    android-tests/CapturePipelineTest.java android-tests/CaptureMemoryMapTest.java android-tests/CaptureStoreRpcTest.java
"$java_home/bin/java" -Xmx24m -cp out/capture-pipeline/classes org.hongen.sonylutprobe.CapturePipelineTest
"$java_home/bin/java" -Xmx24m -cp out/capture-pipeline/classes org.hongen.sonylutprobe.CaptureStoreRpcTest
"$java_home/bin/java" -Xmx24m -cp out/capture-pipeline/classes org.hongen.sonylutprobe.CaptureStoreRpcTest --write-fence out/capture-pipeline/process-fence
"$java_home/bin/java" -Xmx24m -cp out/capture-pipeline/classes org.hongen.sonylutprobe.CaptureStoreRpcTest --read-fence out/capture-pipeline/process-fence
python3 - <<'PY'
from pathlib import Path
root=Path('out/capture-pipeline')
# Public declaration fixtures match the differences observed in the firmware.
# Initializers throw: merely checking capabilities must not load Sony JNI.
head='package com.sony.scalar.hardware; public class CameraEx { static { if (System.currentTimeMillis()!=0) throw new AssertionError("camera initialized"); } '
modern='''public GammaTable createGammaTable(){throw new AssertionError();}
public GammaTable getExtendedGammaTable(){throw new AssertionError();}
public void setExtendedGammaTable(GammaTable g){throw new AssertionError();}
public static class ParametersModifier { public void setRGBMatrix(int[] m){throw new AssertionError();} }
public static class GammaTable {
 public int getSize(){throw new AssertionError();}
 public int write(java.io.InputStream s){throw new AssertionError();}
 public int read(byte[] b){throw new AssertionError();}
 public void release(){throw new AssertionError();}
 public void setPictureEffectGammaForceOff(boolean b){throw new AssertionError();}
}'''
for name,body in [('modern',modern),('legacy',''),('partial',modern.replace('setRGBMatrix','notAvailable'))]:
    folder=root/name;folder.mkdir(exist_ok=True);(folder/'CameraEx.java').write_text(head+body+'}')
PY
for fixture in modern legacy partial; do
    expected=false; [[ "$fixture" == modern ]] && expected=true
    "$java_home/bin/javac" --release 7 -Xlint:-options -d "out/capture-pipeline/$fixture" \
        "out/capture-pipeline/$fixture/CameraEx.java" android/preview/NativePreviewCapability.java android-tests/NativePreviewCapabilityTest.java
    "$java_home/bin/java" -Xmx24m -cp "out/capture-pipeline/$fixture" org.hongen.sonylutprobe.NativePreviewCapabilityTest "$expected"
done
if [[ "$#" == 0 ]]; then
    "$java_home/bin/java" -Xmx24m -cp out/capture-pipeline/classes org.hongen.sonylutprobe.CaptureMemoryMapTest out/capture-pipeline/assets
fi
if [[ "$#" == 1 ]]; then
    python3 - "$1" <<'PY'
import json,sys
from pathlib import Path
rows=json.loads(Path(sys.argv[1]).read_text())
profiles={
 'ILCE-7':('ILDCSC_MUSASHI8Gb',24,2,True),'ILCE-7M2':('ILDCSC_MUSASHI8Gb',24,1,False),
 'ILCE-7R':('ILDCSC_MUSASHI8Gb',36,2,True),'ILCE-7RM2':('ILDCSC_MUSASHI8Gb',42,0,True),
 'ILCE-7S':('ILDCSC_MUSASHI8Gb',12,0,True),'ILCE-7SM2':('ILDCSC_MUSASHI8Gb',12,0,True),
 'ILCE-5000':('ILDCSC_MUSASHI4Gb',20,1,False),'ILCE-5100':('ILDCSC_MUSASHI8Gb',24,1,False),
 'ILCE-6000':('ILDCSC_MUSASHI8Gb',24,1,False),'ILCE-6300':('ILDCSC_MUSASHI8Gb',24,1,False),
 'ILCE-6500':('ILDCSC_MUSASHI8Gb',24,1,True),
}
cases=[]
for row in rows:
    if row['status']!='extracted':continue
    model=row['model']
    if model.startswith('NEX-'):profile=('unknown',16,0,False)
    elif model in ['DSC-HX60V','DSC-HX400V']:profile=('DSCSC_KOJIRO4Gb',20,1,False)
    elif model.startswith('DSC-RX'):profile=('DSCSC_MUSASHI8Gb',20,0,True)
    else:profile=profiles[model]
    memory,pixels,revision,firmware=profile
    filename=f'lib{memory}{pixels}m_PictureEffectPlus_00_{revision:02d}.so'
    cases.append('\t'.join(map(str,[model,memory,pixels,str(Path(row['system'])/'etc/memmap'),filename,str(firmware).lower()])))
Path('out/capture-pipeline/firmware-cases.tsv').write_text('\n'.join(cases)+'\n')
PY
    "$java_home/bin/java" -Xmx24m -cp out/capture-pipeline/classes org.hongen.sonylutprobe.CaptureMemoryMapTest \
        out/capture-pipeline/assets out/capture-pipeline/firmware-cases.tsv
fi
