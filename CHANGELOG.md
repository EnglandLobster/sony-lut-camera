# 更新记录

## 0.9.0-beta.7-rc.3 · 2026-11-01 公开测试包

- 定位 20 份固件保存链路的无截止时间等待，新增 60 项原始 ARM 传输/答复/超时仿真。
- 独立线程核验保存，磁盘确认与原生调用返回共同允许推进；超时可回库，保留后台图像所有权，迟到完成后安全收尾。
- 私有跨进程持有记录阻止未完成保存后的重入；增加不依赖 SD 的等待快照并纳入诊断包，错误页直接导出日志。
- 增加 API10 阻塞线程、停止/迟到和真实跨进程重入测试。详见 `docs/compatibility/STORE_RPC_RECOVERY.md`。

## 0.9.0-beta.7-rc.2 · 本地实现候选，未发布

- 接通 12/16/20/24/36/42MP 原生比例全尺寸链路；按平台、像素、修订号及 SHA-256 选择内存表，保留 A6300 原有 MAP24R1。
- 4Gb/NEX 使用 8 MiB LUT 缓存，36/42MP 使用 16 MiB；保持完整 LUT 网格与全像素计算。NEX 缺少 Gamma/矩阵接口时显示原色取景。
- 保存通知丢失时，通过完整 JPEG 和新增媒体索引恢复；防止迟到/重复通知、索引延迟引发重复推进。停止请求等待当前保存确认，禁止后续保存。
- 全尺寸数值校验、24 MiB 主机链路测试及真实固件选表通过；本地 API10 ARM 验证和剩余实机范围见 `docs/compatibility/IMPLEMENTATION.md`。

## 0.9.0-beta.7-rc.1 · 本地兼容性候选，未发布

- 新增 20 份官方固件的逐机型接口、内存属性 ARM 仿真及完整 33³ 多尺寸压力验证；研究结果见 `docs/compatibility/README.md`。
- 拍摄前核对原生图像内存接口，检查映射返回值；准备分配期间延后页面退出清理。
- 原片保存/调色重叠限制为已实拍验证的 A6300 / 2.01，其他机型等待原片确认再调色。
- 仍保留现有 24MP 内存表边界；新增机型的实拍支持状态未提升。本候选尚未安装到设备。

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
