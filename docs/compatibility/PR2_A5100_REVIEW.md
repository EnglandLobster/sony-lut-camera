# PR #2 审查与 rc.3 整合 · 2026-11-02

**结论：采纳动画清理修复，未发现该核心修复的阻断问题。** 已与公开 rc.3 整合为 **0.9.0-beta.7-rc.4 / code110**。PR 的三个原始提交通过合并提交保留，合并提交另以 Co-authored-by 归属 latteye。rc.4 已作为公开测试包发布，未安装相机。

审查对象：[latteye / Sky Yuan 的 PR #2](https://github.com/EnglandLobster/sony-lut-camera/pull/2)，固定 head `cdc0c54e4d787830257be4d48a763f68321a85a4`。原提交分别是清理修复 `b9fde342`、构建适配 `16942d3f`、候选记录 `cdc0c54e`。整合基线为已发布 rc.3 的 `797af93`；代码与测试来源保留这一归属。

## 根因与修复判断

作者提供的 A5100 / 固件 3.10 / Android 2.3.7（API10）日志顺序是：全尺寸显影、LUT 处理、原片保存、成片保存均完成，然后 `show_review` 在显影动画清理时失败。`Source.release()` 调用 `Canvas.setBitmap(null)`，API10 实现直接访问传入对象，产生 NPE；错误处理再次调用同一清理，引发主线程未捕获异常。[AOSP API10 Canvas](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-2.3.7_r1/graphics/java/android/graphics/Canvas.java#L135-L146)

作者报告 A5100 用户安装其 rc.1 后，拍后退出问题已修复；其 rc.2 是后续版本/记录整理。作者没有把尚未执行的真实图形测试标成通过，完整连续拍摄、设置恢复和原生退出验收仍有待办。系统重启的完整根因也没有被这份 Java 堆栈覆盖。

本地补测已用公开 rc.3 的原始源代码，在 API10、实际 24 MiB Dalvik 堆上重现相同 `Canvas.setBitmap → Source.release → end` 异常。固定后的同一测试通过，因此核心判断同时有作者机身记录、平台源码和本地执行支持。

具体修复合理：

- `Source.release()` 丢弃不再复用的 Canvas 引用，然后仅回收自己持有的三个小位图。没有触碰捕获图像或调用方位图。
- `end()` 先结束时间线、断开当前 source 并重置混合状态，再释放旧 source，重复结束保持安全。
- `PhotoHud` 在正常完成和绘制失败路径中先停止动画，再做资源清理，避免清理抛错后仍留下后续动画请求。

我们先前的 API10 套件运行了状态机和 JNI/回看分配，没有实际执行这一 Canvas 清理路径。本次已把真实 Bitmap/Canvas 回归纳入常规模拟器套件。

## 与最新包的结合

他的修复位于 UI 动画生命周期，rc.3 的保存恢复位于捕获/存储所有权层，两者可同时保留。整合采用补丁粒度：

| 内容 | 整合处理 |
|---|---|
| DevelopingPreview / PhotoHud | 纳入清理修复，保留 rc.3 的原生比例缩略图和等比显示 |
| 12–42MP 配置、完整 LUT、保存恢复 | 未修改；相应状态机与原生库保留 |
| 图形测试 | 纳入原测试，扩为 3:2、4:3、竖图各 32 轮，增加实际留白与尺寸断言 |
| API10 runner | 把图形测试接入现有单 guest-shell 套件，保留 24 MiB 属性设置/恢复、阻塞 RPC 和跨进程持有测试 |
| 诊断 Activity | 纳入数学渐变输入与重复清理检查；仍排除在发行 Activity 外 |
| Windows 构建 | 纳入 NDK host 选择和 Java apksigner 调用；本地 macOS 发行构建通过，Windows 证据归属作者 |
| 版本与签名 | 原 PR code108 低于公开 code109，使用新的 rc.4/code110；沿用本仓库发行密钥，不引入作者的开发签名 |
| 文档 | 原 PR 的历史测试结果保留原版本归属；公开下载更新为 rc.4，历史实拍仍保留原版本归属 |

这些是与已经演进的 main 对接所需的调整，不把作者基于旧 main 使用 code108 认定为原 PR 当时的缺陷。不要整文件替换我们的渲染器、版本文件或模拟器脚本。

## 完成的本地验证

- API10 / 24 MiB：rc.3 清理异常实际复现；修复版 96 轮真实 Canvas 绘制通过，包括混合、替换、重复/部分清理、旧回调、下一张中断和调用方所有权。
- 对最终 rc.4 APK 执行常规 API10 套件：96 轮图形、30 组 LUT/回看分配、16 组拍摄协议、4 组阻塞 RPC、实际跨进程持有记录测试全部通过。
- 产品主机套件、发行构建、API10/DEX035/ARM/v1 签名、源码/APK 资源边界与脚本语法检查通过。
- 与公开 rc.3 比较，31 项原生库、assets、界面资源逐字节相同；拍摄状态机、native、内存配置和依赖锁没有修改。
- 签名证书与公开 rc.3 相同，安装到本地模拟器成功；未安装真实相机。

实际 Activity 销毁、Handler 队列和 Sony 原生退出仍属于机身验收；图形测试没有代替这些验收。原始输出和精确 APK 摘要在忽略目录 `results/pr2-review-20261101/VALIDATION.json`。

```sh
bash scripts/build_android.sh --release
# 比较测试需要 Git checkout 内保留 v0.9.0-beta.7-rc.3 tag：
ADB="$(command -v adb)" bash scripts/test_preview_graphics_emulator.sh emulator-5580
ADB="$(command -v adb)" bash scripts/test_legacy_emulator.sh emulator-5580
```

发行包对应的本地构建：`dist/LUT-Camera-0.9.0-beta.7-rc.4.apk`，SHA-256 `8359965d9c3e6af3328fe3106895ca9063edac35e45f8e0170142bc83b7de2dd`。
