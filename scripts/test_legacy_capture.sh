#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
java_home="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
mkdir -p out/legacy-capture-tests
"$java_home/bin/javac" --release 7 -Xlint:-options -encoding UTF-8 -d out/legacy-capture-tests \
    android/capture/CapturePlatform.java android-tests/CapturePlatformTest.java \
    android/capture/CaptureSurfaceGate.java android/capture/CaptureJpegQuality.java android/capture/CaptureStoreEvents.java \
    android/capture/CaptureGeometry.java android/capture/PhotoFiles.java android/capture/ArwFile.java android/capture/PhotoIndex.java \
    android-tests/CaptureSurfaceGateTest.java android-tests/CaptureJpegQualityTest.java android-tests/CaptureStoreEventsTest.java android-tests/PhotoIndexTest.java
for suite in CapturePlatformTest CaptureSurfaceGateTest CaptureJpegQualityTest CaptureStoreEventsTest PhotoIndexTest; do
    "$java_home/bin/java" -cp out/legacy-capture-tests "org.hongen.sonylutprobe.$suite"
done

bash scripts/test_capture_pipeline.sh
