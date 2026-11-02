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

## 多规格拍摄与保存完成

`CaptureMemoryMap` 根据 Sony 平台/像素标识和精确文件名，按修订版选择机内或随包配置。随包配置继续校验固定摘要；可信系统目录内的原生配置校验路径/基本格式，记录实际摘要，不因摘要未收录而拒绝。`CaptureGeometry` 从相机广告的全尺寸、原生比例中协商唯一尺寸，传给设置、显影校验、照片校验和回看分配；A6300 仍为 6000×4000 与原 MAP24R1。内存表选择先于相机打开，数据来自锁定依赖或机身原生表，原始固件不进入 APK。

`CaptureStoreCompletion` 是原片/成片保存事务的唯一完成判定。Sony 成功具名通知 + RPC 返回 + 完整且尺寸正确的 JPEG 足以推进下一条已声明保存，不等待原片进入索引。缺失通知才调用 `CaptureIndexQueries` 的独立查询线程，按预存水位查找尚未确认的 DCF 身份。回调确认过的原片即使晚入库，也不算新的歧义记录；空索引的水位为 -1，允许记录序号 0。

索引线程至多一项在途工作，不与文件/通知观察线程共用队列。保存前快照最多等 1 秒：现代协议可继续等成功回调；旧式协议没有安全水位时在调用保存前报错。迟到查询不得挪作提交后的水位，也不得确认其他代次。私有等待快照记录真实查询提交数、在途状态、耗时和最后错误。

`RECORD_COUNT=2` 在曝光前声明，因此 MENU/暂停仅阻止下一次拍摄，当前原片确认后仍须完成调色片保存，再释放资源。`beginFinalStore` 只允许确认原片后的一个后续记录；不可第三次提交，也不可在停止后开启新拍摄。RPC 未返回时不释放缓冲。


## 通用诊断边界

`LutApplication` 在每个进程启动时安装 `SupportDiagnostics`；它只采集应用事件与有限设备属性，不打开相机。纯 Java 的 `DiagnosticStore` 负责容量限制、轮转、导出与 ZIP 校验，`DiagnosticText` 负责字段限长与路径脱敏。库页面负责用户导出入口，拍摄流程通过已有 `event()` 接入，照片数据与诊断存储分离。SD 失效保留机身副本，采集错误不改变拍摄结果。

保存核验在独立 `store-confirmation` HandlerThread，Sony 保存调用保持原工作线程；索引查询另有专用线程。私有 `CaptureRecoveryFence` 在保存前持久化，跨进程阻止未知所有权下的重入，完成整组保存及清理后解除。详见 [双保存回归修复](compatibility/PAIR_STORE_REGRESSION.md)。
