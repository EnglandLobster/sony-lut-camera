package org.hongen.sonylutprobe;

import android.app.Activity;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.JSONObject;

/** Explicit, single-shot RAW/develop investigation; never a launcher entry. */
public final class RawCaptureProbeActivity extends Activity implements SurfaceHolder.Callback {
    private final Handler ui=new Handler();private HandlerThread thread;private Handler io;
    private SurfaceHolder holder;private TextView status;private ImageView review;
    private Object cameraEx,sequence;private Camera camera;private volatile boolean active,busy,prepared;
    private boolean opened,finished;private long started,cache;private Bitmap displayed;
    private Class<?> rawType,sequenceType,imageType,media;private Uri external;private String mediaId;
    private final StringBuilder records=new StringBuilder();private File run;
    private void event(String key,Object value){String line=key+" "+value;android.util.Log.i("SonyRawProbe",line);synchronized(records){records.append(line).append('\n');
        // This probe can cross unsupported firmware paths. Persist each stage
        // before the next API call; performance acceptance uses CaptureActivity.
        if(run!=null)try{FileWriter writer=new FileWriter(new File(run,"RAW.JSN"),true);try{writer.write(line+"\n");}finally{writer.close();}}catch(IOException e){android.util.Log.w("SonyRawProbe","journal",e);}
    }}
    @Override public void onCreate(Bundle state){super.onCreate(state);
        String token=getIntent().getStringExtra("probe_token");
        if(token==null||token.equals(getPreferences(0).getString("entered_probe",null))
                ||!getPreferences(0).edit().putString("entered_probe",token).commit()){
            TextView stopped=new TextView(this);stopped.setText("RAW 实验入口已暂停。需要新的显式测试标识。\n正常拍照请打开 LUT Camera。");setContentView(stopped);return;
        }
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);FrameLayout frame=new FrameLayout(this);
        SurfaceView surface=new SurfaceView(this);holder=surface.getHolder();holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);holder.addCallback(this);frame.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        review=new ImageView(this);review.setScaleType(ImageView.ScaleType.FIT_CENTER);review.setBackgroundColor(0xff000000);review.setVisibility(View.INVISIBLE);frame.addView(review,new FrameLayout.LayoutParams(-1,-1));
        page.addView(frame,new LinearLayout.LayoutParams(-1,0,1));status=new TextView(this);status.setTextSize(18);status.setLines(2);status.setText("RAW 拍摄接口测试准备中");page.addView(status);setContentView(page);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        thread=new HandlerThread("raw-develop-probe");thread.start();io=new Handler(thread.getLooper());
        io.post(new Runnable(){public void run(){try{
            File sd=Environment.getExternalStorageDirectory();run=RunFiles.create(sd);event("prepare","run="+run.getPath());
            RunFiles.clearAdbCopies();File lut=Assets.extract(new File(getApplicationInfo().sourceDir),"COLOR17.CUB",run);
            cache=Native.prepareDeviceLut(lut.getPath(),4);
            imageType=Class.forName("com.sony.scalar.graphics.OptimizedImage");rawType=Class.forName("com.sony.scalar.hardware.CameraSequence$RawData");
            sequenceType=Class.forName("com.sony.scalar.hardware.CameraSequence");media=Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
            external=(Uri)media.getField("EXTERNAL_CONTENT_URI").get(null);mediaId=(String)media.getMethod("getMediaId",Uri.class).invoke(null,external);
            prepared=true;ui.post(new Runnable(){public void run(){open();}});
        }catch(Throwable e){failure(e);}}});
    }
    private Object listener(String name,final InvocationHandler handler)throws Exception{Class<?> type=Class.forName(name);return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    private void open(){if(!active||!prepared||opened||holder.getSurface()==null||!holder.getSurface().isValid())return;
        try {
            Class<?> type=Class.forName("com.sony.scalar.hardware.CameraEx");event("camera_open_start","begin");cameraEx=type.getMethod("open",int.class,Class.forName("com.sony.scalar.hardware.CameraEx$OpenOptions")).invoke(null,0,null);
            camera=(Camera)type.getMethod("getNormalCamera").invoke(cameraEx);event("sequence_open_start","begin");sequence=sequenceType.getMethod("open",type).invoke(null,cameraEx);
            Class<?> callback=Class.forName("com.sony.scalar.hardware.CameraSequence$ShutterSequenceCallback");
            Object listener=listener(callback.getName(),new InvocationHandler(){public Object invoke(Object proxy,Method method,Object[] args)throws Throwable{
                if(method.getDeclaringClass()==Object.class){if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);if(method.getName().equals("equals"))return proxy==args[0];return "RawCaptureProbe";}
                if(method.getName().equals("onShutterSequence")){
                    final Object raw=args[0];if(finished){sequenceType.getMethod("storeImage",rawType,boolean.class).invoke(sequence,raw,true);return null;}
                    if(started==0)started=SystemClock.elapsedRealtime();busy=true;finished=true;
                    event("raw_ready",new JSONObject().put("ms",SystemClock.elapsedRealtime()-started).put("class",raw.getClass().getName()));
                    io.post(new Runnable(){public void run(){develop(raw);}});
                }return null;
            }});
            event("register_raw_start","begin");sequenceType.getMethod("setShutterSequenceCallback",callback).invoke(sequence,listener);event("register_raw_done","ok");
            camera.setPreviewDisplay(holder);camera.startPreview();type.getMethod("startDirectShutter").invoke(cameraEx);opened=true;
            event("ready","raw callback registered");status.setText("RAW 接口测试：单次拍摄\n完成后保存原片及 LUT 成片");
            final String token=getIntent().getStringExtra("probe_token");
            if(getIntent().getBooleanExtra("auto_capture",false)&&token!=null&&!token.equals(getPreferences(0).getString("consumed_probe",null)))ui.postDelayed(new Runnable(){public void run(){try{
                if(!active||busy||finished)return;started=SystemClock.elapsedRealtime();busy=true;event("trigger","CameraEx.burstableTakePicture");
                if(!getPreferences(0).edit().putString("consumed_probe",token).commit())throw new IOException("cannot persist one-shot token");
                cameraEx.getClass().getMethod("burstableTakePicture").invoke(cameraEx);
                ui.postDelayed(new Runnable(){public void run(){if(busy&&!finished){
                    try{cameraEx.getClass().getMethod("cancelTakePicture").invoke(cameraEx);}catch(Throwable e){event("cancel_error",root(e));}
                    busy=false;failure(new IOException("no RAW callback within 15 seconds"));
                }}},15000);
            }catch(Throwable e){busy=false;failure(e);}}},500);
        }catch(Throwable e){failure(e);close();}
    }
    private Set<String> images()throws Exception{Cursor c=getContentResolver().query(external,new String[]{"_data"},null,null,null);if(c==null)throw new IOException("no image index");
        Set<String> out=new HashSet<String>();try{while(c.moveToNext())out.add(c.getString(0));}finally{c.close();}return out;}
    private String save(Object image,String label)throws Exception{
        Set<String> before=images();Class<?> type=Class.forName("com.sony.scalar.graphics.JpegExporter"),options=Class.forName("com.sony.scalar.graphics.JpegExporter$Options");
        Object exporter=type.newInstance(),settings=options.newInstance();options.getField("quality").setInt(settings,3);long t=SystemClock.elapsedRealtime();
        try{type.getMethod("encode",imageType,String.class,options).invoke(exporter,image,mediaId,settings);}finally{type.getMethod("release").invoke(exporter);}
        long encodeMs=SystemClock.elapsedRealtime()-t;Set<String> after=images();after.removeAll(before);if(after.size()!=1)throw new IOException("save produced "+after.size()+" new entries");
        String uri=after.iterator().next();Object meta=media.getMethod("getImageInfo",String.class).invoke(null,uri);
        try{Method attr=meta.getClass().getMethod("getAttribute",String.class);
            event(label,new JSONObject().put("encode_save_ms",encodeMs).put("uri",uri).put("dir",attr.invoke(meta,"DCF_TBLDirName")).put("file",attr.invoke(meta,"DCF_TBLFileName")));
        }finally{meta.getClass().getMethod("recycle").invoke(meta);}return uri;
    }
    private void develop(Object raw){Object filter=null,image=null;Bitmap bitmap=null;boolean originalSaved=false,transferred=false;
        try {
            Class<?> type=Class.forName("com.sony.scalar.hardware.CameraSequence$DefaultDevelopFilter");event("develop_create_start","begin");filter=type.newInstance();
            if(!((Boolean)type.getMethod("isSupported").invoke(filter)))throw new IOException("DEFAULT_DEVELOP unsupported");
            type.getMethod("setSource",rawType,boolean.class).invoke(filter,raw,false);long t=SystemClock.elapsedRealtime();
            event("develop_execute_start","begin");if(!((Boolean)type.getMethod("execute").invoke(filter)))throw new IOException("RAW develop failed");
            image=type.getMethod("getOutput").invoke(filter);if(image==null)throw new IOException("RAW develop returned no image");
            event("developed",new JSONObject(Native.deviceInfo(image)).put("develop_ms",SystemClock.elapsedRealtime()-t).put("format",imageType.getMethod("getImageFormat").invoke(image)));
            // Finish the unmodified original before mutating its image buffer.
            save(image,"original_saved");originalSaved=true;
            int w=((Integer)imageType.getMethod("getWidth").invoke(image)).intValue(),h=((Integer)imageType.getMethod("getHeight").invoke(image)).intValue();
            if(((Integer)imageType.getMethod("getImageFormat").invoke(image)).intValue()!=0)throw new IOException("unexpected developed pixel format");
            int scale=Math.max(1,(Math.max(w,h)+799)/800);bitmap=Bitmap.createBitmap(w/scale,h/scale,Bitmap.Config.ARGB_8888);
            event("lut",Native.deviceLutReview(image,cache,bitmap));save(image,"graded_saved");
            final long elapsed=SystemClock.elapsedRealtime()-started;event("done",new JSONObject().put("shutter_to_saved_ms",elapsed));
            final Bitmap result=bitmap;bitmap=null;ui.post(new Runnable(){public void run(){
                if(!active){result.recycle();return;}if(camera!=null)camera.stopPreview();displayed=result;review.setImageBitmap(result);review.setVisibility(View.VISIBLE);status.setText("RAW 测试完成："+(elapsed/1000.0)+" 秒\n原片与 LUT 成片均已保存");
            }});
        }catch(Throwable e){
            if(!originalSaved)try{sequenceType.getMethod("storeImage",rawType,boolean.class).invoke(sequence,raw,true);transferred=true;event("original_fallback","raw handed back to Sony storeImage");}catch(Throwable saveError){event("original_save_error",root(saveError));}
            failure(e);
        }finally{
            if(bitmap!=null)bitmap.recycle();for(Object value:new Object[]{image,filter,transferred?null:raw})if(value!=null)try{value.getClass().getMethod("release").invoke(value);}catch(Throwable e){event("release_error",root(e));}
            busy=false;try{FileWriter writer=new FileWriter(RunFiles.file(run,"RAW.JSN"));try{synchronized(records){writer.write(records.toString());}}finally{writer.close();}}catch(Exception e){event("log_error",e);}
            if(!active)ui.post(new Runnable(){public void run(){close();}});
        }
    }
    private static Throwable root(Throwable e){while(e.getCause()!=null)e=e.getCause();return e;}
    private void failure(Throwable e){final String message=root(e).toString();event("error",message);android.util.Log.e("SonyRawProbe","probe failed",e);ui.post(new Runnable(){public void run(){status.setText("RAW 接口测试失败\n"+message);}});}
    private void close(){for(Object value:new Object[]{sequence,cameraEx})if(value!=null)try{value.getClass().getMethod("release").invoke(value);}catch(Throwable e){event("close_error",root(e));}sequence=null;cameraEx=null;camera=null;opened=false;}
    @Override protected void onResume(){super.onResume();active=true;open();}
    @Override protected void onPause(){active=false;if(!busy)close();super.onPause();}
    @Override protected void onDestroy(){if(io!=null)io.post(new Runnable(){public void run(){if(cache!=0){Native.releaseDeviceLut(cache);cache=0;}thread.quit();}});if(displayed!=null)displayed.recycle();super.onDestroy();}
    @Override public void onBackPressed(){if(!busy)finish();}
    public void surfaceCreated(SurfaceHolder h){open();}public void surfaceChanged(SurfaceHolder h,int f,int w,int height){open();}public void surfaceDestroyed(SurfaceHolder h){if(!busy)close();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){int key=event.getKeyCode(),scan=event.getScanCode();
        if(key==KeyEvent.KEYCODE_BACK||key==KeyEvent.KEYCODE_MENU||scan==514){if(event.getAction()==KeyEvent.ACTION_UP&&!busy)finish();return true;}
        boolean shutter=scan==516||scan==517||scan==518||key==516||key==517||key==518||key==KeyEvent.KEYCODE_CAMERA;
        if(shutter&&(busy||finished))return true;
        if(event.getAction()==KeyEvent.ACTION_DOWN&&(scan==518||key==518||key==KeyEvent.KEYCODE_CAMERA)&&!busy){started=SystemClock.elapsedRealtime();}
        return super.dispatchKeyEvent(event);
    }
}
