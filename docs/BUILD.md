# Build and development setup

The locally verified build host is macOS; PR #2 also provides Windows Git Bash host adaptations. The APK targets old Sony Android firmware; replacing the NDK or Java compatibility level requires separate validation.

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

`dependencies.lock.json` pins libjpeg-turbo and six exact Sony memory-map assets. `config/capture-maps.json` owns platform/pixel/revision identities and camera-resident map fingerprints. Staging verifies the pinned bytes and generates `CAPMAP.TXT`; firmware-only maps are not copied into the APK. The bootstrap verifies existing inputs, refuses different/modified copies, and downloads only missing inputs. `--check` performs no downloads. These files live under ignored `vendor/` and retain their upstream provenance.

## Main APK

```sh
bash scripts/build_host.sh
python3 scripts/test_offline.py
bash scripts/test_product_ui.sh
bash scripts/test_capture_pipeline.sh  # portable flow/fault tests, 24 MiB host heap
bash scripts/test_capture_compatibility.sh  # full-resolution native pixel/budget checks
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

保存阻塞研究：`audit_store_rpc.py` 审计本地固件调用点，`emulate_store_rpc.py` 执行原始 ARM IPC 控制流，两者输入均为 `collect_pmca_firmware.py` 的 collection.json。`test_capture_pipeline.sh` 包含阻塞 worker 与真实跨进程持有记录测试。`test_legacy_emulator.sh` 仅接受 API10 模拟器，临时设置并恢复 Dalvik 堆属性，在单个 guest shell 内运行所有套件；结果另存 `/data/local/tmp/legacy-smoke-result.txt`，以 `PASS_ALL_API10_SUITES` 为完成标记。

## Windows Git Bash and preview regression

Use the same pinned JDK 17, SDK 36/build-tools 36.0.0 and Windows NDK r16b. Set `JAVA_HOME`, `ANDROID_SDK`, `ANDROID_NDK`, `PYTHONUTF8=1` and `CMAKE_GENERATOR=Ninja` for the build shell. NDK host tools are selected by platform; signing uses the apksigner JAR. The release still requires the existing signing identity. Windows host tests that create symbolic links may require additional filesystem privileges; an APK build and a full host-suite pass are separate results. Windows execution evidence for these adaptations comes from PR #2; the integrated candidate was built locally on macOS.

`test_preview_graphics_emulator.sh API10_SERIAL` compares the tagged rc.3 renderer with the working-tree renderer on real API10 Bitmap/Canvas at 24 MiB. The checkout must contain `v0.9.0-beta.7-rc.3`. It requires the baseline null-Canvas exception and 96 successful fixed cycles. `test_legacy_emulator.sh` also runs these graphics cases against the installed APK. Both refuse physical cameras.
