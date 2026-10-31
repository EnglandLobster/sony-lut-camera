# A6000 / A7 一代离线验证（2026-10-31）

候选：0.9.0-beta.6-rc.6 / code102。研究在没有相机连接的情况下完成。公开 beta.5 保持不变；候选已完成 [A6300 两张实拍验收](A6300_RC6_ACCEPTANCE.md)，A6000/A7 实拍仍待验收。已确认两份官方固件共同缺少 beta.5 强制使用的保存通知 API，导致拍摄初始化无法完成。

本轮安装到 API10 模拟器并回归的发行 APK SHA-256：`374fb8dcca61f7d47dd119c17a689c42fa33b2227934fbb58a110fd2503cdeb8`。保持原包名、签名证书和已有用户数据。

## 确认的启动兼容错误与 rc.4 修复

beta.5 的 `open()` 在相机打开和参数配置后调用 `registerCameraListeners()`，其中强制查找 `CameraEx$StoreImageCompleteListener`。两份固件的类定义和 CameraEx 方法声明均没有这个接口及 setter；`startPreview()` 在这次查找之后，因此初始化必然在到达可拍摄状态之前失败。先前仅检查原生库和 DisplayManager 没有覆盖这个 Java 回调契约。

rc.4 的 `CaptureStoreEvents` 按实际接口选择协议。新版机型沿用具名保存完成回调；缺少该接口的老机型使用 `LegacyStoreIndex` 和已有 `PhotoIndex` 的有界查询，确认保存前序号之后唯一的新记录与当前新文件的 DCF 身份一致。同时 `PhotoFiles` 要求文件稳定、完整，拒绝多文件歧义、重复或旧结果。每次保存以代次隔离并有 30 秒超时；取消会使旧轮询失效。只有原片确认后才开始调色并提交第二个保存，保留完整 LUT、6000×4000 和 EXTRA FINE。

旧 `JpegListener` 虽然存在，但没有被启用。A6000 固件的 setter 调用 `native_setCallback(6)`；`libcamera.so` 的 `handleJpegEvent_Thread` 将编码数据复制进 MemoryHeapBase 后交给数据回调，Java EventHandler 再把消息中的 byte[] 交给 JpegListener。最终采用媒体索引确认，避免为保存通知搬运整张 JPEG。

`audit_sony_capture_api.py` 读取真实 ODEX 的方法声明与继承关系，核对 47 个其他必需方法，A6000 3.21 和 A7 3.20 均无缺失。两份数据库库还包含 `AvindexStore.Images.Media.EXTERNAL_CONTENT_URI` 及 `_data`、`rec_order`、`dcf_folder_number`、`dcf_file_number` 字段。日志：`full-capture-api-audit.json`、`store-api-audit.json`；原生事件分发证据：`a6000-jpeg-native.txt`。这项审计确认声明契约，未执行相机硬件服务。

`CaptureStoreEventsTest` 使用与固件声明一致的最小测试类复现旧强制查找失败，验证新分支不注册完整 JPEG 数据回调、新版分支不变且 setter 运行错误不被隐藏。`LegacyStoreCompletionTest` 与 `PhotoIndexTest` 验证双文件顺序、完整文件但索引未到、旧索引不能用于新文件、过期代次、取消、超时和歧义。以上三组在主机及 API10 ARM / 24 MB 堆均通过；这是接口和文件状态模拟，不是传感器模拟。

## 固件证据

从 Sony 官方下载 A6000 3.21 和 ILCE-7 3.20 更新包，仅离线解包，没有执行更新程序。

