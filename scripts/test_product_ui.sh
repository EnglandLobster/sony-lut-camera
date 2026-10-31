#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
java_home="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
mkdir -p out/product-ui-tests
"$java_home/bin/javac" -encoding UTF-8 -d out/product-ui-tests \
  android/ui/CaptureSettingsNotice.java android/ui/CaptureStartupError.java android/capture/CaptureSetup.java android/storage/CaptureDiagnostics.java android/storage/LutLibrary.java android/storage/PhotoCatalog.java android/capture/PreJpegGate.java android/ui/ReviewGeometry.java android/capture/SimpleExposure.java android/ui/PreviewCache.java android/ui/DevelopTimeline.java android/ui/UiModels.java android/capture/CaptureReadout.java android/preview/NativePreviewProfile.java android/preview/NativePreviewSession.java android/platform/NativeExitGate.java android/preview/PreviewCaptureBarrier.java android/preview/AutoPreviewGate.java android/preview/PreviewProfileStore.java \
  android-tests/CaptureSettingsNoticeTest.java android-tests/CaptureStartupErrorTest.java android-tests/CaptureSetupTest.java android-tests/BuiltinRetirementTest.java android-tests/ProductStorageTest.java android-tests/PreJpegGateTest.java android-tests/ReviewGeometryTest.java android-tests/SimpleExposureTest.java android-tests/PreviewCacheTest.java android-tests/DevelopTimelineTest.java android-tests/UiModelsTest.java android-tests/CaptureReadoutTest.java android-tests/NativePreviewTest.java android-tests/NativeExitGateTest.java android-tests/PreviewCaptureBarrierTest.java android-tests/AutoPreviewGateTest.java android-tests/PreviewProfileStoreTest.java
for suite in CaptureSettingsNoticeTest CaptureStartupErrorTest CaptureSetupTest BuiltinRetirementTest PreviewProfileStoreTest AutoPreviewGateTest PreviewCaptureBarrierTest NativeExitGateTest DevelopTimelineTest ProductStorageTest PreJpegGateTest ReviewGeometryTest SimpleExposureTest PreviewCacheTest UiModelsTest CaptureReadoutTest; do
  "$java_home/bin/java" -cp out/product-ui-tests "org.hongen.sonylutprobe.$suite"
done
python3 scripts/generate_preview_fixture.py
"$java_home/bin/java" -cp out/product-ui-tests org.hongen.sonylutprobe.NativePreviewTest out/tests/native-preview/IDENT.PRV
bash scripts/test_legacy_capture.sh
