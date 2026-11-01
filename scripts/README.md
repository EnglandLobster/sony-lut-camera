# 工具索引

脚本保留现有命令入口；从应用仓库根目录执行。构建／检查为离线操作，采集／USB 操作需要明确的设备测试任务。

| 分组 | 脚本 |
| --- | --- |
| 常用构建 | `build_host.sh`、`build_android.sh [--release]`、`stage_android.py`、`stage_capture_maps.py`、`check_package.py`、`package_release.py` |
| 产品回归 | `audit_release_assets.py`、`test_builtin_catalog.py`、`test_product_ui.sh`、`test_offline.py`、`test_lut_cache.sh`、`test_preview_fit.sh` |
| 多机型固件／计算验证 | `collect_pmca_firmware.py`、`audit_pmca_firmware.py`、`audit_sony_capture_api.py`、`emulate_legacy_memory_property.py`、`emulate_sony_mapping.py`、`audit_store_rpc.py`、`emulate_store_rpc.py`、`test_capture_compatibility.sh`、`test_capture_pipeline.sh [collection.json]`、`test_legacy_emulator.sh EMULATOR_SERIAL`；见 [逐机型报告](../docs/compatibility/README.md) |
| 测试素材 | `generate_preview_fixture.py` 从中性数学 LUT 生成诊断参数 |
| 字体维护 | `build_ui_font.py`；正常 APK 构建复用已审查静态字体 |
| 拟合／性能研究 | `fit_native_preview.py`、`test_native_preview.py`、`benchmark_speed.py`、`create_lut_size_fixtures.py` |
| 日志与设备状态 | `analyze_diagnostics.py`（分析用户 REPORT.ZIP）、`collect_device.py`、`collect_link_state.py`、`collect_sd.py`、`capture_android_log.py`、`capture_native_terminal.py` |
| 拍摄观察 | `observe_continuous.py`、`observe_prejpeg_init.py`、`prejpeg_observation.py`、`test_prejpeg_observation.py` |
| 已暂停的 GPU／固件探索 | `gpu_lut_fixtures.py`、`emulate_camseq_options.py` |
| 已暂停的 USB／虚拟机探索 | `inspect_usb_runtime.py`、`usb_debug_host.py`、`usb_service_probe.py`、`parallels_key_input.py`、`pd_event_route_probe.m`、`requirements-pmca.txt` |

Python 数值助手 `test_preview_fit.py` 由对应 shell 脚本调用。原始证据在 `results/<topic>-YYYYMMDD/`；临时输出在 `out/`。不要为了找工具而执行旧设备探针。
