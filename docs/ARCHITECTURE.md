# 源码与目录边界

Java 文件按职责分目录，仍使用同一 `org.hongen.sonylutprobe` 包；本次没有改变类名、Android 组件名、JNI 名称、可见性或进程边界。构建脚本递归收集源码，字体工具也递归扫描界面文字。

| 位置 | 职责／关键入口 |
| --- | --- |
| `android/app/` | LibraryActivity、PhotoActivity、GalleryActivity：用户页面入口 |
| `android/capture/` | PreJpegProbeActivity、PreJpegGate：生产拍摄、原生保存、曝光及遥测 |
| `android/preview/` | 自动拟合调度、参数持有／恢复、半按隔离、每 LUT 偏好 |
| `android/storage/` | LUT 导入、精确缓存、照片目录、资源与 SD 路径 |
| `android/ui/` | 页面绘制、显影动画、回看几何、键位与小图缓存 |
| `android/platform/` | JNI 声明、Sony 退出协议、图形输出与照片预览接口 |
| `android/diagnostics/` | 显式诊断 Activity 和旧拍摄入口；仍随开发 APK 编译 |
| `android-tests/` | 不依赖设备的 Java 契约测试 |
| `native/` | core/LUT、UYVY、JPEG、JNI、缓存与拟合；测试保持现有 CMake／脚本路径 |
| `dev-connect/` | 独立测试连接 APK，自身源码、资源、测试与构建脚本 |

`PhotoActivity` 继承 `capture/PreJpegProbeActivity`，生产与诊断逻辑尚共享该基类。目录分类没有把旧探针从 APK 删除，也不代表已经完成模块拆分。

## 依赖与数据

用户内容读取 SD 卡，不读工作区 `LUT_Collected/`。工作区该目录是测试输入和用户收藏，不能自动打包发行。随包资源在 `android-assets/`。诊断预览样例由 `scripts/generate_preview_fixture.py` 从项目中性数学 LUT 生成，不依赖用户素材。

`vendor/` 保持完整第三方源码、工具链与许可证；`vendor/packages/` 放构建依赖的外部 APK。`out/` 是本地构建工作区，包含不可随意再生成的 `test.keystore`；清理构建目录时必须单独保留密钥和仍被实验引用的内容。

`results/` 是原始日志、照片和实验产物，不按当前结论重写旧回执。`docs/history/` 是旧阶段叙述，`HANDOFF.md` 只写当前有效事实和待办。`archives/` 是明确暂停的研究。

工作区迁移清单与完整历史日志保留在本地，不进入源码仓库。新文档使用新路径；独立构建见 [BUILD.md](BUILD.md)，已验证状态见 [STATUS.md](STATUS.md)。

## 通用诊断边界

`LutApplication` 在每个进程启动时安装 `SupportDiagnostics`；它只采集应用事件与有限设备属性，不打开相机。纯 Java 的 `DiagnosticStore` 负责容量限制、轮转、导出与 ZIP 校验，`DiagnosticText` 负责字段限长与路径脱敏。库页面负责用户导出入口，拍摄流程通过已有 `event()` 接入，照片数据与诊断存储分离。SD 失效保留机身副本，采集错误不改变拍摄结果。
