package org.hongen.sonylutprobe;
import java.lang.reflect.Method;

/** Sony's OSD output precision is separate from Bitmap/Surface pixel formats.
 * Mirrors native IndexLayoutGridViewBase: ABGR8888 while displaying images,
 * RGBA4444 when handing the graphics plane back to the camera. */
final class SonyGraphicsPrecision {
    private final Method change;
    private final Object high,low;
    private boolean owned;
    SonyGraphicsPrecision()throws Exception{
        Class<?> lib=Class.forName("com.sony.scalar.sysutil.didep.Gpelibrary");
        Class<?> type=Class.forName("com.sony.scalar.sysutil.didep.Gpelibrary$GS_FRAMEBUFFER_TYPE");
        change=lib.getMethod("changeFrameBufferPixel",type);high=type.getField("ABGR8888").get(null);low=type.getField("RGBA4444").get(null);
    }
    void acquire()throws Exception{
        if(owned)return;owned=true; // A throwing native setter may have applied.
        change.invoke(null,high);android.util.Log.i("SonyGraphicsDepth","owner_acquired mode=ABGR8888");
    }
    void release()throws Exception{
        if(!owned)return;change.invoke(null,low);owned=false;
        android.util.Log.i("SonyGraphicsDepth","owner_released mode=RGBA4444");
    }
}
