# 仓库说明

## 这份文件的定位

本文件记录仓库结构、构建方法、所有者明确做过的决定，以及在真机上观察到的事实。

2026-11-15 之前的版本里有大量“必须／禁止”式的拍摄、保存、恢复细则。那些是 AI 助手自己写的，所有者没有提出过，已全部删除。代码、测试和 `docs/` 里仍有按那些细则实现的行为，见下方“当前实现”。它们是现状，不是要求：有证据或有明显收益时可以改，改的时候同步更新测试和文档。

不要再往这里添加所有者没说过的限制。工程上的顾虑写成“观察到什么、代价多大”，由所有者决定取舍。

## 范围与结构

本仓库是 Sony PMCA 相机上的 LUT Camera，以及可选的测试连接工具。改动行为前先读 `README.md`、`PRODUCT_UI.md`、`docs/STATUS.md`、`docs/ARCHITECTURE.md`。本地的 `HANDOFF.md` 和 `results/` 不入库。

- Java 在 `android/{app,capture,preview,storage,ui,platform,diagnostics}/`，所有类同属一个包。
- C++/JNI 在 `native/`，Java 测试在 `android-tests/`，资源在 `android-assets/` 和 `android-res/`。
- 测试连接工具在 `dev-connect/`。
- `PreJpegProbeActivity` 名字像探针，实际是正式拍摄流程的基类。
- 机型支持资料在 `docs/compatibility/`，内存映射的来源记录在 `config/capture-maps.json`，日志导出的说明在 `docs/DIAGNOSTICS.md`。

工作区里的规范位置是 `apps/sony-pmca/lut-camera/`。仓库单独克隆到别处也要能构建，所以不通过相对路径引用同级应用或参考仓库。

## 构建与测试

依赖和工具链见 `docs/BUILD.md`。在仓库根目录运行：

```sh
python3 scripts/bootstrap_dependencies.py --check
bash scripts/build_host.sh
python3 scripts/test_offline.py
bash scripts/test_product_ui.sh
bash scripts/test_lut_cache.sh
bash scripts/test_preview_fit.sh
bash scripts/build_android.sh
```

相机运行环境是 Java 7 / API 10，native 用 C++11。改了拍摄流程跑 `scripts/test_capture_pipeline.sh`，改了映射、缓存或画幅跑 `scripts/test_capture_compatibility.sh`。测试素材用数学生成的图，不用用户的照片或 LUT。

汇报结果时分清主机测试、构建、上机和实拍四种，不把前一种说成后一种。

## 所有者的决定

- 一次快门保存原片和调色成片两个独立文件。成片用完整 LUT 处理全尺寸图像，取景预览是近似。
- 成片速度是产品指标。2026-11-15：多机型上 10 多秒不符合预期，A6300 是 4 秒左右。
- 自有代码 GPL-3.0-only。素材授权另记在 `ASSET_LICENSES.md` 和 `THIRD_PARTY_NOTICES.md`。
- 包名从 v0.9.1 起是 `io.github.englandlobster.lutcamera`，测试连接工具保持 `org.hongen.sonydevconnect`。
- 所有者明确要求发布某个版本时，就把这次发布做完：构建并校验发布包，发布 GitHub Release 及 APK、源码包、校验和，确认公开下载可用，更新下载和安装链接。不需要逐步再次确认。没有所有者指示不撤下已发布的版本。
- 小红书宣传材料（`docs/promo/` 等）留在本地，不入库。

## 日常做法

- 提交前把仓库本地的 `user.name` 设为 `EnglandLobster`，`user.email` 设为 `93986837+EnglandLobster@users.noreply.github.com`，`core.hooksPath` 设为 `.githooks`，并运行 `python3 scripts/check_git_identity.py`。
- 提交信息用 `fix(preview): …` 这类聚焦的写法。
- SDK、APK、日志、用户照片、用户的 LUT 收藏、凭据和签名文件不入库。
- 不清空 `out/`：`out/test.keystore` 是已安装应用的签名身份，丢了用户就得卸载重装。
- 对外分发用 `build_android.sh --release`，不用默认的调试包。说明哪个 APK 上过机。
- 往相机装包前，先退出拍摄页，让测试连接工具在前台。
- 可能损坏相机或丢失用户照片的操作（例如改固件）先问所有者。

## 真机观察

