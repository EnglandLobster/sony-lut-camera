# PMCA 多机型适配研究 · 2026-11-01

已完成 **20 份 Sony 官方固件**的 Java/native 接口审计和真实 ARM 内存属性函数仿真，并建立逐机型证据文件。当前公开测试包为 **0.9.0-beta.7-rc.3 / code109**，本轮未安装到相机；beta.6 历史发行版继续保留。A6300 / 2.01 的既有实拍证据保留原版本归属。

已接通本表 20 份固件对应的内存表选择，以及 12/16/20/24/36/42MP 全尺寸链路、NEX 原色取景和保存回调丢失恢复。工程实现、复现命令与本地验收见 [IMPLEMENTATION.md](IMPLEMENTATION.md)。表中“新/旧”只描述接口协议，实际机身拍摄验收仍需逐机型完成。
最新保存 RPC 阻塞、超时回库与跨进程保护实现见 [STORE_RPC_RECOVERY.md](STORE_RPC_RECOVERY.md)。

## 本轮发现与已做修改

- 20 份固件都具有本次审计覆盖的核心拍摄方法、JNI 图像句柄字段和原生映射导出。**NEX-5R / 5T / 6 缺少 9 个近似彩色取景所需的 Gamma / RGB 矩阵方法**；现已接入原色取景，完整 LUT 成片计算独立于该能力。
- Android 版本和保存协议并不一一对应。A5100、A7S、RX100 III 都是 API10，却已有具名保存通知；A6000、A7、A7R、A5000、NEX 和 HX60V/HX400V 使用索引确认。继续按接口能力选择协议。
- 所有取得的固件均配置 **24 MiB Dalvik 堆**。备份配置里的 `4Gb/8Gb` 是 Sony 平台内存标识，不能拿它直接设置应用堆或 LUT 缓存预算。运行时机身日志需要补齐实际可用内存与分配失败阶段。
- 拍摄代码通过 `config/capture-maps.json` 选择准确的平台/像素内存表，校验固件文件或六份固定依赖的完整摘要，再按机身报告的最大原生比例尺寸贯穿设置、显影、保存校验和回看。A6300 保持原有 MAP24R1；42MP 等固件专用表在设备上读取，APK 不复制这些固件文件。
- 新增**拍摄前原生接口预检**：解析 OptimizedImage 的整数句柄字段，以及属性读取、映射、解除映射所需的导出，在打开相机前报告具体缺项。映射结果增加空服务、空指针和长度检查。
- **原片保存与 LUT 调色重叠只保留给 A6300 / 2.01**。依据 Sony `PROP_MODEL_NAME`，而非固件普遍使用的 Android `Build.MODEL=ScalarA`。其他机型等待原片确认后才改写同一图像缓冲，减少尚未验证的编码器读写并发。完整 LUT、照片尺寸和双文件流程不变。
- 后台准备分配资源期间标记 worker 正在工作，页面暂停时延后清理，避免准备与释放同时操作 LUT / 回看缓冲。

## 逐机型结果

“新”指具名保存通知 / `getPhysicalMemoryInfo`；“旧”指索引与完整文件确认 / 内存属性。内存标识来自固件备份配置字符串，不是本轮运行时读数。完整的方法、字段缺项及输入摘要在 [firmware-audit.json](firmware-audit.json)。

