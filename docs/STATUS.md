# Current status

Updated 2026-10-31. Release candidate **0.9.0-beta.3 / code 94**, Sony A6300 firmware 2.01. The current source and release builds have passed host checks; 0.9.0-beta.3 with the expanded LUT catalog is installed on the camera. Publication status and the external resource hold are in [RELEASE.md](RELEASE.md).

## Preserved product baseline

- Full CUBE processing, 6000×4000 EXTRA FINE and separate original/graded JPEGs were tested on A6300. Built-in examples and user 33³ LUTs retain the full grid.
- Automatic preview fitting and recovery were physically exercised with imported LUTs. Preview uses native matrix/Gamma approximation; half-press restores the baseline before capture.
- The user confirmed the 0.8.19 display-banding repair: Sony OSD output is ABGR8888 during app display, restored on pause. Saved photographs were unchanged.
- 0.8.20–0.8.22 were installed and the library and photographic preview were visually checked. The enlarged reference preserves full portrait composition.
- The optional connector 0.6 was physically verified: native exit and same-process reopening reconnect correctly. Ordinary installations do not require it.

## Release-mode checks

The release APK builds successfully, disables debugging and exposes only the three product Activities. Diagnostics, synthetic test JPEG/LUT payloads and legacy preview profiles are excluded. The complete capture base class remains; production native libraries, memory configuration and photo quality settings are unchanged.

Independent source-export builds were checked for the existing toolchain. The current candidate passes both debug and release builds, product/preview-fit tests and source/APK resource-boundary checks. Production native libraries and memory-map bytes match the previously installed build. The catalog now also includes ten maintainer-selected LUTs, preserving seven 25³ and three 33³ source grids; the original two mathematical examples and default remain.

Host numerical/JPEG tests, Java product tests, exact LUT-cache tests, preview-fit tests, catalog/hash validation and APK API10/DEX035/ARM/v1 checks pass. The source history audit found no tracked APKs, signing keys, saved Wi-Fi profiles, firmware dumps or private device captures. The explicitly bundled preview thumbnail and attributed LUTs remain.

## Device acceptance and remaining scope

- 2026-10-31: updated A6300 from beta.2 to beta.3 / code 94 while the development connector was foregrounded. Startup imported the new catalog successfully: all 12 bundled SHA-256 identities and grid sizes are present, with 19 total library entries including existing imports. Library startup completed and the boot identity stayed unchanged. No new shutter capture was performed for this resource update.

- The earlier release-mode installation, library, preview fitting and two physical captures passed on A6300. The user confirmed half-press return and original/graded comparison. Both shots saved separate 6000×4000 EXTRA FINE JPEGs; boot identity stayed unchanged.
- A first install without any development connector, broader SD-card/library sizes, and other camera models remain unaccepted.
- Hardware preview color-order/domain calibration  are incomplete. The fitter's reported error is a software-model metric.
- The external Sony memory-map redistribution basis remains unresolved. No public APK release is claimed.

Raw logs/photos, prior research and installation records are retained locally under ignored `results/` and `HANDOFF.md`; they are not public repository contents.
