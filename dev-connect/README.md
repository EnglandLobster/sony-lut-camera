# 测试连接 · 开发辅助 APK

当前基线 **0.6**（已在 A6300 实机安装并通过退出／重新连接验收），包名 `org.hongen.sonydevconnect`。这是 LUT Camera 的可选开发工具，打开后连接指定 Wi-Fi 并启动 ADB/Telnet；不负责处理照片，不随开机自动运行。

## 网络配置

当前开发 SSID 在 `src/org/hongen/sonydevconnect/ConnectActivity.java` 的 `NETWORK` 常量中。迁移到另一网络时先修改目标 SSID。首次需要通过原版 Tweak 连上该网络，辅助应用才从相机内部 supplicant 配置读取并保存匹配项。密码不写入源码、APK、日志或主机。

A6300 的 `/data/data` 与 `/data/system` 位于 RAM 盘；配置另存持久 `/data/SONYDEV/NET.CFG`，通过原生 bridge 访问。不要把配置文件加入 Git。

## 退出与构建

MENU 退出先让旧任务失效，确认 daemon 属于本包后停止 ADB，等待 Wi-Fi 关闭，再交回原生拍摄。主应用通过同签名 IPC 请求清理，使用平台 ResultReceiver 兼容旧 Android；失败保留错误状态，允许重试。不要绕过此回执直接退出相机应用。

在仓库根执行：

```sh
python3 scripts/bootstrap_dependencies.py --helper
bash dev-connect/build.sh
```

先按主应用 [构建说明](../docs/BUILD.md) 配好环境与同一签名密钥。输出 `dist/SonyDevConnect.apk`。复用固定 OpenMemories Tweak 0.11 的 `libtweak.so`、`libadbd.so`，Java Shell/NativeException 保留 JNI 类名和 MIT 许可。来源及固定摘要在 `dependencies.lock.json`。

历史设备日志和 OFF/ON、退出验收记录保留在本地工作区，已验证结论见 [状态](../docs/STATUS.md)。

## 0.6 重新进入恢复

Sony 原生退出可能保留 singleTask Activity。退出流程使用按访问代次隔离的 ExitVisit：重新启动 Intent 或离开前台后恢复，会解除上一轮退出状态；若清理仍在执行，先等清理完成再重连，并跳过已过时的原生退出请求。旧连接进度和退出回调按代次拒绝；原生退出在前台等待超过 15 秒时显示可重试状态。

主机测试覆盖同实例重入、清理期间重新打开、重复 Menu、迟到回调、失败重试和销毁；相机 MENU→原生拍摄→重新打开已获用户确认；同一 PID 的日志记录退出清理 1098ms、重入状态复位，并自动恢复 ADB/Telnet。
