package io.github.englandlobster.lutcamera;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import java.io.*;
import java.lang.reflect.Method;
import java.util.*;

/** Reads the preview a saved photo already carries, for history: the JPEG's
 * own MPF large thumbnail, else Sony's screen-nail (camera closed only), else
 * a subsampled decode of the photo. No camera session, LUT cache, re-encode or
 * mutation of the saved photo. */
final class SonyPhotoPreview {
    private final PhotoIndex index;
    private final Method screenNail,imageInfo;
    /** False over an open camera: Sony's screen-nail decode fails there. */
    private final boolean screenNails;
    SonyPhotoPreview(final Context context,boolean screenNails)throws Exception{
        this.screenNails=screenNails;
        Class<?> media=Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
        final Uri external=(Uri)media.getField("EXTERNAL_CONTENT_URI").get(null);
        imageInfo=media.getMethod("getImageInfo",String.class);
        screenNail=Class.forName("com.sony.scalar.graphics.AvindexGraphics").getMethod("getScreenNail",String.class);
        index=new PhotoIndex(new PhotoIndex.Query(){public List<PhotoIndex.Entry> read(String selection,String order,int limit)throws IOException{
            Cursor cursor=context.getContentResolver().query(external,new String[]{"_data","rec_order","dcf_folder_number","dcf_file_number"},selection,null,order);
            if(cursor==null)throw new IOException("相机照片索引不可用");
            List<PhotoIndex.Entry> rows=new ArrayList<PhotoIndex.Entry>();
            try{while(rows.size()<limit&&cursor.moveToNext())rows.add(new PhotoIndex.Entry(cursor.getString(0),cursor.getLong(1),cursor.getInt(2),cursor.getInt(3)));}
            finally{cursor.close();}return rows;
        }},new File(Environment.getExternalStorageDirectory(),"DCIM").getCanonicalFile());
    }
    Bitmap load(final File file)throws Exception{
        long begin=SystemClock.elapsedRealtime();
        if(!file.isFile())throw new IOException("照片已移动或 SD 卡不可用");
        JpegPreview.Found embedded=null;
        try{embedded=JpegPreview.read(file);}catch(IOException e){android.util.Log.w("LutUI","gallery_embedded_unreadable file="+file.getName()+" "+e.getMessage());}
        if(embedded!=null&&embedded.jpeg!=null){
            long read=SystemClock.elapsedRealtime();
            Bitmap bitmap=decode(embedded.jpeg);long decoded=SystemClock.elapsedRealtime();
            Bitmap result=ReviewLoader.rotate(bitmap,embedded.orientation);
            android.util.Log.i("LutUI","gallery_decode file="+file.getName()+" source=mpf bytes="+embedded.jpeg.length+" read_ms="+(read-begin)
                +" decode_ms="+(decoded-read)+" rotate_ms="+(SystemClock.elapsedRealtime()-decoded)+" orientation="+embedded.orientation);
            return result;
        }
        if(!screenNails){
            // No embedded preview and no Sony decoder: subsample the photo itself.
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(file.getPath(),options);
            if(options.outWidth<=0||options.outHeight<=0)throw new IOException("照片尺寸无效");
            options.inSampleSize=sample(options);options.inJustDecodeBounds=false;
            Bitmap bitmap=BitmapFactory.decodeFile(file.getPath(),options);
            if(bitmap==null)throw new IOException("照片读取失败");
            android.util.Log.i("LutUI","gallery_decode file="+file.getName()+" source=photo sample="+options.inSampleSize+" ms="+(SystemClock.elapsedRealtime()-begin));
            return ReviewLoader.rotate(bitmap,embedded==null?1:embedded.orientation);
        }
        PhotoIndex.Entry entry=new IndexReady().await(new IndexReady.Lookup(){public PhotoIndex.Entry read()throws IOException{return index.source(file);}},3000);
        long indexed=SystemClock.elapsedRealtime();
        Object info=imageInfo.invoke(null,entry.uri);if(info==null)throw new IOException("照片信息暂不可用");int orientation;
        try{orientation=((Integer)info.getClass().getMethod("getAttributeInt",String.class,int.class).invoke(info,"Orientation",1)).intValue();}
        finally{info.getClass().getMethod("recycle").invoke(info);}
        long metadata=SystemClock.elapsedRealtime();
        byte[] jpeg=(byte[])screenNail.invoke(null,entry.uri);
        if(jpeg==null||jpeg.length==0)throw new IOException("相机预览暂不可用，请重新打开照片");
        long read=SystemClock.elapsedRealtime();
        Bitmap bitmap=decode(jpeg);
        long decoded=SystemClock.elapsedRealtime();Bitmap result=ReviewLoader.rotate(bitmap,orientation);
        android.util.Log.i("LutUI","gallery_decode file="+file.getName()+" source=screen_nail index_ms="+(indexed-begin)+" info_ms="+(metadata-indexed)
            +" read_ms="+(read-metadata)+" decode_ms="+(decoded-read)+" rotate_ms="+(SystemClock.elapsedRealtime()-decoded));
        return result;
    }
    private static int sample(BitmapFactory.Options options){
        int sample=1;while(Math.max(options.outWidth,options.outHeight)/sample>1024)sample*=2;return sample;
    }
    private static Bitmap decode(byte[] jpeg)throws IOException{
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,options);
        if(options.outWidth<=0||options.outHeight<=0)throw new IOException("照片预览尺寸无效");
        options.inJustDecodeBounds=false;options.inSampleSize=sample(options);
        Bitmap bitmap=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,options);
        if(bitmap==null)throw new IOException("照片预览读取失败");
        return bitmap;
    }
}
