# Third-party materials

Original project code is GPL-3.0-only (see `LICENSE`). Third-party code and assets retain their own notices and terms; see `ASSET_LICENSES.md` for bundled photographic resources.

- **Noto Sans SC subset**: `android-assets/UIFONT.TTF`, source identity in `FONTINFO.json`; SIL Open Font License in `FONT-OFL.txt`, included in APKs.
- **OpenMemories Tweak Java bridge**: `dev-connect/src/com/github/ma1co/openmemories/tweak/`; MIT, full notice in `dev-connect/assets/OpenMemories-Tweak-LICENSE.txt`. Original JNI naming and attribution retained. The connector is development-only and not included in the main release APK.
- **libjpeg-turbo 2.1.5.1**: pinned in `dependencies.lock.json`; IJG/BSD/zlib terms are detailed by upstream `LICENSE.md` and `README.ijg`, both packaged with the application. Source is available from the pinned upstream commit and release source bundle.
- **GNU STL runtime from NDK r16b**: runtime notice is included as `GNU-STL-NOTICE.txt`; the runtime uses the GCC runtime library exception. The NDK is an external build tool, not committed here.
- **Sony PictureEffectPlus memory configuration**: external 1,860-byte data file, pinned upstream revision and digest in `dependencies.lock.json`. Upstream has no license file at the audited revision. No rights are claimed or granted here. This notice records the resource provenance; the publication workflow is documented in `docs/RELEASE.md`.
- **Reference photograph**: the maintainer-requested derivative `android-assets/PREVIEW.JPG` is described separately in `ASSET_LICENSES.md`.
- **Synthetic fixtures**: test LUTs and identity preview parameters are generated mathematically by project scripts. Release APKs include only cataloged LUTs; generated diagnostic profiles remain development-only.
