#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
java_home="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
mkdir -p out/product-ui-tests
"$java_home/bin/javac" -encoding UTF-8 -d out/product-ui-tests \
  android/storage/DiagnosticStore.java android/storage/DiagnosticText.java android/ui/CaptureSettingsNotice.java android/ui/CaptureStartupError.java android/capture/CaptureGeometry.java android/capture/CaptureSetup.java android/storage/CaptureDiagnostics.java android/storage/LutLibrary.java android/storage/PhotoCatalog.java android/capture/PreJpegGate.java android/ui/ReviewGeometry.java android/capture/ParameterDriver.java android/capture/ManualExposure.java android/capture/FocusControls.java android/capture/ControlLayout.java android/capture/ViewfinderControls.java android/ui/UiKeyActions.java android/ui/PreviewCache.java android/ui/DevelopTimeline.java android/ui/UiModels.java android/capture/CaptureReadout.java android/preview/NativePreviewProfile.java android/preview/NativePreviewSession.java android/platform/NativeExitGate.java android/preview/PreviewCaptureBarrier.java android/preview/AutoPreviewGate.java android/preview/PreviewProfileStore.java \
  android-tests/DiagnosticStoreTest.java android-tests/CaptureSettingsNoticeTest.java android-tests/CaptureStartupErrorTest.java android-tests/CaptureSetupTest.java android-tests/BuiltinRetirementTest.java android-tests/LutRemovalTest.java android-tests/LutNameTest.java android-tests/ProductStorageTest.java android-tests/PreJpegGateTest.java android-tests/ReviewGeometryTest.java android-tests/ManualExposureTest.java android-tests/ViewfinderControlsTest.java android-tests/PreviewCacheTest.java android-tests/DevelopTimelineTest.java android-tests/UiModelsTest.java android-tests/CaptureReadoutTest.java android-tests/NativePreviewTest.java android-tests/NativeExitGateTest.java android-tests/PreviewCaptureBarrierTest.java android-tests/AutoPreviewGateTest.java android-tests/PreviewProfileStoreTest.java
for suite in DiagnosticStoreTest CaptureSettingsNoticeTest CaptureStartupErrorTest CaptureSetupTest BuiltinRetirementTest LutRemovalTest LutNameTest PreviewProfileStoreTest AutoPreviewGateTest PreviewCaptureBarrierTest NativeExitGateTest DevelopTimelineTest ProductStorageTest PreJpegGateTest ReviewGeometryTest ManualExposureTest ViewfinderControlsTest PreviewCacheTest UiModelsTest CaptureReadoutTest; do
  "$java_home/bin/java" -cp out/product-ui-tests "io.github.englandlobster.lutcamera.$suite"
done
python3 scripts/generate_preview_fixture.py
"$java_home/bin/java" -cp out/product-ui-tests io.github.englandlobster.lutcamera.NativePreviewTest out/tests/native-preview/IDENT.PRV
bash scripts/test_legacy_capture.sh
