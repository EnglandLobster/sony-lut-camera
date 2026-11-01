#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
serial="${1:?Usage: test_legacy_emulator.sh EMULATOR_SERIAL}"
sdk="${ANDROID_SDK:-/opt/homebrew/share/android-commandlinetools}"
java_home="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
adb="${ADB:-$sdk/platform-tools/adb}"
if [[ "$("$adb" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
    echo 'This runner only supports an Android emulator.' >&2; exit 1
fi
if [[ "$("$adb" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')" != 10 ]]; then
    echo 'This runner requires API10.' >&2; exit 1
fi
# API10 AndroidRuntime overwrites -Xmx with this system property. Set it on
# the already-verified emulator only, and restore the old value on exit.
original_heap="$("$adb" -s "$serial" shell getprop dalvik.vm.heapsize | tr -d '\r')"
"$adb" -s "$serial" shell setprop dalvik.vm.heapsize 24m
trap '"$adb" -s "$serial" shell setprop dalvik.vm.heapsize "$original_heap"' EXIT
apk="$("$adb" -s "$serial" shell pm path org.hongen.sonylutprobe | tr -d '\r' | sed 's/^package://')"
if [[ ! "$apk" =~ ^/data/app/org\.hongen\.sonylutprobe-[0-9]+\.apk$ ]]; then
    echo 'Install the matching app build and open the LUT library first.' >&2; exit 1
fi
mkdir -p out/legacy-smoke/classes out/legacy-smoke/dex
# Avoid stale classes from removed suites entering the runner DEX.
python3 - <<'PY_CLEAN'
from pathlib import Path
for path in Path('out/legacy-smoke/classes').rglob('*.class'):
    path.unlink()
PY_CLEAN
"$java_home/bin/javac" --release 7 -Xlint:-options \
    -cp "out/apk/classes:$sdk/platforms/android-36/android.jar" \
    -d out/legacy-smoke/classes android-tests/LegacyRuntimeSmoke.java \
    android-tests/CaptureSurfaceGateTest.java android-tests/CaptureJpegQualityTest.java android-tests/CaptureStoreEventsTest.java android-tests/CapturePipelineTest.java android-tests/CaptureStoreRpcTest.java android-tests/PhotoIndexTest.java android-tests/CaptureStartupErrorTest.java
find out/legacy-smoke/classes -name '*.class' > out/legacy-smoke/classes.txt
"$java_home/bin/jar" cf out/legacy-smoke/app-classes.jar -C out/apk/classes .
"$java_home/bin/java" -cp "$sdk/build-tools/36.0.0/lib/d8.jar" com.android.tools.r8.D8 \
    --min-api 10 --lib "$sdk/platforms/android-36/android.jar" \
    --classpath out/legacy-smoke/app-classes.jar --output out/legacy-smoke/dex \
    @out/legacy-smoke/classes.txt
python3 - <<'PY'
import zipfile
with zipfile.ZipFile('out/legacy-smoke/runner.jar', 'w') as archive:
    archive.write('out/legacy-smoke/dex/classes.dex', 'classes.dex')
PY
"$adb" -s "$serial" push out/legacy-smoke/runner.jar /data/local/tmp/legacy-smoke.jar
# One guest shell avoids repeatedly forking the legacy adbd for every suite.
# Keep output on the guest as well, so a lost host channel is distinguishable.
python3 - "$apk" <<'PY_RUNNER'
from pathlib import Path
import sys
apk=sys.argv[1]
lines=['#!/system/bin/sh','set -e','export CLASSPATH=/data/local/tmp/legacy-smoke.jar:'+apk,
       'app_process -Xmx24m -Djava.library.path=/data/data/org.hongen.sonylutprobe/lib /system/bin org.hongen.sonylutprobe.LegacyRuntimeSmoke /mnt/sdcard/SONYLUT/LIBRARY']
for suite in ['CaptureSurfaceGateTest','CaptureJpegQualityTest','CaptureStoreEventsTest','CapturePipelineTest','CaptureStoreRpcTest','PhotoIndexTest','CaptureStartupErrorTest']:
    lines.append('app_process -Xmx24m /system/bin org.hongen.sonylutprobe.'+suite+' /data/local/tmp')
for action in ['--write-fence','--read-fence']:
    lines.append('app_process -Xmx24m /system/bin org.hongen.sonylutprobe.CaptureStoreRpcTest '+action+' /data/local/tmp/lut-store-fence-test')
lines.append('echo PASS_ALL_API10_SUITES')
Path('out/legacy-smoke/run.sh').write_text('\n'.join(lines)+'\n')
PY_RUNNER
"$adb" -s "$serial" push out/legacy-smoke/run.sh /data/local/tmp/legacy-smoke.sh
"$adb" -s "$serial" shell "sh /data/local/tmp/legacy-smoke.sh > /data/local/tmp/legacy-smoke-result.txt 2>&1; cat /data/local/tmp/legacy-smoke-result.txt" | tee out/legacy-smoke/result.txt
grep -q 'PASS_ALL_API10_SUITES' out/legacy-smoke/result.txt
