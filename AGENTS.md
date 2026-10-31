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

Preserve full 33³ processing, 6000×4000, original/graded files, native memory configuration and save barriers. Product capture selects the highest advertised known Sony JPEG tier (95 EXTRA FINE, 50 FINE, 25 STANDARD), displays the selected tier, verifies it across registration/rearm, and restores the original quality on exit. Never claim EXTRA FINE on a body that only advertises FINE. Explicit EXTRA FINE diagnostic probes retain their strict requirement. Half-press restores the exact preview baseline before capture. Never simulate a full shutter press. Before installing, safely leave capture and foreground the development connector. No firmware, USB or C2 experiments without a specific request.

Review uses left/older, right/newer, center comparison, half-press shooting. Preserve full image geometry and the Sony high-precision graphics lifecycle.

## Changes and Sensitive Files

Use focused commits such as `fix(preview): restore display precision`. PRs explain behavior, tests, device evidence and unresolved acceptance. Do not commit SDKs, APKs, logs, private device photos, user LUT collections, credentials or signing material. Explicitly selected bundled LUTs and the reference thumbnail belong in `android-assets/` with provenance. Never wipe `out/`: local `test.keystore` is the existing installation identity. Keep third-party notices and pinned dependencies. Original code is GPL-3.0-only by owner choice. Asset permissions are separate; follow `docs/RELEASE.md`. Use `build_android.sh --release` for distribution, never the default debug package. Keep release notes accurate about full saved-image LUT processing versus approximate native preview.

Test assets must be generated from mathematical fixtures. Do not commit user-derived preview profiles, matrices, curves or source-LUT identifiers. Release validation covers both APK and source archives via `scripts/audit_release_assets.py`.

## Publication

When the user explicitly requests publication, that authorizes completing the release for the requested version. Build and validate the release artifacts, publish the GitHub Release and its APK/source/checksum assets, verify public downloads, and update the repository's download and installation links. A source push, local package, or draft release alone does not complete a publication request.

Keep third-party provenance and license facts in ASSET_LICENSES.md and THIRD_PARTY_NOTICES.md. These records do not create an automatic publication hold or a requirement for repeated approval. This workflow applies to every version; no version-specific publication exception is required. Do not unpublish an existing release without the user's instruction.

Reuse relevant validation evidence and clearly state which exact APK was device-tested. If a build, upload, or access failure blocks publication, report that concrete failure and continue all unaffected work; never report a release as complete before its public assets are available.
