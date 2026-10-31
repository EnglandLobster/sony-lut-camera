package org.hongen.sonylutprobe;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
public final class CaptureStartupErrorTest {
    static void check(boolean yes){if(!yes)throw new AssertionError();}
    public static void main(String[] args){
        CaptureStartupError map=CaptureStartupError.from(new IOException("当前内存布局尚未适配：unknown/24"));
        check(map.title.equals("当前机型尚未适配"));
        check(CaptureStartupError.from(new InvocationTargetException(new NoSuchMethodException("vendor.open"))).title.equals("相机接口不兼容"));
        check(CaptureStartupError.from(new IOException("matrix/gamma not advertised by this camera")).action.contains("原色取景"));
        check(CaptureStartupError.from(new IOException("unsupported capture setting: picture-size")).title.equals("拍摄参数设置失败"));
        check(CaptureStartupError.from(new OutOfMemoryError()).title.equals("相机可用内存不足"));
        CaptureStartupError unknown=CaptureStartupError.from(new IOException("unknown vendor failure"));
        check(unknown.title.equals("未能开始拍摄")&&!unknown.message().contains("unknown vendor"));
        check(unknown.action.contains("MENU"));
        System.out.println("PASS readable startup guidance: nested vendor errors, platform, parameters, preview fallback, memory and unknown failures");
    }
}
