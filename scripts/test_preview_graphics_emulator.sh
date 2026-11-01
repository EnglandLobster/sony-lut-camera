#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
serial="${1:?Usage: test_preview_graphics_emulator.sh API10_EMULATOR_SERIAL}"
sdk="${ANDROID_SDK:-/opt/homebrew/share/android-commandlinetools}"
adb="${ADB:-$sdk/platform-tools/adb}"
java_home="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
if [[ "$("$adb" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 || "$("$adb" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')" != 10 ]]; then
    echo 'This runner requires a local API10 emulator.' >&2; exit 1
fi
original_heap="$("$adb" -s "$serial" shell getprop dalvik.vm.heapsize | tr -d '\r')"
"$adb" -s "$serial" shell setprop dalvik.vm.heapsize 24m
trap '"$adb" -s "$serial" shell setprop dalvik.vm.heapsize "$original_heap"' EXIT
mkdir -p out/preview-graphics
for variant in baseline fixed; do
    stage="out/preview-graphics/$variant"
    mkdir -p "$stage/src" "$stage/classes" "$stage/dex"
    for source in DevelopingPreview DevelopTimeline ReviewGeometry; do
        if [[ "$variant" == baseline ]]; then git show "v0.9.0-beta.7-rc.3:android/ui/$source.java" > "$stage/src/$source.java"
        else cp "android/ui/$source.java" "$stage/src/$source.java"; fi
    done
    "$java_home/bin/javac" --release 7 -Xlint:-options -encoding UTF-8 -cp "$sdk/platforms/android-36/android.jar" \
        -d "$stage/classes" "$stage"/src/*.java android-tests/DevelopingPreviewTest.java android-tests/PreviewGraphicsRegression.java
    "$java_home/bin/jar" cf "$stage/classes.jar" -C "$stage/classes" .
    "$java_home/bin/java" -cp "$sdk/build-tools/36.0.0/lib/d8.jar" com.android.tools.r8.D8 --min-api 10 \
        --lib "$sdk/platforms/android-36/android.jar" --output "$stage/dex" "$stage/classes.jar"
    "$java_home/bin/jar" cf "$stage/runner.jar" -C "$stage/dex" classes.dex
    "$adb" -s "$serial" push "$stage/runner.jar" "/data/local/tmp/preview-$variant.jar"
done
cat > out/preview-graphics/run.sh <<'GUEST'
#!/system/bin/sh
set -e
CLASSPATH=/data/local/tmp/preview-baseline.jar app_process /system/bin org.hongen.sonylutprobe.PreviewGraphicsRegression baseline
CLASSPATH=/data/local/tmp/preview-fixed.jar app_process /system/bin org.hongen.sonylutprobe.PreviewGraphicsRegression fixed
echo PASS_GRAPHICS_REGRESSION
GUEST
"$adb" -s "$serial" push out/preview-graphics/run.sh /data/local/tmp/preview-graphics.sh
"$adb" -s "$serial" shell 'sh /data/local/tmp/preview-graphics.sh > /data/local/tmp/preview-graphics-result.txt 2>&1; cat /data/local/tmp/preview-graphics-result.txt' | tee out/preview-graphics/result.txt
grep -q '^PASS_BASELINE_CANVAS_NULL_REPRODUCED' out/preview-graphics/result.txt
grep -q '^PASS_FIXED_GRAPHICS' out/preview-graphics/result.txt
grep -q '^PASS_GRAPHICS_REGRESSION' out/preview-graphics/result.txt
