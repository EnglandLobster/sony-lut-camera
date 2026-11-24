package io.github.englandlobster.lutcamera;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import java.io.*;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Deletes a saved photo through Sony's image index, the way playback does,
 * so the camera database never lists a file that is gone. One index content
 * is one DCF number: a RAW+JPEG pair goes together. */
final class PhotoDelete {
    private final ContentResolver resolver;private final Uri external;private final Method deleteImage;
    PhotoDelete(Context context)throws Exception{
        Class<?> media=Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
        external=(Uri)media.getField("EXTERNAL_CONTENT_URI").get(null);
        deleteImage=media.getMethod("deleteImage",ContentResolver.class,Uri.class,long.class);
        resolver=context.getContentResolver();
    }
    /** RAW saved beside a graded JPEG under the same DCF number, or null. */
    static File raw(File jpeg){
        String name=jpeg.getName();int dot=name.lastIndexOf('.');
        File raw=new File(jpeg.getParentFile(),(dot<0?name:name.substring(0,dot))+".ARW");
        return raw.isFile()?raw:null;
    }
    static int[] dcf(File jpeg)throws IOException{
        File dir=jpeg.getParentFile();
        if(dir==null||!dir.getName().matches("[0-9]{3}[A-Z0-9_]{5}")||!jpeg.getName().matches("[A-Z0-9_]{4}[0-9]{4}\\.JPG"))
            throw new IOException("照片路径无效："+jpeg.getName());
        return new int[]{Integer.parseInt(dir.getName().substring(0,3)),Integer.parseInt(jpeg.getName().substring(4,8))};
    }
    /** Deletes the photo with this JPEG's DCF number (and its RAW, if any).
     * A photo already gone from both index and card counts as deleted. */
    void delete(File jpeg)throws Exception{
        long begin=android.os.SystemClock.elapsedRealtime();int[] n=dcf(jpeg);File raw=raw(jpeg);
        long id=PhotoDeleteTarget.settle(new PhotoDeleteTarget.Lookup(){public List<Long> ids(int folder,int file)throws IOException{
            Cursor cursor=resolver.query(external,new String[]{"_id"},"dcf_folder_number="+folder+" AND dcf_file_number="+file,null,null);
            if(cursor==null)throw new IOException("相机照片索引不可用");
            List<Long> ids=new ArrayList<Long>();
            try{while(cursor.moveToNext())ids.add(cursor.getLong(0));}finally{cursor.close();}
            return ids;
        }},n,jpeg,raw!=null,PhotoDeleteTarget.ATTEMPTS,PhotoDeleteTarget.WAIT_MS);
        if(id<0){android.util.Log.i("LutUI","photo_delete file="+jpeg.getName()+" already_gone=true");return;}
        long queried=android.os.SystemClock.elapsedRealtime();
        if(!((Boolean)deleteImage.invoke(null,resolver,external,id)).booleanValue())throw new IOException("相机拒绝删除 "+jpeg.getName());
        boolean jpegGone=!jpeg.exists(),rawGone=raw==null||!raw.exists();
        android.util.Log.i("LutUI","photo_delete file="+jpeg.getName()+" id="+id+" raw="+(raw!=null)+" jpeg_gone="+jpegGone+" raw_gone="+rawGone
            +" query_ms="+(queried-begin)+" delete_ms="+(android.os.SystemClock.elapsedRealtime()-queried));
        if(!jpegGone)throw new IOException(jpeg.getName()+" 删除后仍在卡上");
        if(!rawGone)throw new IOException(raw.getName()+" 删除后仍在卡上");
    }
}
