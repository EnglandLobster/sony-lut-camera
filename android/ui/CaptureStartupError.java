package org.hongen.sonylutprobe;

/** User-facing startup guidance; technical stack traces remain in the SD report. */
final class CaptureStartupError {
    final String title,reason,action;
    private CaptureStartupError(String title,String reason,String action){this.title=title;this.reason=reason;this.action=action;}
    String message(){return reason+"\n\n"+action;}
    static CaptureStartupError from(Throwable error){
        Throwable cause=error;while(cause.getCause()!=null&&cause.getCause()!=cause)cause=cause.getCause();
        String text=String.valueOf(cause.getMessage());
        if(text.contains("内存布局尚未适配")||text.contains("unvalidated platform"))
            return new CaptureStartupError("当前机型尚未适配","当前版本无法使用这台相机的内存配置。","请返回 LUT 库，并把诊断文件发给开发者。\n这不是 LUT 文件损坏。");
        if(cause instanceof NoSuchMethodException||cause instanceof ClassNotFoundException||cause instanceof LinkageError)
            return new CaptureStartupError("相机接口不兼容","当前固件缺少应用需要的拍摄接口。","请返回 LUT 库，将诊断文件发给开发者。\n文件会记录机型和固件信息。");
        if(text.contains("matrix/gamma")||text.contains("gamma buffer")||text.contains("matrix parameter"))
            return new CaptureStartupError("近似取景初始化失败","当前相机无法启用这份 LUT 的近似取景。","返回 LUT 库 → 更多 → 当前 LUT 的取景，\n选择“原色取景”后重试。");
        if(text.contains("capture setting")||text.contains("capture parameter")||text.contains("EXTRA FINE")||text.contains("zoom capability")||text.contains("RAW requires disabled"))
            return new CaptureStartupError("拍摄参数设置失败","相机未接受应用所需的拍摄设置。","请返回并检查相机的画质、尺寸和拍摄模式。\n若再次失败，请把诊断文件发给开发者。");
        if(cause instanceof OutOfMemoryError||text.contains("cannot reserve"))
            return new CaptureStartupError("相机可用内存不足","没有足够内存完成拍摄初始化。","请退出应用后重新打开。\n若再次失败，请把诊断文件发给开发者。");
        if(text.contains("ENOSPC")||text.contains("Permission denied")||text.contains("SD folder"))
            return new CaptureStartupError("无法读写 SD 卡","拍摄准备所需的文件无法写入 SD 卡。","请检查存储卡写保护和剩余空间，\n确认存储卡可用后重试。");
        return new CaptureStartupError("未能开始拍摄","相机在初始化拍摄时返回了错误。","请按 MENU 返回 LUT 库。\n若再次失败，请把诊断文件发给开发者。");
    }
}
