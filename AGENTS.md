# Repository Guidelines

## Scope and Structure

This repository contains LUT Camera and its optional development connector for Sony PMCA cameras. The verified body is an A6300 on firmware 2.01. Read `README.md`, `PRODUCT_UI.md`, `docs/STATUS.md`, and `docs/ARCHITECTURE.md` before changing behavior. Local `HANDOFF.md` and `results/` may exist but are intentionally untracked.

Java lives in `android/{app,capture,preview,storage,ui,platform,diagnostics}/`; all classes retain the same package. Native C++/JNI lives in `native/`, Java tests in `android-tests/`, assets/resources in `android-assets/` and `android-res/`, and the separate helper in `dev-connect/`. `PreJpegProbeActivity` remains a production base class despite its name.

## Multi-App Workspace Integration

The canonical workspace location is `apps/sony-pmca/lut-camera/`; this repository must also build when cloned elsewhere. Keep its own Git history and pinned dependencies. Do not reference sibling apps or mutable reference clones. Shared Sony runtime/helper extraction requires a second real consumer and preserved tests; until then `dev-connect/` stays here. Photographic color/theme variants should reuse the existing engine rather than duplicate capture code. Workspace-level moves must update its external project index and compatibility paths without adding those local files to this repository.

## Build and Test

Follow `docs/BUILD.md` for pinned dependencies and toolchains. Run commands from the repository root:

```sh
python3 scripts/bootstrap_dependencies.py --check
bash scripts/build_host.sh
python3 scripts/test_offline.py
bash scripts/test_product_ui.sh
bash scripts/test_lut_cache.sh
bash scripts/test_preview_fit.sh
bash scripts/build_android.sh
```

Use Java 7/API10 compatibility and C++11. Match nearby four-space formatting, PascalCase classes, camelCase Java methods, and snake_case scripts. Tests use `*Test.java`, native test executables, and Python assertions. Validate the changed contract; keep host, build, device and physical acceptance distinct.

## Capture and UI Contracts

Preserve full 33³ processing and separate original/graded files. A6300 retains 6000×4000 and the original MAP24R1 bytes; other profiles use their exact platform/pixel map and negotiated full native-ratio geometry throughout settings, YC, JPEG validation and review. Product capture selects the highest advertised known Sony JPEG tier (95 EXTRA FINE, 50 FINE, 25 STANDARD), displays the selected tier, verifies it across registration/rearm, and restores the original quality on exit. Never claim EXTRA FINE on a body that only advertises FINE. Explicit EXTRA FINE diagnostic probes retain their strict requirement. Half-press restores the exact preview baseline before capture. For device capture tests in scope, automation may operate the camera buttons, including half-press and full-press, through a control path verified for the connected camera. Wait for capture readiness and preview baseline restoration before full-press, and wait for both saves and rearm before triggering another shot. Record the trigger method and confirm the resulting files. Before installing, safely leave capture and foreground the development connector. No firmware, USB or C2 experiments without a specific request.

Review uses left/older, right/newer, center comparison, half-press shooting. Preserve full image geometry and the Sony high-precision graphics lifecycle.

## Cross-model compatibility

Use `docs/compatibility/README.md` and the source-addressed firmware audit before changing model support. Android `Build.MODEL` is commonly `ScalarA`; use Sony's `PROP_MODEL_NAME` for physical model identity. The original-save/LUT overlap optimization is device-verified only on ILCE-6300 firmware 2.01; other product sessions wait for original confirmation. A shared API or firmware memory string does not authorize reusing the 24MP map for a different sensor layout. Keep native preflight ahead of capture and defer teardown during startup allocations. Run `scripts/test_capture_compatibility.sh` for mapping/cache/geometry changes. Firmware packages stay under ignored results, never in source/APK assets.

`config/capture-maps.json` pins bundled map identities and records reviewed firmware fingerprints; bundled inputs must also be pinned in `dependencies.lock.json`. Do not use collected firmware hashes as a runtime device whitelist. Follow Sony's exact platform/pixel/name/revision selection for camera-resident maps, validating the trusted system path and file structure and recording observed hashes. Preserve existing corrected bundled maps and known revision floors. `config/compatibility-targets.json` tracks the full Recipe Lab scope, not a shooting allowlist. `CaptureStoreCompletion` owns receipt generations and index watermarks. A successful named receipt and RPC return permit the next store once the target JPEG passes validation. A6300 firmware 2.01 can use the verified file path and JPEG dimensions for named receipts. Other firmware and index recovery require full JPEG structure validation. Never require the original to enter the index before submitting the second declared record: Sony may publish the index only after the pair. Missing callbacks use read-only index recovery, excluding callback-confirmed DCF identities, never a duplicate store. Bodies without a named receipt may also confirm the exact next DCF identity predicted from the session's last confirmed file or the pre-store index head, plus those after the highest DCF number present in that base's folder when it differs (a phantom or stale index head): only identities absent before submission, only after RPC return, exactly one, with full JPEG structure and geometry validation (`LegacyDcfPredictionTest`). Never use prediction on named-receipt bodies or turn it into a scan of unrelated photos. Do not abandon/release unconfirmed encoder buffers on a timer; a completed but wrong-sized JPEG is a confirmed terminal failure. Cover both save roles, lost/late/duplicate callbacks, early index/file events, stopping, corrupt files and wrong geometry with `scripts/test_capture_pipeline.sh`.

