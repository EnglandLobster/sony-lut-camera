# Sony shooting controls (development, not released)

Implemented 2026-11-25 following the A7 II owner's request for native button
assignments, the marked exposure-compensation dial, and MF assistance.
This is source/host validation, not an A7 II hardware acceptance record.

## Behaviour

- In live view, `ScalarInput.getKeyLogicCode(scanCode, 1, category)` selects the
  shooting function. Categories are P=0, A=1, S=2, M=3. Custom functions are read
  with `Settings.getKeyFunction(int[])`. An explicitly unassigned button stays
  unassigned; an unsupported assignment displays a message instead of invoking
  the old fixed button action.
- Mode-dependent front/rear dial assignments use `getDialSetting()` and
  `getDialExposureCompensation()`. P uses native program shift when a dial is
  not assigned compensation; A/S use aperture/shutter. M honours the configured
  aperture/shutter swap. The previous `ControlLayout` remains a fallback only
  when the assignment API is unavailable.
- The marked EV dial is scan/status 620. Sony encodes negative positions with
  bit 512. Its position is an exposure-compensation parameter index. Away from
  zero it takes priority over software compensation; zero allows the camera's
  configured software compensation control. Moving back to zero resets the
  compensation. Old app EV memory is not applied on entry to a body with this
  dial. Movements during capture are applied when the camera is writable again;
  movements in M/fixed ISO are remembered until compensation is available.
  Exit respects a dial moved during the session.
- The AF/MF–AEL lever (589) selects the assignment for button 638. A button's
  release uses the function recorded at its press, even across a menu or mode
  change. A release during capture updates intent immediately and performs the
  native reset when the capture gate reopens. Session cleanup resets held and
  toggled locks and restores app-modified focus/metering/flash settings.
- Fn or MENU opens shooting settings. MENU inside a panel closes it. The last
  settings entry returns to the LUT library. Aperture, shutter and compensation
  can be adjusted with direction buttons through these settings. This makes
  them reachable even with no rotary controls. ISO and WB retain their lists.
- A missing physical mode dial still exposes the P/A/S/M menu. Dedicated
  P/A/S/M command keys (628–631) select a mode without marking the body as
  having a physical dial. The still/movie key (624) no longer counts as one.

### Implemented assigned functions

ISO and WB lists; exposure compensation; focus mode (including advertised
AF-S/AF-C/AF-A through Sony's autofocus modifier), focus area/point, face
priority, metering, flash mode and flash compensation; AEL hold/toggle; AF/MF
hold/toggle; lens focus hold; focus magnifier; DISP; P/A/S/M selection; aperture,
shutter and program-shift steps; single-dial Tv/Av switching. Standard centre
enters flexible-spot positioning when applicable, rather than resetting EV.

### MF assistance

`CameraFocusMagnifier` registers `CameraEx.PreviewMagnificationListener` and
uses native `setPreviewMagnification(level, Pair)` / `stopPreviewMagnification`.
Levels come from `getSupportedPreviewMagnification`; actual ratios and positions
come from callbacks. The existing AF feedback listeners remain independent.

The assigned magnifier button or Fn/MENU → 对焦放大 enters magnification.
Direction buttons move the position; centre advances levels and then exits.
Front/rear dials move vertically/horizontally. Half-press and MENU exit; playback,
mode transitions and session teardown also stop magnification. The overlay
shows a position guide and the reported ratio, hiding unmagnified AF/grid marks.

Focus-ring callbacks start automatic assistance in MF/DMF when Sony's
`getPreviewMagnificationOnManualFocus()` preference is enabled. The timeout
uses `getPreviewMagnificationTimeOnManualFocus()` (seconds, zero = unlimited).
Callback work is posted to the camera-owning UI thread. Automatic entry is
blocked during capture, review and panels; stale callbacks cannot resurrect
an explicitly stopped magnifier or abandon a new pending request.

## Remaining gaps

This does not embed Sony's complete shooting application or its Fn-menu
layout. Assignments outside the implemented functions (including drive/burst,
DRO/HDR, creative styles, picture effects, image size/quality/aspect, digital
zoom and video) are reported as unsupported. The full-size, single-shot LUT
pipeline still owns capture configuration. Focus peaking is not implemented.
An AF/MF-AEL lever moved *while its button remains held* retains that press's
original function until release; automatic transfer to the other function is
not implemented. Other focus/iris hardware switch events still need model
testing. No claim of complete native-control parity is made.

## Source evidence

Protocol names and values were checked against decompiled My Best Portrait
1.41, in [this reference tree](https://github.com/up209d/open-memories-app-ai/tree/f2c77da5ab181f8159c2c7c1e2c68c4023297c20/reference-apps/portrait-beauty/app/src/main/java/com/sony/imaging/app):

- `fw/P1Key.java`: logical keys, mode categories, dial settings and EV priority.
- `fw/CustomKeyMgr.java`, `fw/CustomizableFunction.java`, `fw/KeyConverter.java`:
  custom function IDs and AF/MF-AEL lever resolution.
- `base/common/EVDialDetector.java`: EV presence, position and sign encoding.
- `base/shooting/camera/CameraSetting.java`: magnification callbacks/native
  calls, `adjustProgramLine`, focus hold.
- `base/shooting/camera/{FocusModeController,AutoFocusModeController,
  FocusMagnificationController,ExposureCompensationController,FlashController}.java`:
  native control operations, supported modes and readback.
- `base/shooting/{MfAssistState,trigger/MfAssistKeyHandler}.java`: ring timing,
  contextual movement and half-press exit.

The implementation is original project code; Sony UI framework classes and
artwork are not added as dependencies.

## Verification and diagnostics

- `scripts/test_product_ui.sh`: passes, including `SonyControlsTest` and the
  existing exposure, preview, legacy capture and receipt tests.
- `scripts/test_capture_pipeline.sh`: passes on the host.
- 2026-11-26: installed checksum-verified NDK r16b, SDK platform 36 r2,
  build-tools 36.0.0 and Temurin JDK 17 under ignored `vendor/toolchains/`.
  The product suite passes with JDK 17. Production Java compiles with
  `--release 7` against SDK 36 as part of the successful release-mode APK build.
- Local APK: `dist/SonyLutTest.apk`, 4,570,648 bytes, SHA-256
  `d7323dc19bb4c1a3f098a1637854c93703f24020f7b1d64bf1ba01cb0fc14806`.
  Signature and package checks pass: v1 signing, API10, DEX035, ARMv5TE/ARMv7,
  production Activities and resource boundaries. `dist/BUILDINFO.json` records
  the package checks. Signed with a new local development identity in
  `out/test.keystore`, not the published project's signing key; it cannot update
  an installation signed with that original key. This is an unpublished build.
  No APK from this change has been installed or used to shoot.
- No physical A7 II, emulator or real-photo validation in this change.

Journals include `native_control_assignments`, `native_control_key`, `ev_dial`,
`mf_assist` / `mf_assist_unavailable`, and `control_error` on native refusal.
The A7 II follow-up should compare the same configured buttons/dials in native
shooting and LUT shooting, then check MF ring/key entry, magnification movement,
half-press, one LUT capture, playback, and exit back to the native camera.

To rebuild in this provisioned checkout, from the repository root:

```sh
source vendor/toolchains/env.sh
bash scripts/build_android.sh --release
```
