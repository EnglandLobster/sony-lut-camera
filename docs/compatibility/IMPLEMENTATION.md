# 多像素机型拍摄链路与保存恢复 · 2026-11-01

本页记录 **0.9.0-beta.7-rc.2 / code108** 已完成代码接入与离线验证。公开版仍为 beta.6；候选未安装到相机。工作在独立 checkout `out/a7-startup-fix` 的 `fix/pmca-firmware-compatibility` 分支。

后续 rc.3 已实现独立核验、超时回库与跨进程持有保护，见 [保存 RPC 恢复](STORE_RPC_RECOVERY.md)。

## 内存表和全尺寸链路

`config/capture-maps.json` 是唯一配置来源：包含六份固定提交的 PictureEffectPlus 内存表，以及八条官方固件内存表指纹。构建时生成 `CAPMAP.TXT`，运行时按 Sony 内存类型、像素类别、修订号、文件大小和 SHA-256 选表。固件表只读取 `/system/etc/memmap`，不打入 APK；配置缺失或摘要不匹配时，在拍摄前明确报错。

| 机型组 | 内存表选择 | 目标全尺寸示例 | LUT 缓存 / 线程 |
|---|---|---|---|
| A7S / A7S II | 机身 8Gb12 r0 | 4240×2832 | 32 MiB / 4 |
| NEX-5R / 5T / 6 | 固定 unknown16 r0 | 4912×3264 | 8 MiB / 1 |
| A5000 | 固定 ILC4Gb20 r1 | 5456×3632 | 8 MiB / 2 |
| HX60V / HX400V | 固定 KOJIRO4Gb20 r1 | 5184×3888，4:3 | 8 MiB / 2 |
| RX100 III / IV / V、RX10 II | 机身 DSC8Gb20 r0 | 5472×3648 | 32 MiB / 4 |
| A5100 / A6000 / A7 II | 固定 ILC8Gb24 r1 | 6000×4000 | 32 MiB / 4 |
| A6300 | 原有 MAP24R1 原字节 | 6000×4000 | 32 MiB / 4 |
| A6500 | 机身 ILC8Gb24 r1 | 6000×4000 | 32 MiB / 4 |
| A7 | 机身 ILC8Gb24 r2 | 6000×4000 | 32 MiB / 4 |
| A7R | 机身 ILC8Gb36 r2；固定 r1 后备 | 7360×4912 | 16 MiB / 4 |
| A7R II | 机身 ILC8Gb42 r0 | 7952×5304 | 16 MiB / 4 |

表中尺寸是测试用例；应用从机身公布的尺寸中选择最大原生比例全尺寸，贯穿参数设置、显影图像核对、JPEG 校验和回看。比例或像素类别不匹配时拒绝拍摄。六份固定表另含 DSC MUSASHI4Gb20 r1，尚无单独机型实拍承诺。

4Gb/8Gb 是 Sony 平台标识，固件的 Java 堆配置均为 24 MiB。上述 LUT 缓存是独立 native 预算；128 MiB 加速表虚拟地址预留和实际触及页分开记录。算法保持完整 LUT 网格和全像素处理，无降采样。回看图保持原比例且不超过 750×500，两个 RGBA 缓冲最多约 3 MB。

NEX 缺少 Gamma/矩阵接口时使用明确标示的原色取景；完整 LUT 成片计算保持启用。能力检查只读取类声明，不初始化 Sony JNI。旧机没有公布的可选变焦参数不再盲目写入；公布的参数仍在 RAW 前读回验证。

## 保存回调丢失的恢复

`CaptureStoreCompletion` 替代旧机专用完成器，统一新旧协议：

1. 每次 `storeImage` 前记录索引水位和事务代次。
2. 具名回调按准确文件核验；没有回调时，轮询唯一新文件，要求文件稳定、完整 JPEG、正确尺寸和新增匹配索引。
3. 两条路径都等待索引，避免原片索引迟到后被下一次成片保存误认为第二个新文件。确认只推进一次；旧代次、已接收文件和重复通知不再推进。
4. 超时切换到保存核验界面，持续只读检查；确认后继续。MENU 请求停止后，不再提交成片或下一张，当前保存确认后返回。部分/歧义文件保留待核验。
5. 若照片已完整保存并进入索引，但尺寸错误，保留文件并结束失败事务，允许退出，不无限等待。