`CaptureStoreCompletion` also requires RPC return before consuming a confirmed file. The independent store observer must never call Sony mutators or advance the capture gate directly. Wait for RPC return before file confirmation. The normal named path checks its exact target without querying the index. Recovery uses one independent, bounded-queue index worker; a slow query must not block late named receipt handling. Snapshot timeout may disable optional recovery on named-protocol bodies, but legacy-only capture must have a safe pre-store watermark. Product capture must not take whole-DCIM snapshots or use directory scans as a polling fallback. `CaptureRecoveryFence` survives process death within the same kernel boot; only the owner after native cleanup may clear it. Timeout may detach the UI, never interrupt/kill a pending RPC or release its arguments. A stop request prevents new captures, but must drain the already-declared RECORD_COUNT=2 pair before resource teardown; do not abandon the second record after the original completes. Keep API10 blocked-worker and actual cross-process fence tests in the capture/emulator runners.

## Diagnostics

`CameraEx.StoreImageCompleteListener.onDone`'s integer must not be treated as a zero-success status. Real A6300 completed pairs carry 0, then 1, then 2. Keep it as an opaque receipt identifier for diagnostics; the named file, RPC return and JPEG validation determine completion. `scripts/test_store_receipt_dispatch.py` runs the actual Activity callback body, in addition to state-machine fixtures, so an adapter-side filter cannot escape coverage. Product photo observation must tolerate DCIM being absent before Sony creates the first photo; do not create native photo/index directories from the app.

Production diagnostics use bounded UI/CAP journals in private storage and on SD. Keep logging failures outside capture control flow; never include photos, LUT payloads, credentials or unfiltered system logcat in user exports. Sony SD does not permit reading an actively written file: close both writers under the export lock, validate the archive, and resume append without rotating away the current session. Test retained failure history, bounds, failed-export preservation and API10 compatibility when changing this contract. See `docs/DIAGNOSTICS.md`.

## Maintainer Git Identity

Before committing from any clone or worktree, explicitly set repository-local `user.name` to `EnglandLobster` and `user.email` to `93986837+EnglandLobster@users.noreply.github.com`; never inherit an unrelated global identity. Set `core.hooksPath` to `.githooks` and run `python3 scripts/check_git_identity.py`. Check both Author and Committer before pushing. Do not publish identity overrides from another service. This is separate from the APK signing identity, which remains unchanged.

## Changes and Sensitive Files

Use focused commits such as `fix(preview): restore display precision`. PRs explain behavior, tests, device evidence and unresolved acceptance. Do not commit SDKs, APKs, logs, private device photos, user LUT collections, credentials or signing material. Explicitly selected bundled LUTs and the reference thumbnail belong in `android-assets/` with provenance. Never wipe `out/`: local `test.keystore` is the existing installation identity. Keep third-party notices and pinned dependencies. Original code is GPL-3.0-only by owner choice. Asset permissions are separate; follow `docs/RELEASE.md`. Use `build_android.sh --release` for distribution, never the default debug package. Keep release notes accurate about full saved-image LUT processing versus approximate native preview.

Test assets must be generated from mathematical fixtures. Do not commit user-derived preview profiles, matrices, curves or source-LUT identifiers. Release validation covers both APK and source archives via `scripts/audit_release_assets.py`.

## Publication

When the user explicitly requests publication, that authorizes completing the release for the requested version. Build and validate the release artifacts, publish the GitHub Release and its APK/source/checksum assets, verify public downloads, and update the repository's download and installation links. A source push, local package, or draft release alone does not complete a publication request.

Keep third-party provenance and license facts in ASSET_LICENSES.md and THIRD_PARTY_NOTICES.md. These records do not create an automatic publication hold or a requirement for repeated approval. This workflow applies to every version; no version-specific publication exception is required. Do not unpublish an existing release without the user's instruction.

Reuse relevant validation evidence and clearly state which exact APK was device-tested. If a build, upload, or access failure blocks publication, report that concrete failure and continue all unaffected work; never report a release as complete before its public assets are available.
