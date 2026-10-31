# A6000 / A7 一代离线验证（2026-10-31）

候选：0.9.0-beta.6-rc.3 / code99。研究在没有相机连接的情况下完成。公开 beta.5 保持不变；rc.3 尚未完成相机拍摄验收。客户反馈的“准备拍摄后退出”尚未锁定唯一触发点。

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

本轮没有模拟传感器、Sony AV 服务或存储硬件；两机的实际快门、显影和保存仍待相机验收。后续现场日志用于确定启动退出究竟落在哪一步，不替代以上离线测试。

## 重现命令

先构建发行 APK，在 API10 ARM 模拟器安装并打开 LUT 库，确认 24m 堆设置已生效。以下脚本拒绝运行在真实相机上：

```sh
bash scripts/test_legacy_emulator.sh EMULATOR_SERIAL
clang++ -std=c++11 native/device_memory_span_tests.cpp -o out/device-memory-span-tests
out/device-memory-span-tests
python3 scripts/emulate_legacy_memory_property.py /path/to/a6000/libcameraseq.so /path/to/a7/libcameraseq.so
```

指令仿真需要 `unicorn`、`pyelftools`。固件、原始日志与截图仅保留在本地忽略目录 `results/legacy-emulator-20261031/`，不随源码或 APK 发布。