A6300 / 2.01 保留原先实拍通过的原片保存与调色重叠；其余机型确认原片保存后才改写图像。若 Sony 保存 RPC 本身不返回，worker 仍被阻塞，界面明确显示接口尚未返回。此时不重复保存、不强制释放未确认缓冲；固件服务级阻塞需要对应机身日志进一步定位。

## 本地验收

- **20/20 固件选表**：使用已解包的真实官方文件，校验选中路径、修订号和摘要；另测六份打包配置、损坏、缺失、错误平台和畸形目录拒绝。
- **16/16 拍摄状态链路**：八种尺寸 × 新旧保存协议，主机 `-Xmx24m` 与 Android API10 / 24 MiB Dalvik 均执行。覆盖丢失原片/成片回调、部分 JPEG、错误尺寸、索引延迟、迟到/重复通知、超时后确认、停止请求和歧义拒绝。文件/索引由测试夹具提供。
- **9/9 主机全尺寸原生计算**：完整 33³ 数学 LUT、12–42MP、1/8/16/32 MiB 预算，逐像素与无缓存算法比较，行填充/首尾边界全部通过。
- **9/9 API10 ARM 原生执行**：与主机相同的九组 12–42MP / 1–32 MiB 配置全部通过全像素与边界校验；执行时间属于模拟器，不作相机性能估算。完整运行结果保存在本地验收 JSON。
- **30/30 API10 初始化分配**：十个内置 LUT × 8/16/32 MiB 缓存，加两张受限 RGBA 回看图；实测 Dalvik 最大堆 25,165,824 字节，全部创建/释放成功。
- **3/3 取景能力夹具**：现代、旧机缺接口、部分接口，能力检查均未触发 Sony 静态初始化。
- 原有全 RGB、JPEG、二进制 LUT、预览拟合、产品逻辑测试通过。发行 APK 的 API10 / DEX035 / ARMv5-v7 / v1 签名及资源边界检查通过。
- 前一轮 20 项真实固件 ARM 属性指令与 80 项映射/失败清理仿真结果继续有效，见本目录 JSON。

本轮模拟器首次长时间运行时，部分 ADB shell 通道未返回且未见目标进程；后续执行已取得 42MP 原生结果与 API10 套件的有效输出。检查还发现主机装有两个不同版本的 ADB，验证脚本现在允许用 `ADB` 明确选择同一客户端。无结果的尝试不计为通过。

精确 APK 摘要和各项结果在忽略目录 `results/compatibility-implementation-20261101/VALIDATION.json`，原始日志在同目录。模拟器、固件、SDK 和私有素材均在 Git 外。

## 复现

```sh
bash scripts/build_android.sh --release
bash scripts/test_product_ui.sh
bash scripts/test_capture_pipeline.sh results/compatibility-20261101/firmware/collection.json
bash scripts/test_capture_compatibility.sh
# 启动专用 API10 ARM 模拟器，安装本候选并打开 LUT 库后：
ADB="$(command -v adb)" bash scripts/test_legacy_emulator.sh emulator-5580
```

不提供固件目录也可运行 `test_capture_pipeline.sh`，执行独立的六份内存表及状态机测试。原生 ARM 数值测试使用 NDK r16b、`SONY_BUILD_BENCHMARKS=ON` 编译 `sony-model-tests`；以数学 `COLOR33.CUB width height cacheMiB workers` 为参数在模拟器执行。

## 尚需机身证据

此候选的 A6300 实体快门回归，以及其他机型的实际 AV 显影、保存、SD 时序与镜头生命周期尚待完成。RX1R II、RX10 III、HX90/WX500、非 V HX60/HX400 等缺少准确固件；不猜测 18MP 机身应套用哪个 20MP 内存表。现有 REPORT.ZIP 导出入口保持可用，后续按实际机型/固件和最后完成阶段归档用户反馈。