这些是事实记录，括号里是来源。新的观察按同样方式补充。

- Android 的 `Build.MODEL` 在多数机身上是 `ScalarA`，真实型号要读 Sony 的 `PROP_MODEL_NAME`（用户日志的会话头）。
- 内存映射随平台、像素数和修订号不同，取自相机固件里对应的文件；A6300 用 `MAP24R1`（`config/capture-maps.json`）。
- 有的机身保存完成时会回调文件名，例如 A6300 2.01、A6500 1.05。有的没有，例如 A7 3.20，目前靠预测下一个 DCF 文件名确认（2026-11 用户日志）。
- 完成回调里的整数是回执编号，不是状态码，会出现 0 以外的值（A6300 连拍日志、A6500 日志）。
- 一次声明保存两个文件时，相机可能在两个文件都写完后才更新索引（A6000，issue #4）。
- Sony 的存储卡文件系统不允许读一个正在被写入的文件，会报 EBUSY（issue #3）。
- 第一次保存前 `DCIM` 可能不存在，相机会自己创建（rc.8）。
- 从卡上读文件约 5 MB/s。整文件校验一张 10 MB 的 JPEG 约 2 秒，20 MB 以上约 4.5 秒（A6500 issue #8、A7 的 NAS 报告，2026-11-15 分析）。
- A6300 写出的 JPEG：主图之后附一张预览图，再用 0 补齐到 32768 字节的整数倍；最后的非零字节是 EOI（本地 14 张实拍文件）。其他机身的文件尾没有样本。
- Sony 自家拍摄应用在离开检视状态时调用 `cancelAutoPictureReview()`，检视时间对应相机参数 `auto-review-time`（`vendor/sony-picture-effect-plus` 里的 `AutoReviewState`、`ShootingExecutor`）。
- A6300 2.01 上验证过两件事：`storeImage` 返回后编码器不再读源图像，所以 LUT 可以和原片保存同时进行；完成回调到达时文件已写完。
- A7R II 4.01：朋友的专版（`references/sony/a7r2-film-camera`，从本仓库 beta.5 分出）正式拍摄一直是“保存调用返回即开始 LUT + 回调后只核对文件头”，不分机型判断；所有者 2026-11-16 转述其用户反馈没有问题。这是实际使用的证据，不是逐像素核对。
- 其他有回调的机身（A6500、RX1R II 等）与上面两台同代、同一套保存接口，重叠是按这两台推广的，没有各自验证。
- A7 3.20 上，重新应用取景预览的调用总是在成片保存调用返回后 9.8–10.0 秒才完成，与文件大小无关（同一用户 11 张，NAS 报告，2026-11-15）。原因未查明；一个待验证的猜测是相机自己的“自动检视”时间，应用目前没有通过 `CameraEx.AutoPictureReviewControl` 关掉它。A6300 上没有这个固定等待。

## 当前实现（现状，不是要求）

- 保存后的文件确认：所有机身核对文件头尺寸和文件尾的结束标记，不再整文件回读；文件尾不是 Sony 的常规形式时退回整文件扫描（日志里 `complete_by=tail` 或 `scan`）。2026-11-16 改，主机和 API10 模拟器测试通过，未在任何相机上实拍。
- LUT 与原片保存重叠：有保存完成回调的机身，原片的保存调用一返回就开始 LUT，不等文件确认。2026-11-16 起不再限 A6300。没有回调的老机身（A7、A7R、A6000 等）仍等原片文件确认后才开始 LUT，约多 0.3 秒；这类机身没有重叠的实机证据。
- 进入拍摄时把相机的“自动检视”时间设为 0，退出时恢复（`CaptureAutoReview`，日志 `auto_review`）。
- `CaptureStoreCompletion` 统一判定保存完成：先等保存调用返回，再按回调文件名、索引或预测的 DCF 文件名确认。
- `CaptureRecoveryFence` 在拍摄进程异常退出后阻止再次拍摄，直到相机重启。
- 超时只让界面脱离等待，不打断还在进行的保存调用。
- 画质选相机支持的最高档，界面显示实际选中的档位，退出时恢复原设置。
- 诊断日志是私有存储和存储卡上的定长日志，导出时不含照片、LUT 内容和凭据。

细节见 `docs/ARCHITECTURE.md` 和各测试脚本。
