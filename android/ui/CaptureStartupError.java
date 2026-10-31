package org.hongen.sonylutprobe;

/** User-facing startup guidance; technical stack traces remain in the SD report. */
final class CaptureStartupError {
    final String title,reason,action;
    private CaptureStartupError(String title,String reason,String action){this.title=title;this.reason=reason;this.action=action;}
    String message(){return reason+"\n\n"+action;}
    static CaptureStartupError from(Throwable error){
        Throwable cause=error;while(cause.getCause()!=null&&cause.getCause()!=cause)cause=cause.getCause();
        StringBuilder messages=new StringBuilder();
        for(Throwable item=error;item!=null;item=item.getCause()){
            messages.append(item.getMessage()).append('\n');if(item.getCause()==item)break;
        }
        String text=messages.toString();
        if(text.contains("preview surface lost"))
            return new CaptureStartupError("取景界面连接中断","相机启动后取景界面已被销毁。","请按 MENU 返回 LUT 库后重新进入。\n若仍发生，请提供诊断文件。");
        if(text.contains("preview surface callbacks unavailable"))
            return new CaptureStartupError("取景界面尚未就绪","没有收到相机取景界面的就绪通知。","请按 MENU 返回 LUT 库后重新进入。\n若仍发生，请提供诊断文件。");
        if(text.contains("JPEG quality")&&!text.contains("EXTRA FINE"))
            return new CaptureStartupError("无法设置 JPEG 画质","相机未能提供或保持已选的 JPEG 画质。","请退出应用，选择机内支持的最高 JPEG 画质。\n若仍发生，请提供诊断文件。");
        if(text.contains("capture HDR enabled"))
            return new CaptureStartupError("请先关闭 HDR","HDR 合成与当前 LUT 拍摄流程不能同时使用。","MENU 返回 → 退出应用 → 相机菜单，\n关闭自动 HDR／HDR 绘画，再重新进入。");
        if(text.contains("EXTRA FINE not advertised"))
            return new CaptureStartupError("相机未提供 EXTRA FINE","当前固件没有提供所需的额外精细画质。","请检查原生画质菜单是否有“额外精细”。\n若没有，请将诊断文件发给开发者。");
        if(text.contains("EXTRA FINE")||text.contains("JPEG quality"))
            return new CaptureStartupError("无法设置额外精细画质","应用自动设置 EXTRA FINE 未成功。","请退出应用，将画质设为 JPEG／额外精细，\n然后重新进入 LUT Camera。");
        if(text.contains("picture-size"))
            return new CaptureStartupError("无法设置照片尺寸","应用未能使用所需的 6000×4000 尺寸。","请退出应用，设为 3:2、L（24M），\n然后重新进入 LUT Camera。");
        if(text.contains("storage-fmt"))
            return new CaptureStartupError("无法切换 JPEG 格式","应用自动从 RAW 切换为 JPEG 未成功。","请退出应用，将文件格式设为 JPEG，\n然后重新进入 LUT Camera。");
        if(text.contains("drive-mode"))
            return new CaptureStartupError("无法切换单张拍摄","应用自动设置单张拍摄未成功。","请退出应用，将拍摄模式设为单张，\n关闭连拍／包围曝光后重新进入。");
        if(text.contains("automatic exposure")||text.contains("exposure parameter")||text.contains("exposure compensation"))
            return new CaptureStartupError("无法设置自动曝光","应用未能启用 P 模式和自动 ISO。","请退出应用，将模式转盘设为 P、ISO 自动，\n关闭 HDR 后重新进入 LUT Camera。");
        if(text.contains("内存布局尚未适配")||text.contains("unvalidated platform"))
            return new CaptureStartupError("当前机型尚未适配","当前版本无法使用这台相机的内存配置。","请返回 LUT 库，并把诊断文件发给开发者。\n这不是 LUT 文件损坏。");
        if(cause instanceof NoSuchMethodException||cause instanceof NoSuchFieldException||cause instanceof ClassNotFoundException||cause instanceof LinkageError)
            return new CaptureStartupError("相机接口不兼容","当前固件缺少应用需要的拍摄接口。","请返回 LUT 库，将诊断文件发给开发者。\n文件会记录机型和固件信息。");
        if(text.contains("matrix/gamma")||text.contains("gamma buffer")||text.contains("matrix parameter"))
            return new CaptureStartupError("近似取景初始化失败","当前相机无法启用这份 LUT 的近似取景。","返回 LUT 库 → 更多 → 当前 LUT 的取景，\n选择“原色取景”后重试。");
        if(text.contains("capture setting")||text.contains("capture parameter")||text.contains("EXTRA FINE")||text.contains("zoom capability")||text.contains("RAW requires disabled"))
            return new CaptureStartupError("拍摄参数设置失败","相机未接受应用所需的拍摄设置。","请退出应用，设为单张、JPEG、3:2、L（24M）。\n若仍失败，请提供诊断文件。");
        if(cause instanceof OutOfMemoryError||text.contains("cannot reserve"))
            return new CaptureStartupError("相机可用内存不足","没有足够内存完成拍摄初始化。","请退出应用后重新打开。\n若再次失败，请把诊断文件发给开发者。");
        if(text.contains("ENOSPC")||text.contains("Permission denied")||text.contains("SD folder"))
            return new CaptureStartupError("无法读写 SD 卡","拍摄准备所需的文件无法写入 SD 卡。","请检查存储卡写保护和剩余空间，\n确认存储卡可用后重试。");
        return new CaptureStartupError("未能开始拍摄","相机在初始化拍摄时返回了错误。","请按 MENU 返回 LUT 库。\n若再次失败，请把诊断文件发给开发者。");
    }
}
