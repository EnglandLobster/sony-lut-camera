# Changelog

## 0.9.0-beta.4 — curated LUT library

- Preload the ten selected Fuji, Ilford, Kodak and Ricoh LUTs; default to Fuji C200.
- Remove the example and identity entries from the product; upgrades retire their exact content identities and caches while preserving other imports and valid selections.
- Generate mathematical test fixtures independently of the product catalog.

## 0.9.0-beta.3 — first release candidate

- Camera-side full 3D LUT processing before JPEG encoding; separate original/graded 24MP EXTRA FINE files.
- Automatic matrix/Gamma preview fitting for imported 2–65³ CUBE LUTs; preview is isolated from capture on half-press.
- Photographic LUT-library preview, automatic exposure with EV, paired review and adjacent-photo caching.
- Correct Sony display precision for smooth gradients and full-composition portrait reference layout.
- Separate release packaging: debugging disabled, production Activity entries only, no legacy test photos/profiles.
- GPL-3.0-only original code; third-party assets documented separately.

Verified hardware scope: A6300 firmware 2.01. APK publication is pending clarification of the external Sony memory-configuration asset. See `docs/RELEASE.md` for live release/acceptance status.
