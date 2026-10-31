package org.hongen.sonylutprobe;

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

/** Reads Sony's existing screen-nail JPEG for history. No full image decode,
 * camera session, LUT cache, re-encode or mutation of the saved photo. */
final class SonyPhotoPreview {
    private final PhotoIndex index;
    private final Method screenNail,imageInfo;
    SonyPhotoPreview(final Context context)throws Exception{
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
        PhotoIndex.Entry entry=new IndexReady().await(new IndexReady.Lookup(){public PhotoIndex.Entry read()throws IOException{return index.source(file);}},3000);
        long indexed=SystemClock.elapsedRealtime();
        Object info=imageInfo.invoke(null,entry.uri);if(info==null)throw new IOException("照片信息暂不可用");int orientation;
        try{orientation=((Integer)info.getClass().getMethod("getAttributeInt",String.class,int.class).invoke(info,"Orientation",1)).intValue();}
        finally{info.getClass().getMethod("recycle").invoke(info);}
        long metadata=SystemClock.elapsedRealtime();
        byte[] jpeg=(byte[])screenNail.invoke(null,entry.uri);
        if(jpeg==null||jpeg.length==0)throw new IOException("相机预览暂不可用，请重新打开照片");
        long read=SystemClock.elapsedRealtime();
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,options);
        if(options.outWidth<=0||options.outHeight<=0)throw new IOException("照片预览尺寸无效");
        int sample=1;while(Math.max(options.outWidth,options.outHeight)/sample>1024)sample*=2;
        options.inJustDecodeBounds=false;options.inSampleSize=sample;
        Bitmap bitmap=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,options);
        if(bitmap==null)throw new IOException("照片预览读取失败");
        long decoded=SystemClock.elapsedRealtime();Bitmap result=ReviewLoader.rotate(bitmap,orientation);
        android.util.Log.i("LutUI","gallery_decode file="+file.getName()+" index_ms="+(indexed-begin)+" info_ms="+(metadata-indexed)
            +" read_ms="+(read-metadata)+" decode_ms="+(decoded-read)+" rotate_ms="+(SystemClock.elapsedRealtime()-decoded));
        return result;
    }
}
