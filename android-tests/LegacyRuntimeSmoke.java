package org.hongen.sonylutprobe;

import android.graphics.Bitmap;
import android.os.Build;
import java.io.File;

/** Emulator-only JNI/bitmap startup regression, without Sony hardware calls. */
public final class LegacyRuntimeSmoke {
    public static void main(String[] args)throws Exception{
        long heap=Runtime.getRuntime().maxMemory();
        if(Build.VERSION.SDK_INT!=10||heap>24L*1024*1024)
            throw new IllegalStateException("requires API10 and a maximum 24 MiB heap");
        System.out.println("runtime sdk="+Build.VERSION.SDK_INT+" heap="+heap);
        int count=0;
        for(LutLibrary.Entry entry:new LutLibrary(new File(args[0])).entries()){
          for(int budget:new int[]{8,16,32}){
            long cache=0;Bitmap a=null,b=null;
            try{
                LutBinaryCache.verifySource(entry);
                cache=Native.prepareDeviceLutBudget(entry.file.getPath(),LutBinaryCache.binary(entry.file).getPath(),entry.id,budget==8?1:4,budget);
                if(cache==0)throw new AssertionError("null native cache");
                a=Bitmap.createBitmap(budget==8?666:750,500,Bitmap.Config.ARGB_8888);
                b=Bitmap.createBitmap(budget==8?666:750,500,Bitmap.Config.ARGB_8888);
                a.eraseColor(0xff123456);b.eraseColor(0xff654321);
                if(a.getPixel(1,1)!=0xff123456||b.getPixel(1,1)!=0xff654321)throw new AssertionError("bitmap mismatch");
                System.out.println("PASS "+entry.name+" grid="+entry.size+" "+Native.deviceLutLoadInfo(cache));count++;
            }finally{
                if(a!=null)a.recycle();if(b!=null)b.recycle();if(cache!=0)Native.releaseDeviceLut(cache);
            }
          }
        }
        if(count==0)throw new AssertionError("empty library");
        System.out.println("PASS legacy startup allocations count="+count);
    }
}
