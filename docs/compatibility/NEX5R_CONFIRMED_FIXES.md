# NEX-5R: confirmed startup and exposure-menu fixes

2026-11-27. These two changes address viewfinder startup and software mode
selection. **NEX-5R capture remains unresolved**: no successful full-LUT photo
has been obtained. Host tests and APK builds are not real-photo acceptance.

## JPEG quality fallback

Before the fix, the owner's NEX-5R 1.00 / Sony platform 1.0 reported current
JPEG quality 50 but no `jpeg-quality-values`. Startup stopped with
`JPEG quality capabilities unavailable` before reaching the viewfinder.

Sony's `Environment` interprets the second platform-version component as the
API level. `PictureQualityController` uses FINE=50 / STANDARD=25 on API 0/1;
quality enumeration and EXTRA FINE belong to the newer path. `CaptureJpegQuality`
now selects FINE only when that legacy platform is known and the list is
absent. An advertised list takes precedence. Unknown/newer platforms still
report a missing capability, and writes retain exact readback verification.

Evidence: the owner confirmed viewfinder startup with local `nex5r.1`.
Subsequent capture journals repeatedly reach ready on 1.00/platform 1.0;
`LOGS-9` also confirms startup and FINE50 on 1.03/platform 1.1. These are startup
observations, not successful shooting. Original evidence remains locally in
`external/5r/SONYLUT/` and `external/5r/LOGS-*`.

Host regression: `CaptureJpegQualityTest` covers both legacy API levels,
advertised tiers, malformed/unknown/newer platforms and rejected readback.

## Software exposure-mode selection

The owner could not find the exposure-mode setting on the dial-less NEX-5R.
The old code treated a readable mode-status key as physical dial hardware.
Sony's `ModeDialDetector.hasModeDial` instead checks the supported positions
in `ScalarProperties.getIntArray("input.mode.sw.mode.list")`.

`CameraInputs.modeDial` now uses that declaration. Mode notifications without
declared hardware no longer hide software P/A/S/M selection.

Evidence: `nex5r.2` journals report `mode_switch_positions=null` and
`physical_dial=false`, followed by software A/S selections (`LOGS-2/CAP2.TXT`,
session `92403637-6434-4bf0-9936-e2b19f15a2f1`, events 69/71/72). Hardware-dial
behavior and all four software modes have host coverage; this is not a claim
of device acceptance for every mode or camera.

Host regression: `CameraInputsTest` supplies a valid logical mode key on a
body without a dial, exercises the visible P/A/S/M menu and mode restoration,
and covers declared hardware and an unavailable property. It runs through
`scripts/test_product_ui.sh`.

## Cleanup scope

The owner requested retaining only confirmed fixes. The native-map delivery,
software shutter, sequence options and explicit-media experiments did not
establish a capture fix. Temporary diagnostic listeners/logcat collection,
READ_LOGS and candidate version gates were also removed from this changeset.
The existing DirectShutter and asset-map delivery paths are used again.

Complete experimental sources, tests and investigation notes are preserved
locally under `results/nex5r-experiments-20261127-cleanup/`; original evidence
and versioned candidate APKs remain in `external/` and `dist/`. These directories
are ignored. Reference-image colour and UI/import performance remain open.

After cleanup, `scripts/test_product_ui.sh` (including legacy and capture
pipeline suites) and the release-mode Android build pass. APK inspection
confirms API10/DEX035/ARM32/v1 signing/alignment, no experimental classes,
no READ_LOGS permission, and asset-only capture maps. Logs are local at
`out/confirmed-fixes-tests.log` and `out/confirmed-fixes-build.log`.
This rebuilt APK has not been installed or used for a real photograph.
