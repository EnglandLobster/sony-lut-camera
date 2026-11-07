# Current status

Current release: **v0.9.0-beta.7-rc.10 / code117**, experimental prerelease. Bodies without a named store receipt (A6000, A7, A7R, A5000, NEX-5R/5T/6, HX60V/HX400V) no longer wait for the original's index record before the graded store: the exact predicted DCF JPEG, absent before the store and complete with the expected geometry after RPC return, confirms it. Not yet device-tested on those bodies; the A6300 named-receipt path is unchanged from rc.9. See [release details](RELEASE.md) and [findings](compatibility/LEGACY_STORE_PREDICTION.md).

## Earlier validation records

rc.9: **v0.9.0-beta.7-rc.9 / code116**, experimental prerelease. On A6300 firmware 2.01, a verified native completion receipt and RPC return allow confirmation by JPEG path and dimensions. Two physical captures on the matching fast-save candidate displayed in 5.26 s and 4.78 s, saved both JPEGs, and rearmed. See [release details](RELEASE.md).
# Current status

2026-11-03 correction: rc.7 still discards valid nonzero native store receipt identifiers. The actual released Activity callback reproduces a second-capture stall with receipt 1 and an unpublished pair index. Local **0.9.0-beta.7-rc.7-fix1 / code114** removes that gate and permits Sony to create an initially absent DCIM. This candidate is not published and physical capture acceptance is pending. See [incident findings](compatibility/RC7_RECEIPT_REGRESSION.md); the previous rc.7 validation below missed the Activity callback filter.

Current release: **v0.9.0-beta.7-rc.7 / code113**, experimental prerelease, 2026-11-03. Fixes an application regression introduced in rc.3: the original photo's native success callback was incorrectly gated on index publication before submitting the second declared record. The PR #2 renderer fix is retained. [Evidence, reproduction and fix](compatibility/PAIR_STORE_REGRESSION.md).

Named success now confirms the exact stable/complete JPEG after RPC return. Missing callbacks use a separate single-flight index worker with confirmed-DCF exclusions; stop requests drain the already-declared pair before teardown. Native status codes, query-worker waits and errors are retained. Imports accept root LUTS/LUT folder aliases. The rc.6 runtime memory-map selection and all full-resolution LUT processing remain.

Host and final API10/24MiB tests pass, including delayed pair-index publication, blocked index reads/snapshots, stop/drain, lost/duplicate receipts, native-call ownership, 96 graphics cycles and four previously saved Sony JPEGs checked on the host. This release has not been physically accepted on A5100/A6300. User screenshots lack an exact app version; the independently reproduced regression is the leading explanation, not a measured native cause of every card-recovery prompt.

Published compatibility test package: **0.9.0-beta.7-rc.3 / code109**, not installed on a camera in this round. The [store RPC recovery implementation](compatibility/STORE_RPC_RECOVERY.md) isolates file/index observation from blocked Sony calls, requires both disk confirmation and RPC return, and permits timeout return to the library while retaining native ownership. A private cross-process fence prevents reopening until cleanup or a new kernel boot. Twenty firmware paths and sixty original ARM IPC scenarios were checked. The [12–42MP implementation](compatibility/IMPLEMENTATION.md) and [firmware matrix](compatibility/README.md) remain active; physical acceptance is pending.

- A6300 / firmware 2.01: two physical captures on the rc.6 candidate; RAW/RAW+JPEG, burst/bracket, size, M-mode, HDR guidance and settings restoration tested on rc.7; bounded logs and repeat export tested on rc.8; export/menu pagination tested on rc.9. Exact records: [capture](A6300_RC6_ACCEPTANCE.md), [settings](A6300_SETTINGS_ACCEPTANCE.md), [diagnostics](DIAGNOSTICS.md).
- Beta.6 promotes the rc.9 implementation with a new version name/code. Final release payload comparison and installation checks are recorded in RELEASE.md; earlier physical capture tests are not relabeled as shots taken with the final APK.
- A6000 / A7 first-generation compatibility fixes have host/API10 and official-firmware interface evidence. Physical shooting on those models remains pending; see [legacy validation](LEGACY_OFFLINE_VALIDATION.md).
- Default diagnostics retain bounded UI/capture history privately and on SD; MENU → export produces REPORT.ZIP without photos, LUT payloads or network uploads.

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
- The current test APK and source package are linked in RELEASE.md. Third-party provenance and license facts are retained in ASSET_LICENSES.md.

Raw logs/photos, prior research and installation records are retained locally under ignored `results/` and `HANDOFF.md`; they are not public repository contents.