| 机型 | 官方固件 | Android API | 保存 / 映射 | 固件内存标识 | 后续设备验收重点 |
|---|---|---:|---|---|---|
| A5000 | 1.10 | 10 | 旧 / 旧 | ILDCSC_MUSASHI4Gb | 20MP 显影/双保存、8 MiB 缓存运行 |
| A5100 | 3.10 | 10 | 新 / 新 | ILDCSC_MUSASHI8Gb | 24MP 初始化、串行原片/成片保存设备证据 |
| A6000 | 3.21 | 10 | 旧 / 旧 | ILDCSC_MUSASHI8Gb | beta.6 旧协议真实保存、索引时序 |
| A6300 | 2.01 | 16 | 新 / 新 | ILDCSC_MUSASHI8Gb | 本候选两张实体快门回归 |
| A6500 | 1.06 | 16 | 新 / 新 | ILDCSC_MUSASHI8Gb | 24MP 初始化、串行保存设备证据 |
| A7 | 3.20 | 10 | 旧 / 旧 | ILDCSC_MUSASHI8Gb | beta.6 旧协议真实保存、索引时序 |
| A7 II | 4.01 | 16 | 新 / 新 | ILDCSC_MUSASHI8Gb | 24MP 初始化、串行保存设备证据 |
| A7R | 3.20 | 10 | 旧 / 旧 | ILDCSC_MUSASHI8Gb | 36MP 显影/双保存与索引时序 |
| A7R II | 4.01 | 16 | 新 / 新 | ILDCSC_MUSASHI8Gb | 42MP 显影/保存峰值与 16 MiB 缓存 |
| A7S | 3.20 | 10 | 新 / 新 | ILDCSC_MUSASHI8Gb | 12MP 显影/双保存 |
| A7S II | 3.01 | 16 | 新 / 新 | ILDCSC_MUSASHI8Gb | 12MP 双保存、防抖与镜头状态 |
| NEX-5R | 1.03 | 10 | 旧 / 旧 | 未提取到上述命名的字符串 | 原色取景、16MP 显影/保存与 8 MiB 缓存 |
| NEX-5T | 1.01 | 10 | 旧 / 旧 | 同上 | 同上 |
| NEX-6 | 1.03 | 10 | 旧 / 旧 | 同上 | 同上 |
| RX100 III | 2.00 | 10 | 新 / 新 | DSCSC_MUSASHI8Gb | 20MP 显影/保存、镜头伸缩/变焦生命周期 |
| RX100 IV | 2.00 | 16 | 新 / 新 | DSCSC_MUSASHI8Gb | 同上 |
| RX100 V | 2.00 | 16 | 新 / 新 | DSCSC_MUSASHI8Gb | 同上 |
| RX10 II | 1.30 | 16 | 新 / 新 | DSCSC_MUSASHI8Gb | 同上 |
| HX60V | 2.10 | 10 | 旧 / 旧 | DSCSC_KOJIRO4Gb | 4:3 20MP 显影与索引保存 |
| HX400V | 2.10 | 10 | 旧 / 旧 | DSCSC_KOJIRO4Gb | 同上 |

**固件缺口**：RX1R II、RX10 III、HX90、WX500 在检查的 Sony 日/美区域页面没有取得下载包；HX60 / HX400 非 V 型号也没有单独包。本表保留实际取得的 V 型号身份，后续用准确机型固件或设备只读证据补齐。HX90V 也没有独立固件证据。

