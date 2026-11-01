package org.hongen.sonylutprobe;
import android.graphics.Bitmap;
import java.nio.ByteBuffer;
final class Native {
    static { System.loadLibrary("sonylut"); }
    static native String fitPreview(String cube,String binary,String sha256);
    static native int inspectLut(String cube);
    static native int compileLut(String cube,String binary,String sha256);
    static native long create(String cube,int workers);
    static native long createCompiled(String cube,String binary,String sha256,int workers);
    static native void destroy(long engine);
    static native String file(long engine,String input,String output,int scale,boolean apply);
    static native String filePipelined(long engine,String input,String output,int scale,boolean apply);
    static native String frame(long engine,ByteBuffer jpeg,int length,Bitmap output);
    static native String deviceInfo(Object image);
    static native String devicePreflight();
    static native byte[] deviceRead(Object image,int offset,int length);
    static native String deviceLut(Object image,String cube,int workers);
    static native long saveSonyStream(java.io.InputStream stream,String output);
    static native long prepareDeviceLut(String cube,int workers);
    static native long prepareDeviceLutCompiled(String cube,String binary,String sha256,int workers);
    static native long prepareDeviceLutBudget(String cube,String binary,String sha256,int workers,int cacheMiB);
    static native String deviceLutLoadInfo(long cache);
    static native void releaseDeviceLut(long cache);
    static native String deviceLutCached(Object image,long cache);
    static native String deviceLutReview(Object image,long cache,android.graphics.Bitmap bitmap);
    static native void devicePreview(Object image,android.graphics.Bitmap bitmap);
}
