package org.hongen.sonylutprobe;

import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.os.SystemClock;
import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import org.json.JSONObject;

/** Sony's full-resolution JPEG path. Confined to the capture I/O thread. */
final class HardwareJpeg {
    static final class Result {
        final File output;final String uri;final JSONObject stats;final Bitmap preview;
        Result(File output,String uri,JSONObject stats,Bitmap preview){this.output=output;this.uri=uri;this.stats=stats;this.preview=preview;}
    }
    private final ContentResolver resolver;private final Context context;private final File dcim;
    private final IndexReady indexReady=new IndexReady();private boolean receiverRegistered;
    private final BroadcastReceiver indexReceiver=new BroadcastReceiver(){public void onReceive(Context ignored,Intent intent){
        indexReady.changed();android.util.Log.i("SonyLutCapture","index_updated at_ms="+SystemClock.elapsedRealtime());
    }};
    private final Class<?> imageType,decodeOptions,encoderType,encodeOptions,media;
    private final Method decode,encode,imageInfo,screenNail;
    private final Uri external;private final String mediaId;private final PhotoIndex index;
    private static final class Photo {
        final String uri;final File file;final int orientation;
        Photo(String uri,File file,int orientation){this.uri=uri;this.file=file;this.orientation=orientation;}
    }
    private final Map<String,Photo> metadata=new LinkedHashMap<String,Photo>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Photo> eldest){return size()>32;}
    };
    private long cache;private Bitmap sparePreview;
    private Bitmap acquirePreview(int width,int height) {
        Bitmap bitmap=sparePreview;sparePreview=null;
        if(bitmap!=null&&(bitmap.isRecycled()||bitmap.getWidth()!=width||bitmap.getHeight()!=height)){
            if(!bitmap.isRecycled())bitmap.recycle();bitmap=null;
        }
        return bitmap!=null?bitmap:Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
    }
    // Ownership transfers from the detached ImageView to the capture I/O thread.
    void offerPreview(Bitmap bitmap) {
        if(bitmap==null||bitmap.isRecycled())return;
        if(!bitmap.isMutable()||bitmap.getConfig()!=Bitmap.Config.ARGB_8888){bitmap.recycle();return;}
        if(sparePreview!=null)sparePreview.recycle();sparePreview=bitmap;
    }
    void preparePreview(int width,int height) {
        if(width<1||height<1)return;
        int scale=Math.max(1,(Math.max(width,height)+799)/800);
        int w=Math.max(1,width/scale),h=Math.max(1,height/scale);
        if(sparePreview!=null&&sparePreview.getWidth()==w&&sparePreview.getHeight()==h)return;
        if(sparePreview!=null)sparePreview.recycle();sparePreview=null;
        sparePreview=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
    }
    HardwareJpeg(Context context,File sd,File lut,int workers) throws Exception {
        this.context=context.getApplicationContext();this.resolver=this.context.getContentResolver();dcim=new File(sd,"DCIM").getCanonicalFile();
        imageType=Class.forName("com.sony.scalar.graphics.OptimizedImage");
        decodeOptions=Class.forName("com.sony.scalar.graphics.OptimizedImageFactory$Options");
        decode=Class.forName("com.sony.scalar.graphics.OptimizedImageFactory").getMethod("decodeImage",String.class,decodeOptions);
        encoderType=Class.forName("com.sony.scalar.graphics.JpegExporter");
        encodeOptions=Class.forName("com.sony.scalar.graphics.JpegExporter$Options");
        encode=encoderType.getMethod("encode",imageType,String.class,encodeOptions);
        media=Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
        external=(Uri)media.getField("EXTERNAL_CONTENT_URI").get(null);
        mediaId=(String)media.getMethod("getMediaId",Uri.class).invoke(null,external);
        index=new PhotoIndex(new PhotoIndex.Query(){public List<PhotoIndex.Entry> read(String selection,String order,int limit)throws IOException{
            Cursor cursor=HardwareJpeg.this.resolver.query(external,new String[]{"_data","rec_order","dcf_folder_number","dcf_file_number"},selection,null,order);
            if(cursor==null)throw new IOException("Sony image index unavailable");
            List<PhotoIndex.Entry> rows=new ArrayList<PhotoIndex.Entry>();
            try{while(rows.size()<limit&&cursor.moveToNext())rows.add(new PhotoIndex.Entry(cursor.getString(0),cursor.getLong(1),cursor.getInt(2),cursor.getInt(3)));}
            finally{cursor.close();}return rows;
        }},dcim);
        imageInfo=media.getMethod("getImageInfo",String.class);
        screenNail=Class.forName("com.sony.scalar.graphics.AvindexGraphics").getMethod("getScreenNail",String.class);
        cache=Native.prepareDeviceLut(lut.getPath(),workers);
        try{this.context.registerReceiver(indexReceiver,new IntentFilter("com.sony.scalar.database.avindex.action.AVINDEX_DATABASE_UPDATED"));receiverRegistered=true;}
        catch(RuntimeException e){Native.releaseDeviceLut(cache);cache=0;throw e;}
    }
    private Photo photoFor(String uri) throws Exception {
        Photo cached=metadata.get(uri);if(cached!=null)return cached;
        Object info=imageInfo.invoke(null,uri);
        if(info==null)throw new IOException("Sony image metadata unavailable: "+uri);
        try {
        Method attr=info.getClass().getMethod("getAttribute",String.class);
        String dir=(String)attr.invoke(info,"DCF_TBLDirName"),name=(String)attr.invoke(info,"DCF_TBLFileName");
        if(dir==null||name==null||!dir.matches("[0-9]{3}[A-Z0-9_]{5}")||!name.matches("[A-Z0-9_]{1,8}\\.JPG"))
            throw new IOException("unsupported Sony image path");
        // dcim is canonical; both Sony-provided components are restricted to
        // DCF names above. No per-image realpath walk is necessary.
        File file=new File(new File(dcim,dir),name);
        int orientation=((Integer)info.getClass().getMethod("getAttributeInt",String.class,int.class).invoke(info,"Orientation",1)).intValue();
        Photo photo=new Photo(uri,file,orientation);metadata.put(uri,photo);return photo;
        }finally{info.getClass().getMethod("recycle").invoke(info);}
    }
    private Photo find(final File source) throws Exception {
        PhotoIndex.Entry entry=indexReady.await(new IndexReady.Lookup(){public PhotoIndex.Entry read()throws IOException{return index.source(source);}},3000);
        Photo photo=photoFor(entry.uri);
        if(!photo.file.equals(source.getAbsoluteFile()))throw new IOException("source DCF path mismatch");
        return photo;
    }
    Result process(File source) throws Exception {
        Object image=null,encoder=null;Bitmap preview=null;boolean delivered=false;
        long start=SystemClock.elapsedRealtime();
        try {
            if(cache==0)throw new IOException("hardware LUT cache is closed");
            Photo original=find(source);String sourceUri=original.uri;
            int sourceAttempts=indexReady.attempts;long sourceWait=indexReady.waitMs;
            long indexed=SystemClock.elapsedRealtime();
            Object options=decodeOptions.newInstance();decodeOptions.getField("imageType").setInt(options,3); // MAIN, not screen nail
            decodeOptions.getField("colorType").setInt(options,1); // sRGB
            image=decode.invoke(null,sourceUri,options);
            if(image==null)throw new IOException("Sony JPEG decode failed");
            long decoded=SystemClock.elapsedRealtime();
            int width=((Integer)imageType.getMethod("getWidth").invoke(image)).intValue(),height=((Integer)imageType.getMethod("getHeight").invoke(image)).intValue();
            int scale=Math.max(1,(Math.max(width,height)+799)/800);
            preview=acquirePreview(Math.max(1,width/scale),Math.max(1,height/scale));
            long allocated=SystemClock.elapsedRealtime();
            JSONObject stats=new JSONObject(Native.deviceLutReview(image,cache,preview));
            long filtered=SystemClock.elapsedRealtime();
            preview=rotateReview(preview,original.orientation);long previewed=SystemClock.elapsedRealtime();
            encoder=encoderType.newInstance();Object settings=encodeOptions.newInstance();
            encodeOptions.getField("quality").setInt(settings,3); // EXTRA FINE
            // Snapshot immediately before the native write. No cached watermark
            // survives app pauses, card changes, or a failed previous save.
            final long before=index.latestOrder();long marked=SystemClock.elapsedRealtime();
            encode.invoke(encoder,image,mediaId,settings); // Sony owns JPEG, SD write and index registration.
            long saved=SystemClock.elapsedRealtime();
            final String inputUri=sourceUri;
            PhotoIndex.Entry savedEntry=indexReady.await(new IndexReady.Lookup(){public PhotoIndex.Entry read()throws IOException{return index.savedAfter(before,inputUri);}},3000);
            String outputUri=savedEntry.uri;File output=photoFor(outputUri).file;long bytes=output.length();
            if(output.equals(source.getAbsoluteFile())||bytes<4)
                throw new IOException("Sony saved image verification failed");
            stats.put("backend","sony_hardware").put("source_uri",sourceUri).put("output_uri",outputUri)
                .put("source_lookup_ms",indexed-start).put("decode_ms",decoded-indexed)
                .put("source_index_attempts",sourceAttempts).put("source_index_wait_ms",sourceWait)
                .put("output_index_attempts",indexReady.attempts).put("output_index_wait_ms",indexReady.waitMs)
                .put("lut_wall_ms",filtered-allocated).put("preview_alloc_ms",allocated-decoded).put("preview_rotate_ms",previewed-filtered).put("fused_review",true)
                .put("save_mark_ms",marked-previewed).put("encode_save_ms",saved-marked).put("save_order",savedEntry.order).put("index_mode","dcf_incremental")
                .put("verify_ms",SystemClock.elapsedRealtime()-saved).put("bytes",bytes);
            delivered=true;return new Result(output,outputUri,stats,preview);
        }finally {
            if(!delivered&&preview!=null)preview.recycle();
            // No mapped memory or worker may outlive the Java-owned Sony image.
            for(Object value:new Object[]{image,encoder})if(value!=null)try{value.getClass().getMethod("release").invoke(value);}
                catch(Exception e){android.util.Log.w("SonyLutCapture","hardware release",e);}
        }
    }
    Bitmap review(File file,String knownUri) throws Exception {
        Photo photo=knownUri!=null?photoFor(knownUri):find(file);String uri=photo.uri;
        byte[] jpeg=(byte[])screenNail.invoke(null,uri);
        if(jpeg==null||jpeg.length==0)throw new IOException("Sony review image unavailable");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=2;
        Bitmap bitmap=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,options);
        if(bitmap==null)throw new IOException("Sony review image decode failed");
        return rotateReview(bitmap,photo.orientation);
    }
    private static Bitmap rotateReview(Bitmap bitmap,int orientation) {
        Matrix matrix=new Matrix();
        switch(orientation) {
            case 2:matrix.setScale(-1,1);break;
            case 3:matrix.setRotate(180);break;
            case 4:matrix.setScale(1,-1);break;
            case 5:matrix.setRotate(90);matrix.postScale(-1,1);break;
            case 6:matrix.setRotate(90);break;
            case 7:matrix.setRotate(270);matrix.postScale(-1,1);break;
            case 8:matrix.setRotate(270);break;
            default:return bitmap;
        }
        Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);
        if(rotated!=bitmap)bitmap.recycle();return rotated;
    }
    void close(){if(receiverRegistered){context.unregisterReceiver(indexReceiver);receiverRegistered=false;}
        if(cache!=0){Native.releaseDeviceLut(cache);cache=0;}if(sparePreview!=null){sparePreview.recycle();sparePreview=null;}}
}
