# Sony 保存 RPC 阻塞与恢复 · 2026-11-01

公开测试包 **0.9.0-beta.7-rc.3 / code109**。新增独立保存核验、原生调用返回屏障、超时回库和跨进程持有记录。beta.6 历史发行版继续保留，本包未安装到相机。

## 固件证据

20 份官方固件的 `CameraSeqUtil::sendSDComMsg` 都把 `SDComIPC::sendSync` 的等待参数设为 **0**。原始 ARM 指令执行确认：参数为 0 时进入 `pthread_cond_wait`，没有截止时间；正数进入 `pthread_cond_timedwait`。每台机型的库摘要、函数地址、调用点和 Java 方法表保存在 [静态审计](store-rpc-audit.json)，仿真结果在 [ARM 验证](store-rpc-arm.json)。

保存调用的路径为：

```text
CameraSequence.storeImage
  → nativeStoreImage
  → CameraSequenceDiadem::storeImage
      → 保留源图时：取得/复制内存票据的同步请求
      → 提交保存的同步请求
  → CameraSeqUtil::sendSDComMsg
  → SDComIPC::sendSync
      → 加锁、登记等待事务
      → send → osal_snd_msg（传输阶段）
      → pthread_cond_wait（等待答复阶段）
      → 取答复、移除等待事务、解锁
```

旧 NEX 的 native 保存函数只有一个布尔参数，较新的接口有两个；应用仍按现有公开的 `storeImage(image, releaseSource)` 调用。20 份 `CameraSequence` Java 声明均没有保存取消方法。另一个 DSP 类的 `cancel` 不属于保存事务。

离线执行覆盖每份固件三个场景，共 **60 项**：

- 答复缺失：停在真实 `pthread_cond_wait` 调用点，事务仍在，输出未确认；随后向同一个 ARM 上下文注入迟到答复，验证完成及等待记录清理。
- 传输阻塞：停在发送阶段，尚未进入答复等待，也没有移除等待事务。
- 正数截止时间：原始超时分支返回 `-5` 并清理本地等待记录。测试没有执行任何取消后台保存的操作。

这些结果定位了明确的无限等待机制；用户评论中每一次卡死的具体停点，仍需对应日志区分传输、答复、索引和文件读取。没有把 Android 线程中断、修改固件等待参数或并发 `release` 用作取消保存。

## 应用改动

**保存线程与核验线程分开。** Sony 调用保持在原工作线程，避免改变其调用线程契约；单独的 `store-confirmation` 线程串行处理回调、文件与索引核验。保存线程被挂起时，核验仍可完成。

**两项证据共同允许推进。** 完整 JPEG + 新增匹配索引确认磁盘保存；`rpcReturned` 确认原生调用已停止访问入参。两者都满足才推进调色、下一次保存或清理。已核验文件的结果会缓存，避免等待 RPC 时每秒重新扫描大 JPEG。超时和 `cancel` 都不能撤销仍在调用中的图像所有权。

**超时可退出当前屏幕。** 30 秒后 MENU 返回 LUT 库，停止后续调色/保存。独立 capture 进程、工作线程和图像引用继续保留；暂停/销毁页面跳过会触发 Sony 调用的显示清理，等保存确认与 RPC 返回后再释放。迟到成功会完成收尾；原片阶段停止不再提交成片，成片阶段停止则保留两张照片。

**库页面禁止重入。** 每次真正保存前，先在应用私有存储写入 `CAPTURE.PENDING`，使用原子替换、同步落盘和进程间文件锁。持有者完成原生资源释放后才能清除。普通异常退出或重启应用不会把它当作已完成；内核 boot ID 改变后才允许新会话接管。旧进程不能清掉新 boot 的记录。等待期间禁止再次拍摄、硬件相册、测试连接和返回原生相机；库与日志入口保持可用。恢复后页面自动提示可重新拍摄。

**独立保存等待快照。** UI watchdog 每 5 秒检查一次，在私有 `CAPTURE.WAIT` 记录索引快照/原生调用/调用已返回阶段、RPC 状态、磁盘确认、核验线程最近时间、worker Java 栈、线程 ID、可读的内核 `wchan` 和 syscall 编号。快照不访问 SD，也不调用 Sony 相机接口；不含照片、LUT 数据或内存内容。导出的 `SUMMARY.TXT` 包含快照。清理后标注 released，保留最后一次等待观察。错误提示页的日志行改为直接导出，修复它仍跳转旧中间页的问题。

## 本地验证

- 20/20 固件静态路径核验；60/60 原始 ARM 控制流场景通过。
- 16 组既有新旧协议/多尺寸故障链路继续通过。
- 4 组真实 Java 阻塞线程测试：新旧保存协议 × 继续/停止。独立核验先成功时，不提前推进；迟到 RPC 返回后只完成一次，停止状态保留。拒绝在调用中取消、再次提交和重复完成。
- 持有记录验证覆盖准确持有者清理、重入拒绝、boot 切换、旧持有者晚到、损坏记录和独立私有快照；另用两个实际进程验证写入者退出后记录仍阻止新进程。
- API10 ARM 模拟器运行相同 Java 套件及 30 组既有 JNI/回看分配。实际 Java 最大堆必须是 25,165,824 字节；API10 `AndroidRuntime` 会覆盖命令行 `-Xmx`，脚本仅在确认是 API10 模拟器后设置 `dalvik.vm.heapsize=24m`，结束恢复原值。
- 额外的界面入口注入检查因旧模拟器 ADB/shell 异常、缺少 `am force-stop` 未完成，未计入通过；实际页面退出/恢复仍列入实机验收。API10 工作线程与跨进程套件已保留完整 guest 输出。
- 发行构建、API10 / DEX035 / ARMv5-v7 / v1 签名和资源边界检查通过。像素算法没有改变，沿用 rc.2 的主机及 API10 ARM 各九组完整 LUT 数值证据。

精确候选包、最终检查和模拟器输出在忽略目录 `results/store-rpc-20261101/VALIDATION.json`；原始固件未写入 APK。

```sh
out/compat-venv/bin/python scripts/audit_store_rpc.py \
  results/compatibility-20261101/firmware/collection.json \
  --output docs/compatibility/store-rpc-audit.json
out/compat-venv/bin/python scripts/emulate_store_rpc.py \
  results/compatibility-20261101/firmware/collection.json \
  --output docs/compatibility/store-rpc-arm.json
bash scripts/test_capture_pipeline.sh
bash scripts/test_product_ui.sh
bash scripts/build_android.sh --release
ADB="$(command -v adb)" bash scripts/test_legacy_emulator.sh emulator-5580
```

## 实机待验收

下一项是 A6300 的正常双保存/退出回归与受控迟到观察，随后收集其他机型的实际等待快照。此次没有注入相机故障、模拟快门或修改固件。

若原生服务永久不返回，应用会保持原会话和重入保护，无法在同一次开机中安全地强制重建相机。用户可回库查看提示；存储灯熄灭后正常关机再开机是该状态下的恢复路径。这里完成的是安全离开屏幕、保持缓冲所有权、迟到自动收尾与禁止危险重入，服务本身的永久阻塞仍需真机停点和 AV 端证据。
