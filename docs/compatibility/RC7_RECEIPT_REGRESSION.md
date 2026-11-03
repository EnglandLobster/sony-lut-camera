# RC7 保存卡死：回调整数被误当作成功状态码

2026-11-03。本地修复候选 `0.9.0-beta.7-rc.7-fix1 / code114`，未发布；真机连续拍摄、退出原生相机和重新开机验收待完成。

## 已定位的代码错误

RC3 提交 `797af93` 在 `PreJpegProbeActivity` 的 Sony 保存完成回调中增加：

```java
final boolean success=value instanceof Number&&((Number)value).intValue()==0;
if(success)storeCompletion.notified(token,directory,file);
```

RC7 `5326658` 保留该过滤。实际 A6300 成功拍摄记录中，第一组原片/调色片的整数均为 0，第二组均为 1，第三组均为 2。这些记录发生在错误过滤加入之前；照片都继续完成了双保存。该整数不是“0 才成功”的状态码。修复将其保留为不参与成功判定的 `receiptId`，不依赖其起点或连续性。

当有效通知携带非零整数时，Activity 丢弃文件路径，完成状态机只剩索引恢复。如果 Sony 索引等声明的两条记录全部提交后才发布，程序就等待原片索引而不提交第二条，形成循环。RC7 虽然取消了具名路径的索引前置条件，但这个上游过滤让后续拍摄进不了具名路径。

相机原生“未正确完成写入”提示发生在用户反馈中；本次两份日志都没有该次保存事务的事件。已确认的实现缺陷是上述回调过滤及其导致的未完成双保存；原生恢复提示的具体触发事件仍需现场保存记录或实机复现。

## 为什么此前测试漏过

此前状态机测试直接调用 `CaptureStoreCompletion.notified()`，绕过 Activity 的整数过滤；接口测试只送入 0。API10 模拟器同样使用这些输入，因此通过结果没有覆盖真实的连续拍摄回调。

现在 `test_store_receipt_dispatch.py` 直接提取并编译 Activity 的原始回调体，只替换 Android 时钟和调度队列。指定 `--baseline-ref v0.9.0-beta.7-rc.7` 时，旧代码第一组成功、第二组编号 1 卡住；候选代码在同样不发布索引的条件下完成 0/1/2/41 四组双保存。另有反射回调到保存状态机的五组测试，覆盖非零起始编号 41、重复回调、RPC 未返回、以及 Sony 延后创建 DCIM。

## 本次收到的两个诊断包

| 诊断包 | 日志自报环境 | 实际保留的事件 |
|---|---|---|
| REPORT (1) | A7 / 3.20 / beta.6 code106 | 两个拍摄会话均为 shot=0，正常清理退出；无保存事务 |
| REPORT (2) | A5100 / 3.10 / RC5 code111 | 去重后五个拍摄会话都在 prepare 抛出 `DCIM directory unavailable`，尚未打开相机 |

第二个包确认了目录前置检查失败。旧实现要求在相机启动之前就有 DCIM；这会拒绝尚未建立照片目录的卡。候选在非扫描的产品路径只要求 SD 根目录可用，DCIM 若存在则必须是目录，照片目录仍由 Sony 创建。现有日志没有区分“目录不存在”与“目录暂不可访问”，因此同时保留后者的明确报错，不擅自创建、格式化或修复存储卡。

## 补充反馈：取景模式与 LUT 的交叉表现

用户补充 RC7 的使用反馈：某些 LUT 近似预览可以保存，原色取景持续核验；另一个 LUT 在原色取景下同样可以保存。截图未提供这几次拍摄的顺序、各自回调编号及保存阶段日志。

代码核对结果：`PreviewProfileStore` 按 LUT 独立持久化选择；`LibraryActivity.previewSettings()` 允许反复更改，入口为“LUT 库选中对应 LUT → MENU → 当前 LUT 的取景”。首次告警在确认之后不重复弹出，不等于选项被锁定。

两种模式均经过同一 `process → storeToCamera → pollStore → verifyPairStore` 保存流程，LUT 调色仍由 `applyRetainedLut` 执行。快门控制则不同：近似预览通过 `AutoPreviewGate` 在半按时恢复颜色基线、启动 DirectShutter，并在拍摄完成后停止 DirectShutter 再恢复色彩预览；原色模式在初始化时启动 DirectShutter，后续拍摄通过释放锁继续同一会话。尚未验证这些启停是否改变原生回调编号；不将其写成已确认的模式相关根因。

实拍验收增加交叉条件：同一个 LUT 的近似/原色模式分别从新会话拍摄至少三组；另一个 LUT 重复同样流程；记录每组的模式、应用 shot、原生 receipt_id、原片/调色片完成和索引阶段。验收应区分模式差异、连续拍摄编号和 LUT 数据差异，不能以更换 LUT 后偶然成功作为修复验收。

## 改动与验证范围

- 删除 Activity 的零值过滤，接口参数改为明确的整数 `receiptId`，日志保留原值。
- 原有 RPC 返回、完整 JPEG、尺寸、重复身份和跨进程持有屏障继续生效。
- 允许首次拍摄前不存在 DCIM；SD 根目录缺失、DCIM 为普通文件仍拒绝。
- 实际回调体旧版失败/新版通过、反射端到端五组双保存、原有保存/索引阻塞/停止流程及产品测试通过。
- Release 方式构建 code114，通过包结构、API10 DEX、ARM ABI 和原签名检查。未发布；随后已覆盖安装到用户 A6300，确认 code114、17 项 LUT 保留、启动进入 LUT 库且 boot ID 未变。未触发快门，等待用户实拍；安装记录见 `results/rc7-save-investigation-20261103/a6300-install/INSTALLATION.json`。
- 最终同一 APK 在 API10 / 24 MiB 模拟器完成全部套件（`PASS_ALL_API10_SUITES`），包括新增五组回调双保存、延后创建 DCIM、阻塞保存、跨进程持有保护和 96 次 Canvas 清理；模拟器已关闭。模拟器首次因引擎与 SD 挂载配置不匹配失败，修正为 classic/goldfish 并确认 vfat 挂载后完整重跑通过。

原始记录、两份报告副本和运行结果位于忽略目录 `results/rc7-save-investigation-20261103/`。此前 A6300 实拍原始记录位于 `results/a6300-rc6-20261101/capture-observation/ANDROID_LIVE.txt`；其中名称为 beta.6 之前的内部候选，不能与 beta.7 RC6 混称。
