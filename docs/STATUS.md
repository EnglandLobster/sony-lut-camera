# Current status

2026-11-01 diagnostics candidate: **0.9.0-beta.6-rc.8 / code104** adds bounded private/SD event journals, retained previous capture attempts, Java failure reporting and verified ZIP export from the normal menu. Host failure/rotation tests and API10 restart/forced capture-process interruption/export passed. A6300 firmware 2.01 passed startup and two consecutive on-device exports, including Sony SD exclusive-reader handling; settings were restored and the camera was left in the LUT library. No new exposure was taken for this logging change. See [diagnostics](DIAGNOSTICS.md). Public Release remains beta.5.

Compatibility branch update 2026-11-01: **0.9.0-beta.6-rc.7 / code103** uses Surface lifecycle callbacks instead of `Surface.isValid()` to authorize opening, adds bounded waiting, and selects the highest advertised known Sony JPEG tier with an accurate HUD label. The A6000 false-valid flag and 50/25 quality list are user-reported conditions reproduced as software fixtures, not measurements from our emulator. Host and API10 ARM tests cover these fixtures. The rc.6 build on A6300 firmware 2.01 passed two physical captures, adaptive EXTRA FINE selection, capture re-entry and preview/focus transitions; A6000/A7 physical acceptance remains pending. See [A6300 acceptance](A6300_RC6_ACCEPTANCE.md). The rc.7 candidate adds a visible explanation of automatically adjusted capture settings. A6300 RAW/RAW+JPEG, burst/bracket, small size, manual exposure and HDR-art blocking have been exercised; native-menu Auto HDR also remains on the explicit HDR error page. An initial apparent exit was traced to the test harness pressing the retained Exit menu item, not capture startup; the corrected harness passed the real HDR startup check. The user confirmed the native HDR message, restored DRO Auto, and the final normal startup with hdr=off passed. All temporary settings were restored, and the camera was left in the LUT library. See [settings acceptance](A6300_SETTINGS_ACCEPTANCE.md). Public release is still beta.5. See [legacy validation](LEGACY_OFFLINE_VALIDATION.md).

Updated 2026-10-31. Release candidate **0.9.0-beta.5 / code 96**, Sony A6300 firmware 2.01. The current source and release builds have passed host checks. Beta.5 was installed with IMG_7554.jpeg as the LUT selector reference; the camera is now on rc.8/code104 after diagnostics acceptance; Fuji C200 remains the default. Public downloads and the release workflow are in [RELEASE.md](RELEASE.md).

## Preserved product baseline

- Full CUBE processing, 6000×4000 EXTRA FINE and separate original/graded JPEGs were tested on A6300. Bundled and imported LUTs retain their full grids.
- Automatic preview fitting and recovery were physically exercised with imported LUTs. Preview uses native matrix/Gamma approximation; half-press restores the baseline before capture.
- The user confirmed the 0.8.19 display-banding repair: Sony OSD output is ABGR8888 during app display, restored on pause. Saved photographs were unchanged.
- 0.8.20–0.8.22 were installed and the library and photographic preview were visually checked. The enlarged reference preserves full portrait composition.
- The optional connector 0.6 was physically verified: native exit and same-process reopening reconnect correctly. Ordinary installations do not require it.

## Release-mode checks

The release APK builds successfully, disables debugging and exposes only the three product Activities. Diagnostics, synthetic test JPEG/LUT payloads and legacy preview profiles are excluded. The complete capture base class remains; production native libraries, memory configuration and photo quality settings are unchanged.

Independent source-export builds were checked for the existing toolchain. The previous baseline passed debug/release builds and preview-fit tests. Beta.4 passes the release build, product and upgrade-migration tests, exact LUT-cache tests and source/APK resource-boundary checks. Production native libraries and memory-map bytes match the previously installed build. The catalog contains only ten maintainer-selected LUTs, preserving seven 25³ and three 33³ source grids. Fuji C200 is the default. Upgrade migration removes only the two former mathematical samples by exact content identity and cleans their caches; other imports and valid selections remain.

Host numerical/JPEG tests, Java product tests, exact LUT-cache tests, preview-fit tests, catalog/hash validation and APK API10/DEX035/ARM/v1 checks pass. The source history audit found no tracked APKs, signing keys, saved Wi-Fi profiles, firmware dumps or private device captures. The explicitly bundled preview thumbnail and attributed LUTs remain.

## Device acceptance and remaining scope

- 2026-10-31 beta.5/code96: replaced the reference with the maintainer-selected yellow-car photograph, oriented and converted from Display P3 to a 216×288 sRGB JPEG without EXIF. All ten LUTs rendered it successfully on the host; release package checks passed. The camera screenshot confirms the new reference in the selector, with 17 library entries and unchanged boot identity.

- 2026-10-31 beta.4/code95: upgrade removed exactly the two sample content IDs and their source caches. All other library IDs and metadata stayed unchanged (19 → 17 entries); all ten bundled LUTs remain available. Startup completed with Fuji C200 selected, and the boot identity stayed unchanged. No new shutter capture was performed.

- 2026-10-31: updated A6300 from beta.2 to beta.3 / code 94 while the development connector was foregrounded. Startup imported the new catalog successfully: all 12 bundled SHA-256 identities and grid sizes are present, with 19 total library entries including existing imports. Library startup completed and the boot identity stayed unchanged. No new shutter capture was performed for this resource update.

- The earlier release-mode installation, library, preview fitting and two physical captures passed on A6300. The user confirmed half-press return and original/graded comparison. Both shots saved separate 6000×4000 EXTRA FINE JPEGs; boot identity stayed unchanged.
- A first install without any development connector, broader SD-card/library sizes, and other camera models remain unaccepted.
- Hardware preview color-order/domain calibration  are incomplete. The fitter's reported error is a software-model metric.
- The public beta.5 APK and source package are linked in RELEASE.md. Third-party provenance and license facts are retained in ASSET_LICENSES.md.

Raw logs/photos, prior research and installation records are retained locally under ignored `results/` and `HANDOFF.md`; they are not public repository contents.
