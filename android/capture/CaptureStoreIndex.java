package org.hongen.sonylutprobe;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import java.io.*;
import java.util.*;

/** Uses the same bounded DCF queries as HardwareJpeg, without decoding a photo. */
final class CaptureStoreIndex implements CaptureStoreCompletion.Registration,Closeable {
    private final CaptureIndexQueries queries;
    CaptureStoreIndex(final Context context,File sd)throws Exception{
        Class<?> media=Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
        final Uri external=(Uri)media.getField("EXTERNAL_CONTENT_URI").get(null);
        PhotoIndex index=new PhotoIndex(new PhotoIndex.Query(){
            public List<PhotoIndex.Entry> read(String selection,String order,int limit)throws IOException{
                Cursor cursor=context.getContentResolver().query(external,
                    new String[]{"_data","rec_order","dcf_folder_number","dcf_file_number"},selection,null,order);
                if(cursor==null)throw new IOException("Sony image index unavailable");
                List<PhotoIndex.Entry> rows=new ArrayList<PhotoIndex.Entry>();
                try{while(rows.size()<limit&&cursor.moveToNext())rows.add(new PhotoIndex.Entry(
                    cursor.getString(0),cursor.getLong(1),cursor.getInt(2),cursor.getInt(3)));}
                finally{cursor.close();}return rows;
            }
        },new File(sd,"DCIM").getCanonicalFile());
        queries=new CaptureIndexQueries(index);
    }
    public void begin()throws Exception{queries.begin();}
    public PhotoIndex.Entry saved()throws Exception{return queries.saved();}
    public void accepted(File file)throws Exception{queries.accepted(file);}
    public void close(){queries.close();}
    String diagnostics(){return queries.diagnostics();}
}