研究目标以 [OpenMemories / fwtool 设备表](https://github.com/ma1co/fwtool.py/blob/master/devices.yml)中标有 apps 的独立相机为入口。Recipe Lab 表里列作未知的 A68、A77 II、A99 II、RX100 II、初代 RX10 没有在该设备表中标注 apps；它们先核实能否安装 PMCA，不作为已支持产品机型。

## 本地计算与指令仿真结果

- 20/20 原始 `libcameraseq.so` 的 ARM `getProperty` 指令执行通过：`memory-address` / `memory-size` 分别请求后端属性 1 / 2；负数地址文本与共享字符串缓冲行为一致。仿真只替换底层属性取值和 libc/logging，没有调用实际 AV 服务或映射物理内存。[结果](memory-arm-emulation.json)
- 80/80 原始 ARM `mapSharedMemory` / `unmapSharedMemory` 场景通过：20 份固件分别覆盖对齐、非对齐、打开设备失败、mmap 失败。验证页内偏移、返回长度、失败时不写输出以及成功映射的 munmap/close 顺序；系统调用由数学桩替换。[结果](mapping-arm-emulation.json)
- 9/9 完整 33³ 数学 LUT 压力场景通过：4240×2832 至 7952×5304，1/2/4 工作线程，1/8/16/32 MiB 缓存组合。每个像素与不使用缓存的算法比较，检查行填充、首尾边界及缓存预算。6000×4000 在 1 MiB 缓存下仍保持完全相同的计算结果。[结果](geometry-tests.json)
- 128 MiB 是 LUT 加速表的**虚拟地址预留**，默认已触及缓存页预算为 32 MiB；不能把两者混为实际驻留内存。24MP UYVY 像素区约 48 MB，42MP 示例约 84 MB。相机路径映射 Sony 已有图像，不新增一份完整 RGB 图。两个 750×500 RGBA 回看像素缓冲合计 3 MB。
- 主机测试覆盖现代/旧式内存接口、逐个缺失导出、整数边界、共享返回缓冲、保存协议选择、取消、迟到/重复通知、索引未完成、超时和歧义拒绝；APK 构建检查覆盖 API10、DEX035、真实 ARMv5/v7、v1 签名和发行资源边界。
- 本轮未连接或安装相机；新增本地 Android API10 ARM 模拟器执行证据见 [实现验收](IMPLEMENTATION.md)。固件函数仿真验证接口和地址运算，AV 服务、曝光与 SD 写入时序仍由后续机身验收覆盖。

## 拍后等待的恢复行为

原片/成片共用一个带代次的保存事务：具名回调与回调缺失恢复均要求完整 JPEG 和新增匹配索引，确认后只推进一次。延迟索引、迟到/重复回调、部分 JPEG、停止请求和超时后的恢复已有可执行测试。等待超时进入核验界面，继续只读检查，不重提保存；MENU 会阻止后续调色/保存并等待当前照片确认。

rc.3 已把文件/索引核验移到独立线程。原生保存调用未返回时仍可核验，但只有磁盘确认与调用返回共同满足才释放资源。30 秒后可回库，持有记录阻止重入；永久不返回的 AV 服务需要机身日志定位。
用户日志继续使用 **更多 → 导出诊断日志 → `SONYLUT/DIAG/REPORT.ZIP`**。按实际机型、固件、应用版本和最后完成阶段分组；没有终止事件只记为记录不完整，不直接归因 OOM。原始评论中的“卡死”尚未逐条对应到具体版本与日志。

## 复现与证据位置

固件下载入口和每台机型的页面见 [firmware-sources.json](firmware-sources.json)。下载 URL 必须保留 Sony 提供的查询参数：删除 `fm` 后直接请求旧路径会返回 404，保留参数会重定向至 Sony 的 `di.update.sony.net` 下载站。

```sh
python3 -m venv out/compat-venv
out/compat-venv/bin/pip install -r scripts/requirements-compatibility.txt
out/compat-venv/bin/python scripts/collect_pmca_firmware.py \
  --output results/compatibility-20261101/firmware \
  --fwtool /absolute/path/to/fwtool.py --jobs 3
out/compat-venv/bin/python scripts/audit_pmca_firmware.py \
  results/compatibility-20261101/firmware/collection.json \
  --output out/firmware-audit.json
out/compat-venv/bin/python scripts/emulate_legacy_memory_property.py /path/to/libcameraseq.so
out/compat-venv/bin/python scripts/emulate_sony_mapping.py \
  results/compatibility-20261101/firmware/collection.json \
  --output out/mapping-arm-emulation.json
bash scripts/test_capture_pipeline.sh results/compatibility-20261101/firmware/collection.json
bash scripts/test_capture_compatibility.sh
bash scripts/test_product_ui.sh
bash scripts/build_android.sh --release
```

固件与解包输出约 33 GiB，留在忽略的 `results/compatibility-20261101/firmware/`。下载包摘要、原始提取配置和解包日志保留在每机型目录；Git 仅包含脚本、JSON 事实摘要与本文。构建和主机测试记录在 `results/compatibility-20261101/`，本候选 APK 在 `dist/SonyLutTest.apk`。