- A6000：[官方 DMG](https://support.d-imaging.sony.co.jp/download/NEX/AcicwqgUEQ/Update_ILCE6000V321.dmg?fm=am)。用只读挂载取得 FirmwareData，再以 ma1co/fwtool.py 解包。
- A7：[官方 EXE](https://support.d-imaging.sony.co.jp/download/IMT/Update_ILCE7V320.exe?fm=am)。EXE 内 DAT 从 524860 字节开始，按 UFU chunk 到 DEND 截取，共 205956248 字节，再用 fwtool 解包。fwtool 的 EXE 包装识别失败，DAT 解密成功。

两者 `nflasha16` 的 `build.prop` 都是 Android 2.3.7 / API10 / armeabi-v7a / `dalvik.vm.heapsize=24m`。A6000 备份配置含 `ILDCSC_MUSASHI8Gb`；当前内存表保护条件没有放宽。

两者均提供 DisplayManager、Gpelibrary 高精度图形接口、CameraEx.OpenOptions、CameraSequence 与 DefaultDevelopFilter。不能把通用 Android 模拟器的缺少 Sony 类异常当作这两台相机的现场根因。

`libcameraseq.so` SHA-256：

| 固件 | SHA-256 |
| --- | --- |
| A6000 3.21 | ca96d7fbd87ff774b3c5cc95b1f7dba7c3acf5e639e2808ffe84982acb4da36b |
| A7 3.20 | 69a732e2b9a114d36064343c1dd02b3a83ac809cbfb6bb9edab9f50ecc0f222b |

## 确认并修复的原生接口差异

两者都缺少 A6300 路径使用的 `DeviceMemoryDiadem::getPhysicalMemoryInfo(unsigned&, unsigned&)` 导出。原有代码在首次映射显影图像时会抛出 required device-memory symbol unavailable。这发生在显影图像处理阶段，不能据此把用户的启动退出归因到这里。

旧固件自己的 `readBuffer` 先读取属性 ID 1、2，再调用 `mapSharedMemory`；其公开 `getProperty` 将 `memory-address`、`memory-size` 映射至相同 ID。rc.3 在新版导出缺失时使用这两个属性，继续原有零拷贝映射，不读取硬编码对象偏移、不改变内存配置表、不增加全图复制。新版接口存在但调用失败时仍报告失败，不掩盖错误。

旧接口通过 `%d` 输出地址，并复用同一个静态字符串缓冲。实现立即解析每个值，正确还原 32 位有符号地址，并校验长度上限。`scripts/emulate_legacy_memory_property.py` 在 Unicorn 中执行两份固件的原始 ARM `getProperty` 指令，仅替换属性后端和 libc/logging。两份均返回 ID `[1,2]`、字符串 `[-2128396288,48000000]`，确认共享缓冲行为。物理地址是数学测试值，未访问真实物理内存。

## ARM Android 模拟器

使用 Google tools 25.2.5 的 ARM 模拟器及官方 `armeabi-v7a-10_r05.zip`，实际系统 Android 2.3.7。将 `dalvik.vm.heapsize` 设为 24m 后重启模拟器 Android 服务，运行日志确认 max heap = 25165824。宿主运行在 x86_64 Linux，模拟器 RAM 512 MB；没有把此 RAM 值当作相机可用内存测量。

结果：

- 公开 beta.5 能加载 C200、运行 JNI 预览拟合并进入拍摄 Activity。通用系统在 Sony DisplayManager 类查找处报错，未复现客户现场的具体异常。
- rc.3 在 API10 / 24 MB Java 堆下，10 个内置 LUT 均通过源码摘要校验、编译缓存读取、原生 LUT 初始化（含 128 MB 虚拟地址预留）、两张 750×500 ARGB 回看缓冲分配、像素读写及资源释放。
- 模拟器复现 rc.2 在显示初始化失败、资源关闭后仍执行后台 prepare，随后第二个错误覆盖首个错误。rc.3 将后台准备安排在显示初始化成功之后；复测只有首个 display_geometry 错误，没有后续 prepare。
- 错误页面显示中文原因、MENU 返回提示与诊断路径。实际截图发现名称文字与多行错误正文重叠，已删除错误页中重复的中部名称，保留顶部名称。

本轮没有模拟传感器、Sony AV 服务或存储硬件；两机的实际快门、显影和保存仍待相机验收。已确认的初始化 API 阻断点已修复；现场若仍失败，日志用于识别其他运行条件。

## 重现命令

先构建发行 APK，在 API10 ARM 模拟器安装并打开 LUT 库，确认 24m 堆设置已生效。以下脚本拒绝运行在真实相机上：

```sh
bash scripts/test_legacy_capture.sh
bash scripts/test_legacy_emulator.sh EMULATOR_SERIAL
python3 scripts/audit_sony_capture_api.py /path/to/a6000/system /path/to/a7/system
clang++ -std=c++11 native/device_memory_span_tests.cpp -o out/device-memory-span-tests
out/device-memory-span-tests
python3 scripts/emulate_legacy_memory_property.py /path/to/a6000/libcameraseq.so /path/to/a7/libcameraseq.so
```

指令仿真需要 `unicorn`、`pyelftools`，Java 接口审计需要 `androguard`。固件、原始日志与截图仅保留在本地忽略目录 `results/legacy-emulator-20261031/`，不随源码或 APK 发布。

## rc.5 设置引导与异常返回验证（2026-11-01）

新增 HDR on/auto 与 HDR 绘画的启动前拦截测试，确认报错前不写相机设置；RAW／连拍／尺寸的自动配置与恢复沿用并通过 CaptureSetupTest。P 模式和 EXTRA FINE 的设置异常保留操作上下文，即使底层只返回 setParameters failed，屏幕仍给出具体设置方法。CaptureStartupErrorTest 在 API10 / 24 MB 模拟器通过。

在已确认 `ro.kernel.qemu=1` 的模拟器中，仅对隔离的 `:capture` 进程注入 SIGKILL。首次实验发现 Android 2.3.7 可不派发 Activity result 就恢复库页面；补上会话返回检查后，再次注入时显示“拍摄会话意外结束”弹窗，照片库进程存活。日志 `rc5-process-death.log`，实际截图 `rc5-interrupted-dialog.png`。该测试没有连接相机或触发快门，也没有把虚拟机结果记为 A6300 各种真实菜单组合的实拍验收。

## rc.6：用户报告的 Surface 与 JPEG 能力关卡（2026-11-01）

新增报告是外部用户提供的条件：A6000 收到 Surface 回调但 isValid 为 false，JPEG 能力列表为 50,25。此前 API10 模拟器没有复现这些硬件条件，rc.5 仍会被对应守卫拦住。因此不能用先前的模拟器通过来声称这两个关卡已验证。

Sony PictureEffectPlus 参考源码 `base/shooting/ShootingState.Callback.surfaceCreated` 直接调用 executor.setPreviewDisplay(holder)，没有 isValid 前提。rc.6 的 CaptureSurfaceGate 以生命周期回调为依据，覆盖回调先于／晚于准备、暂停、销毁与重复回调。准备前的 Surface 重建不会关闭整个会话，缺少就绪回调每 250 ms 重查、5 秒后显示明确错误；相机已打开后的取景丢失显示错误。所有调用仍经过原来的显示跟踪、相机参数和内存布局检查。

CaptureJpegQuality 按已知 Sony 档位 95/50/25 选最高能力；50,25 选择 FINE，包含 95 时仍选 EXTRA FINE。没有广告能力或未知代码报错，不猜档位。原片与成片使用同一个已选质量值，设置读回、RAW 注册后、下一张拍摄的检查全部改为该值，退出仍恢复原值。主界面不再固定标注 EXTRA FINE，取景 HUD 显示真实档位。显式 EXTRA FINE 诊断入口仍保持严格 95 条件。

CaptureSurfaceGateTest、CaptureJpegQualityTest 在主机和 API10 / 24 MB ARM 环境运行相同的生产逻辑，覆盖外部报告条件及新旧档位路径。它们是可重复的状态／参数测试，不是相机硬件实测。完整运行结果保留在 `rc6-product-tests.txt`、`rc6-api10-tests.txt`；签名发行包构建、DEX035、API10、ARM 检查通过。
