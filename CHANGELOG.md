# 更新记录

## 0.9.0-beta.7-rc.2 — reviewed A5100 retest candidate

- Independent review found no high-confidence regression requiring a production-logic change; update acceptance notes and version identification only, retaining the rc.1 signing identity.

## 0.9.0-beta.7-rc.1 — A5100 animation cleanup candidate

- Avoid the API10 `Canvas.setBitmap(null)` crash after saving; stop animation callbacks and detach the current source before releasing only owned thumbnails.
- Add real Android graphics lifecycle regressions with mathematical fixtures; preserve full LUT processing, photo dimensions and the capture/save interfaces.
- User reports installing rc.1 on A5100 and confirming the original post-capture exit is fixed. Full device acceptance and real API10 graphics-test execution remain pending; the previously observed system restart remains unexplained.

## v0.9.0-beta.6 · 2026-11-01

- 旧机兼容：使用 Surface 生命周期判断取景就绪；缺少新版保存回调时采用媒体索引与文件完成检查；适配旧版原生内存属性接口。A6000 / A7 一代已做官方固件接口审计及 API10 离线验证，实拍仍待验收。
- 画质自适应：选择固件支持的最高 JPEG 档位，显示实际画质，退出恢复原值。
- 设置引导：RAW／RAW+JPEG、连拍／包围、尺寸及曝光模式可自动调整，屏幕说明调整内容；自动 HDR／HDR 绘画冲突显示关闭路径，不静默退出。
- 故障诊断：默认保留有容量上限的机身与 SD 日志，支持菜单导出 REPORT.ZIP，附带主机分析脚本。无需 ADB，不打包照片或 LUT 内容，不自动上传。
- 导航：从“更多”直接导出，成功保留菜单位置，上下键可继续跨页。
- 文档：同步下载链接、覆盖升级、画质选择、HDR 处理和用户日志提交步骤。

A6300 / 固件 2.01 已完成实机验证。分阶段测试范围见 [发行记录](docs/RELEASE.md)。

## 0.9.0-beta.5 — updated reference photograph

- Replace the LUT selector reference with the maintainer-selected IMG_7554.jpeg, oriented and converted to an sRGB thumbnail with the full portrait composition.

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

Verified hardware scope: A6300 firmware 2.01. See `docs/RELEASE.md` for public downloads and release/acceptance status.
