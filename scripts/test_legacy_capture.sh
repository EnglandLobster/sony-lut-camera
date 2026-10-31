#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
java_home="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
mkdir -p out/legacy-capture-tests
"$java_home/bin/javac" --release 7 -Xlint:-options -encoding UTF-8 -d out/legacy-capture-tests \
    android/capture/CaptureSurfaceGate.java android/capture/CaptureJpegQuality.java android/capture/CaptureStoreEvents.java android/capture/LegacyStoreCompletion.java \
    android/capture/PhotoFiles.java android/capture/ArwFile.java android/capture/PhotoIndex.java \
    android-tests/CaptureSurfaceGateTest.java android-tests/CaptureJpegQualityTest.java android-tests/CaptureStoreEventsTest.java android-tests/LegacyStoreCompletionTest.java android-tests/PhotoIndexTest.java
for suite in CaptureSurfaceGateTest CaptureJpegQualityTest CaptureStoreEventsTest LegacyStoreCompletionTest PhotoIndexTest; do
    "$java_home/bin/java" -cp out/legacy-capture-tests "org.hongen.sonylutprobe.$suite"
done
