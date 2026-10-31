#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
serial="${1:?Usage: test_legacy_emulator.sh EMULATOR_SERIAL}"
sdk="${ANDROID_SDK:-/opt/homebrew/share/android-commandlinetools}"
java_home="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
adb="$sdk/platform-tools/adb"
if [[ "$("$adb" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
    echo 'This runner only supports an Android emulator.' >&2; exit 1
fi
apk="$("$adb" -s "$serial" shell pm path org.hongen.sonylutprobe | tr -d '\r' | sed 's/^package://')"
if [[ ! "$apk" =~ ^/data/app/org\.hongen\.sonylutprobe-[0-9]+\.apk$ ]]; then
    echo 'Install the matching app build and open the LUT library first.' >&2; exit 1
fi
mkdir -p out/legacy-smoke/classes out/legacy-smoke/dex
"$java_home/bin/javac" --release 7 -Xlint:-options \
    -cp "out/apk/classes:$sdk/platforms/android-36/android.jar" \
    -d out/legacy-smoke/classes android-tests/LegacyRuntimeSmoke.java \
    android-tests/CaptureStoreEventsTest.java android-tests/LegacyStoreCompletionTest.java android-tests/PhotoIndexTest.java android-tests/CaptureStartupErrorTest.java
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
"$adb" -s "$serial" shell "CLASSPATH=/data/local/tmp/legacy-smoke.jar:$apk app_process -Djava.library.path=/data/data/org.hongen.sonylutprobe/lib /system/bin org.hongen.sonylutprobe.LegacyRuntimeSmoke /mnt/sdcard/SONYLUT/LIBRARY" | tee out/legacy-smoke/result.txt
grep -q 'PASS legacy startup allocations count=' out/legacy-smoke/result.txt
for suite in CaptureStoreEventsTest LegacyStoreCompletionTest PhotoIndexTest CaptureStartupErrorTest; do
    "$adb" -s "$serial" shell "CLASSPATH=/data/local/tmp/legacy-smoke.jar:$apk app_process /system/bin org.hongen.sonylutprobe.$suite /data/local/tmp" | tee "out/legacy-smoke/$suite.txt"
    grep -q '^PASS ' "out/legacy-smoke/$suite.txt"
done
