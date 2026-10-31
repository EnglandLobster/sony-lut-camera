# Asset licensing

The repository's GPL-3.0-only license applies to original program code and documentation. External assets retain the terms below; GPL does not relicense them.

| Resource | Attribution and terms |
| --- | --- |
| `android-assets/PREVIEW.JPG` | Maintainer-supplied reference photograph derived from IMG_7681.jpeg, oriented and converted to sRGB at 216×288, without EXIF. Included at the maintainer's request as the application's LUT reference. No separate general-purpose photo license is asserted. |
| `android-assets/UIFONT.TTF` | Noto Sans SC subset, SIL Open Font License 1.1; full terms in `FONT-OFL.txt`. |
| Synthetic identity/example LUTs | Mathematical fixtures supplied with the project. The release catalog contains the neutral identity and a synthetic color example. Other development fixtures are excluded from the release APK. |
| Sony `MAP24R1.BIN` | External 1,860-byte memory-layout configuration, pinned by URL and digest in `dependencies.lock.json`. The upstream repository has no license file at the audited revision. This project does not relicense this external resource. Its distribution status is tracked in `docs/RELEASE.md`. |

Dependencies and their license notices are listed in `THIRD_PARTY_NOTICES.md`. User-imported LUTs remain on the user's SD card and are not uploaded by the app.
