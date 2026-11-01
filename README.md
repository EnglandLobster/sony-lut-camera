# Sony LUT Camera

**让 A6300 在机内完成真正的 3D LUT 调色，拍完直接得到原片和调色 JPEG。** 无需手机、联网或电脑参与出片。支持导入自己的 `.cube`，预装 10 个指定 LUT。

> 当前版本：**v0.9.0-beta.7-rc.4**（公开测试包，code110）。本版面向 PMCA 多机型兼容性测试；已完成固件、主机和 API10 模拟验证，尚未完成本版真机回归。历史实拍基线为 **Sony A6300 / 固件 2.01**，其他机型按实验适配反馈。

本版合并 [@latteye 的 A5100/API10 拍后退出修复（PR #2）](https://github.com/EnglandLobster/sony-lut-camera/pull/2)，保留 12/16/20/24/36/42MP 全尺寸、对应内存表和保存恢复。NEX 缺少近似色彩预览接口时使用原色取景，照片仍应用完整 LUT。[发行说明](docs/RELEASE.md) · [机型与验证范围](docs/compatibility/README.md)。

## 上手使用

**第一次安装？先看 [新手安装与使用教程](docs/USER_GUIDE.md)**：Windows/macOS 工具下载、相机 MTP 设置、选择本地 APK、首次拍摄及常见问题。

**[下载 beta.7-rc.4 测试包](https://github.com/EnglandLobster/sony-lut-camera/releases/download/v0.9.0-beta.7-rc.4/SonyLutCamera-v0.9.0-beta.7-rc.4.apk)** · [发行说明与校验文件](https://github.com/EnglandLobster/sony-lut-camera/releases/tag/v0.9.0-beta.7-rc.4) · 多机型实验适配，欢迎提交诊断日志。

1. 从 [beta.7-rc.4 发行页](https://github.com/EnglandLobster/sony-lut-camera/releases/tag/v0.9.0-beta.7-rc.4) 下载 `SonyLutCamera-v0.9.0-beta.7-rc.4.apk`。自行编译见 [构建说明](docs/BUILD.md)。
2. 用 [Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE) 安装 APK。相机使用 MTP USB 模式；详细步骤见 [安装与使用](docs/USER_GUIDE.md)。
3. 打开相机应用列表中的 **LUT Camera**，选一个 LUT，按中央键进入拍摄。首次打开会把预装 LUT 加入 SD 卡。
4. 正常半按对焦、全按拍照。相机保留原片 JPEG，并另外保存应用完整 LUT 的 JPEG；成片后半按回到取景。

普通拍摄不需要“测试连接”、ADB、Telnet 或 Wi-Fi。开发连接工具不随发行包提供。

出问题后可用 **MENU → 导出诊断日志** 生成 SD 卡 `SONYLUT/DIAG/REPORT.ZIP`，把日志包与复现步骤交给开发者。[日志说明](docs/DIAGNOSTICS.md)。

## LUT 选择

预装 Fuji、Ilford、Kodak 和 Ricoh 共 10 个指定 LUT（7 个 25³、3 个 33³），默认选择 Fuji C200。用户导入的 LUT 按完整网格处理，支持 **2–65³**。参考 [内置资源](docs/BUILTIN_LUTS.md)。

将独立 `.cube` / `.cub` 放入 SD 卡根目录的 `LUTS/`，在应用里按右键扫描、导入。每个文件最多 64 MiB；应用保存完整表并自动生成近似取景参数。首次拟合差异较大时，可以选择继续近似预览或原色取景，成片始终使用完整 LUT。

## 拍摄与回看

- 自动曝光，左右键／拨轮调节曝光补偿，中央键归零。
- **本机原生比例全尺寸、机型支持的最高 JPEG 档位**（A6300 为 6000×4000）（支持时使用 EXTRA FINE；仅提供 50/25 的机型使用 FINE），原片与 LUT 成片分别保存到 SD 卡；原片指未套自定义 LUT 的 JPEG，不是 RAW 文件。
- LUT 库右侧照片随选择显示完整 LUT 效果；这张参考图不是实时取景。
- 播放键进入应用内相册；左看较早照片，右看较新照片，中央键比较原片和成片；半按快门返回拍摄。
- MENU 返回 LUT 库，再从「更多 → 退出应用」返回索尼原生拍摄。

完整拍摄需要数秒，耗时受场景、曝光与 SD 卡影响。其他机型正在测试；视频 LUT 和全分辨率实时 3D LUT 取景未提供。详细验证边界见 [状态](docs/STATUS.md)。

## 开发与仓库结构

原始代码采用 **GPL-3.0-only**。构建环境为 macOS、JDK 17、Android SDK 36 和 NDK r16b；版本固定的外部依赖由脚本获取。

```sh
python3 scripts/bootstrap_dependencies.py
bash scripts/build_host.sh
python3 scripts/test_offline.py
bash scripts/test_product_ui.sh
bash scripts/test_lut_cache.sh
bash scripts/test_preview_fit.sh
bash scripts/build_android.sh --release
```

[完整构建说明](docs/BUILD.md) · [贡献规范](AGENTS.md) · [页面操作规则](PRODUCT_UI.md)

| 目录 | 职责 |
| --- | --- |
| `android/{app,capture,preview,storage,ui,platform}/` | 产品页面、拍摄、预览、存储和索尼接口 |
| `native/` | C++ 完整 LUT、UYVY、JPEG、缓存与预览拟合 |
| `android-assets/` | 预装 LUT、参考缩略图、字体及归属说明 |
| `android-tests/`、`scripts/` | 验证、构建与维护入口 |
| `android/diagnostics/`、`native/probes/` | 开发诊断，发行 APK 不暴露诊断 Activity |
| `dev-connect/` | 可选开发连接工具，不是用户拍摄依赖 |
| `docs/` | 用户指南、构建、架构和发行记录 |

`out/`、`dist/`、`results/`、`vendor/` 保留在本地，不提交密钥、APK、设备照片、固件或私人 LUT 收藏。`PreJpegProbeActivity` 虽保留历史名称，仍是生产拍摄基类，不能删除。

## 许可与致谢

[GPL-3.0](LICENSE) 适用于本项目原始代码；预装 LUT、字体、照片及外部配置分别遵循 [资源许可说明](ASSET_LICENSES.md) 和 [第三方清单](THIRD_PARTY_NOTICES.md)。感谢 OpenMemories、Sony-PMCA-RE 和 libjpeg-turbo。项目为第三方应用，不隶属于 Sony。
