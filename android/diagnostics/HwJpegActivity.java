package org.hongen.sonylutprobe;
import android.app.Activity;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

/** ADB-only codec probe: reads an existing image and creates new scratch outputs. */
public final class HwJpegActivity extends Activity {
    private final Handler ui=new Handler();private TextView status;private volatile boolean running;private File run;
    private void event(String kind,Object value) {
        final String line=kind+" "+value;android.util.Log.i("SonyHwJpeg",line);
        ui.post(new Runnable(){public void run(){status.setText(line);}});
        if(run!=null)try{FileWriter w=new FileWriter(new File(run,"PROBE.JSN"),true);try{w.write(line+"\n");}finally{w.close();}}catch(IOException ignored){}
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);status=new TextView(this);status.setTextSize(18);status.setText("JPEG 硬件接口测试准备中…");setContentView(status);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        running=true;new Thread(new Runnable(){public void run(){probe();}},"sony-hw-jpeg").start();
    }
    private String imageUri() throws Exception {
        String explicit=getIntent().getStringExtra("source_uri");if(explicit!=null)return explicit;
        Class<?> media=Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
        Uri uri=(Uri)media.getField("EXTERNAL_CONTENT_URI").get(null);
        Cursor cursor=getContentResolver().query(uri,new String[]{"_id","_data"},null,null,"_id DESC");
        if(cursor==null)throw new IOException("image provider returned no cursor");
        try{String first=null;while(cursor.moveToNext()){
            String data=cursor.getString(1);event("media",cursor.getLong(0)+" "+data);if(first==null)first=data;
        }if(first==null)throw new IOException("no images in provider");return first;}finally{cursor.close();}
    }
    private Set<String> mediaUris() throws Exception {
        Class<?> media=Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");Uri uri=(Uri)media.getField("EXTERNAL_CONTENT_URI").get(null);
        Cursor cursor=getContentResolver().query(uri,new String[]{"_data"},null,null,null);if(cursor==null)throw new IOException("no media cursor");
        Set<String> out=new HashSet<String>();try{while(cursor.moveToNext())out.add(cursor.getString(0));}finally{cursor.close();}return out;
    }
    private void probe() {
        Object image=null,exporter=null;long cache=0;
        try {
            File root=new File("/mnt/wv_cache/HWJPEG");if(!root.isDirectory()&&!root.mkdir())throw new IOException("probe scratch unavailable");
            root.setReadable(true,false);root.setExecutable(true,false);
            if(getIntent().getBooleanExtra("clear_scratch",false)) {
                File[] old=root.listFiles();if(old!=null)for(File dir:old)if(dir.isDirectory()&&dir.getName().matches("H[0-9a-f]+")) {
                    File jpeg=new File(dir,"ROUND.JPG");if(jpeg.isFile()&&jpeg.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile())&&!jpeg.delete())throw new IOException("cannot clear old probe JPEG");
                }
            }
            run=new File(root,"H"+Long.toHexString(System.currentTimeMillis()));if(!run.mkdir())throw new IOException("probe directory unavailable");
            run.setReadable(true,false);run.setExecutable(true,false);event("run",run.getPath());
            String uri=imageUri();event("source_uri",uri);
            String lutName=getIntent().getStringExtra("lut");File lutFile=null;
            if(lutName!=null){if(!lutName.equals("COLOR17.CUB")&&!lutName.equals("ID33.CUB"))throw new IOException("probe LUT not allowed");lutFile=Assets.extract(new File(getApplicationInfo().sourceDir),lutName,run);}
            if(lutFile!=null&&getIntent().getBooleanExtra("precompute",false)) {
                long before=SystemClock.elapsedRealtime();cache=Native.prepareDeviceLut(lutFile.getPath(),getIntent().getIntExtra("workers",4));
                event("cache_ready",new JSONObject().put("build_ms",SystemClock.elapsedRealtime()-before).put("virtual_bytes",134217728));
            }
            int repeats=getIntent().getIntExtra("repeat",1);
            if(repeats<1||repeats>3||(repeats>1&&!getIntent().getBooleanExtra("direct_save",false)))throw new IOException("invalid repeat mode");
            for(int iteration=0;iteration<repeats;iteration++) {
            long iterationStart=SystemClock.elapsedRealtime();event("iteration_start",iteration+1);
            Class<?> factory=Class.forName("com.sony.scalar.graphics.OptimizedImageFactory"),options=Class.forName("com.sony.scalar.graphics.OptimizedImageFactory$Options");
            Object opt=options.newInstance();options.getField("imageType").setInt(opt,getIntent().getIntExtra("image_type",3));options.getField("colorType").setInt(opt,1);
            long start=SystemClock.elapsedRealtime(),cpu=Debug.threadCpuTimeNanos();
            image=factory.getMethod("decodeImage",String.class,options).invoke(null,uri,opt);
            long decodeMs=SystemClock.elapsedRealtime()-start,decodeCpu=(Debug.threadCpuTimeNanos()-cpu)/1000000;
            if(image==null)throw new IOException("Sony decoder returned null");
            Class<?> imageClass=Class.forName("com.sony.scalar.graphics.OptimizedImage");
            JSONObject info=new JSONObject(Native.deviceInfo(image));
            info.put("width",imageClass.getMethod("getWidth").invoke(image));info.put("height",imageClass.getMethod("getHeight").invoke(image));
            info.put("format",imageClass.getMethod("getImageFormat").invoke(image));info.put("decode_ms",decodeMs);info.put("decode_cpu_ms",decodeCpu);event("decoded",info);
            if(getIntent().getBooleanExtra("read_sample",false)) {
                int offset=Integer.decode(info.getString("image-data-offset"));start=SystemClock.elapsedRealtime();byte[] sample=Native.deviceRead(image,offset,65536);
                event("memory_read","bytes="+sample.length+" ms="+(SystemClock.elapsedRealtime()-start));
                File sampleFile=new File(run,"SAMPLE.BIN");FileOutputStream f=new FileOutputStream(sampleFile);try{f.write(sample);}finally{f.close();}sampleFile.setReadable(true,false);
            }
            if(getIntent().getBooleanExtra("encode",true)) {
                if(lutFile!=null)event("lut",cache!=0?Native.deviceLutCached(image,cache):Native.deviceLut(image,lutFile.getPath(),getIntent().getIntExtra("workers",4)));
                Class<?> type=Class.forName("com.sony.scalar.graphics.JpegExporter"),encOptions=Class.forName("com.sony.scalar.graphics.JpegExporter$Options");
                exporter=type.newInstance();Object settings=encOptions.newInstance();encOptions.getField("quality").setInt(settings,3);
                if(getIntent().getBooleanExtra("direct_save",false)) {
                    Set<String> before=mediaUris();Class<?> media=Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
                    Uri external=(Uri)media.getField("EXTERNAL_CONTENT_URI").get(null);String id=(String)media.getMethod("getMediaId",Uri.class).invoke(null,external);
                    event("record_target",id);start=SystemClock.elapsedRealtime();
                    type.getMethod("encode",imageClass,String.class,encOptions).invoke(exporter,image,id,settings);
                    long saveMs=SystemClock.elapsedRealtime()-start;Set<String> after=mediaUris();after.removeAll(before);
                    if(after.size()!=1)throw new IOException("native save produced "+after.size()+" new media entries");
                    String savedUri=after.iterator().next();
                    event("recorded",new JSONObject().put("encode_and_save_ms",saveMs).put("uri",savedUri));
                    Object meta=media.getMethod("getImageInfo",String.class).invoke(null,savedUri);
                    event("saved_info",new JSONObject().put("dir",meta.getClass().getMethod("getAttribute",String.class).invoke(meta,"DCF_TBLDirName"))
                        .put("file",meta.getClass().getMethod("getAttribute",String.class).invoke(meta,"DCF_TBLFileName")));
                    long previewStart=SystemClock.elapsedRealtime();
                    byte[] nail=(byte[])Class.forName("com.sony.scalar.graphics.AvindexGraphics").getMethod("getScreenNail",String.class).invoke(null,savedUri);
                    long fetched=SystemClock.elapsedRealtime();BitmapFactory.Options previewOptions=new BitmapFactory.Options();
                    previewOptions.inSampleSize=2;
                    Bitmap preview=BitmapFactory.decodeByteArray(nail,0,nail.length,previewOptions);
                    if(preview==null)throw new IOException("native screennail failed to decode");
                    event("screennail",new JSONObject().put("ms",SystemClock.elapsedRealtime()-previewStart).put("fetch_ms",fetched-previewStart).put("decode_ms",SystemClock.elapsedRealtime()-fetched).put("width",preview.getWidth()).put("height",preview.getHeight()));preview.recycle();
                }else{
                start=SystemClock.elapsedRealtime();cpu=Debug.threadCpuTimeNanos();
                InputStream stream=(InputStream)type.getMethod("encode",imageClass,encOptions).invoke(exporter,image,settings);
                long encodeMs=SystemClock.elapsedRealtime()-start,encodeCpu=(Debug.threadCpuTimeNanos()-cpu)/1000000;
                if(stream==null)throw new IOException("Sony encoder returned null stream");
                File output=getIntent().getBooleanExtra("sd_output",false)?RunFiles.file(RunFiles.create(Environment.getExternalStorageDirectory()),"HWLUT.JPG"):new File(run,"ROUND.JPG");
                long count=0,copyStart=SystemClock.elapsedRealtime();
                if(getIntent().getBooleanExtra("native_copy",false)) {
                    try{count=Native.saveSonyStream(stream,output.getPath());}finally{stream.close();}
                }else{
                if(!output.createNewFile())throw new IOException("output already exists");
                try{FileOutputStream file=new FileOutputStream(output);try{
                    byte[] block=new byte[262144];int n;while((n=stream.read(block))!=-1){if(n==0)throw new IOException("encoder stream stalled");file.write(block,0,n);count+=n;if(count>24L*1024*1024)throw new IOException("encoded JPEG exceeds probe limit");}
                }finally{file.close();}}finally{stream.close();}}
                output.setReadable(true,false);
                event("encoded",new JSONObject().put("encode_ms",encodeMs).put("encode_cpu_ms",encodeCpu).put("copy_ms",SystemClock.elapsedRealtime()-copyStart).put("bytes",count).put("output",output.getPath()));
                }
            }
            for(Object obj:new Object[]{image,exporter})if(obj!=null)obj.getClass().getMethod("release").invoke(obj);
            image=null;exporter=null;
            event("iteration_done",new JSONObject().put("iteration",iteration+1).put("total_ms",SystemClock.elapsedRealtime()-iterationStart));
            }
            event("done",getIntent().hasExtra("lut")?"hardware codec + LUT complete":"codec probe complete; no LUT applied");
        }catch(Throwable e){while(e.getCause()!=null)e=e.getCause();event("error",e.toString());android.util.Log.e("SonyHwJpeg","probe",e);}
        finally{
            if(cache!=0)Native.releaseDeviceLut(cache);
            for(Object obj:new Object[]{image,exporter})if(obj!=null)try{obj.getClass().getMethod("release").invoke(obj);}catch(Throwable e){event("release_error",e.toString());}
            if(run!=null)new File(run,"PROBE.JSN").setReadable(true,false);running=false;
        }
    }
    @Override public void onBackPressed(){if(!running)super.onBackPressed();}
}
