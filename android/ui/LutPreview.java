package org.hongen.sonylutprobe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.*;
import java.nio.ByteBuffer;

/** Small reference images rendered by the same LUT kernel as photo preview. */
final class LutPreview {
    private final ByteBuffer jpeg;
    private final int width,height;
    private final PreviewCache<Bitmap> cache=new PreviewCache<Bitmap>(2L*1024*1024,new PreviewCache.Disposal<Bitmap>(){
        public void release(Bitmap bitmap){bitmap.recycle();}
    });
    LutPreview(Context context)throws IOException{
        InputStream input=context.getAssets().open("PREVIEW.JPG");ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try{byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)bytes.write(buffer,0,n);}finally{input.close();}
        byte[] encoded=bytes.toByteArray();BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(encoded,0,encoded.length,options);
        if(options.outWidth<1||options.outHeight<1)throw new IOException("LUT 参考图无效");
        width=options.outWidth;height=options.outHeight;jpeg=ByteBuffer.allocateDirect(encoded.length);jpeg.put(encoded);jpeg.position(0);
    }
    Bitmap cached(String id){return cache.get(id);}
    void pin(String id){cache.pin(id);}
    Bitmap render(LutLibrary.Entry entry){
        long engine=0;
        try{
            engine=Native.createCompiled(entry.file.getPath(),LutBinaryCache.binary(entry.file).getPath(),entry.id,1);
            Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            try{Native.frame(engine,jpeg,jpeg.capacity(),bitmap);return bitmap;}
            catch(RuntimeException e){bitmap.recycle();throw e;}
        }finally{if(engine!=0)Native.destroy(engine);}
    }
    boolean store(String id,Bitmap bitmap){return cache.put(id,bitmap,(long)bitmap.getRowBytes()*bitmap.getHeight());}
    void clear(){cache.clear();}
}
