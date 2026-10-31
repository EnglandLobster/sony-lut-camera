# Build and development setup

The supported build host is macOS, matching the current verified toolchain. The APK targets old Sony Android firmware; replacing the NDK or Java compatibility level requires separate validation.

## Toolchain

Install JDK 17, CMake, Xcode command-line tools, Homebrew LLVM, Python 3 and Android command-line tools. Install Android SDK `platforms;android-36` and `build-tools;36.0.0`. Supply **NDK r16b** separately; newer NDKs remove the ARMv5/GNU STL combination used by this app.

```sh
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_SDK=/opt/homebrew/share/android-commandlinetools
export ANDROID_NDK=/absolute/path/to/android-ndk-r16b
# If Homebrew LLVM is elsewhere, set SANITIZER_CXX to its clang++.
python3 scripts/bootstrap_dependencies.py
```

`dependencies.lock.json` pins libjpeg-turbo and the exact 1,860-byte Sony memory-map asset. The bootstrap verifies existing inputs, refuses different/modified copies, and downloads only missing inputs. `--check` performs no downloads. These files live under ignored `vendor/`; the Sony reference asset retains its upstream provenance and is not relicensed here.

## Main APK

```sh
bash scripts/build_host.sh
python3 scripts/test_offline.py
bash scripts/test_product_ui.sh
bash scripts/test_lut_cache.sh
bash scripts/test_preview_fit.sh
bash scripts/build_android.sh --release
```

The offline suite generates synthetic LUTs and JPEGs in `out/tests/`, including files required by the APK. It has no dependency on another local camera project or a collected film LUT. The fit test uses the active `python3`; `PYTHON=/path/to/python` can override it.

`dist/SonyLutTest.apk` is the current build; `dist/BUILDINFO.json` records its checks. `android-assets/` includes the licensed static font, so rebuilding the font is optional and requires the separately obtained pinned Noto source described in `scripts/build_ui_font.py`.

`--release` stages only production Activity entries and cataloged resources, disables debugging and excludes legacy test assets. Omit it for the development APK with explicit diagnostics. Both reuse the same production capture classes. Release staging requires the existing signing key; a new developer can first make a debug build to generate their own identity.

`python3 scripts/package_release.py` creates versioned local delivery artifacts from a clean committed tree; it does not publish anything. Its source archive also contains the pinned libjpeg source under `third-party-sources/libjpeg-turbo/` for source availability. Normal builds continue fetching the verified Git checkout into `vendor/` using the bootstrap command. See [release status](RELEASE.md) before distributing the APK.

## Signing and installation

`out/test.keystore` is ignored. A first build creates a local development key; existing installations require their original signing key to accept an update. Do not regenerate or overwrite that key, commit it, or clear app data merely to bypass a signature mismatch. Main and helper APKs must use the same signing identity.

For an authorized device update, safely leave capture and open the helper before running `adb -s <serial> install -r dist/SonyLutTest.apk`. Read device version, startup logs and boot identity afterward. Camera captures require physical shutter operation.

## Optional development connector

```sh
python3 scripts/bootstrap_dependencies.py --helper
bash dev-connect/build.sh
```

This reuses verified OpenMemories Tweak 0.11 native components. The helper currently selects a fixed development SSID in `ConnectActivity`; configure it for your test network before building. Passwords are learned and stored only on the camera, never supplied by this repository. Its build expects the signing key created/preserved for the main app.

## Local research tools

Some diagnostic collectors require an attached camera, ignored historical logs, an upstream PMCA checkout or an optional research SDK. They are not prerequisites for the build commands above. Archived results and `HANDOFF.md` belong to the original workspace and are intentionally excluded from Git.
