package org.hongen.sonylutprobe;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/** Bounded camera test bench. Tests its bundled JPEG and low-res preview; no shutter or persistent camera writes. */
public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private final Handler ui=new Handler();
    private volatile boolean active=false,stop=true,busy=false;
    private volatile Object sequence;
    private volatile Thread worker;
    private TextView status;
    private SurfaceHolder holder;
    private Frame view;
    private LinearLayout controls;
    private String lut="ID33.CUB";
    private int workers=1;
    private final Object logs=new Object();
    private File wiredRun;
    private String wiredSummary="";
    private Bitmap retainedFrame;
    private interface Completion {void run(boolean ok);}
    private static final class RenderFrame {
        final Bitmap bitmap;final int generation;
        RenderFrame(Bitmap bitmap,int generation){this.bitmap=bitmap;this.generation=generation;}
    }
    private class Frame extends View {
        volatile RenderFrame frame;
        volatile int drawn;
        int lastGeneration=-1;
        boolean reportNext;
        final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        Frame(){super(MainActivity.this);setBackgroundColor(Color.TRANSPARENT);}
        protected void onDraw(Canvas c) {
            RenderFrame current=frame;if(current==null)return;
            synchronized(current.bitmap) {
            if(frame!=current)return; // A newer complete frame superseded this one.
            Bitmap bitmap=current.bitmap;
            if(reportNext){reportNext=false;log("{\"kind\":\"frame_draw\",\"view_width\":"+getWidth()+",\"view_height\":"+getHeight()+",\"canvas_hardware\":"+c.isHardwareAccelerated()+"}");}
            c.drawColor(Color.BLACK);
            float scale=Math.min((float)getWidth()/bitmap.getWidth(),(float)getHeight()/bitmap.getHeight());
            float w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;
            c.drawBitmap(bitmap,null,new RectF((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2),paint);
            if(lastGeneration!=current.generation){lastGeneration=current.generation;++drawn;}
            }
        }
    }
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        clearLegacyFixtures();
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        FrameLayout preview=new FrameLayout(this);SurfaceView surface=new SurfaceView(this);
        holder=surface.getHolder();holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);holder.addCallback(this);
        // Sony's full-size native preview plane occludes Android bitmap content.
        // Keep the producer surface alive in a small corner; render LUT frames above.
        int nativeSize=getIntent().getIntExtra("native_surface_size",16);
        if(nativeSize>0 && nativeSize<=64)preview.addView(surface,new FrameLayout.LayoutParams(nativeSize,nativeSize));
        else preview.addView(surface);
        view=new Frame();preview.addView(view);
        page.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout buttons=new LinearLayout(this);controls=buttons;
        button(buttons,"USB Test",new Runnable(){public void run(){wiredTest();}});
        button(buttons,"Probe",new Runnable(){public void run(){probe();}});
        button(buttons,"LUT",new Runnable(){public void run(){if(!busy){lut=lut.equals("ID33.CUB")?"COLOR17.CUB":"ID33.CUB";say(lut);}}});
        button(buttons,"1/2/4",new Runnable(){public void run(){if(!busy){workers=workers==1?2:workers==2?4:1;say("workers="+workers);}}});
        button(buttons,"JPEG test",new Runnable(){public void run(){fileTest();}});
        button(buttons,"View 10s",new Runnable(){public void run(){if(busy)halt();else preview();}});
        page.addView(buttons);status=new TextView(this);status.setTextSize(12);status.setText("USB Test: JPEG + live LUT, results to SD/SONYLUT | MENU exits");page.addView(status);
        setContentView(page);
        buttons.getChildAt(0).requestFocus();
    }
    private void button(LinearLayout parent,String text,final Runnable action) {
        Button b=new Button(this);b.setText(text);b.setTextSize(11);b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){action.run();}});
        parent.addView(b,new LinearLayout.LayoutParams(0,-2,1));
    }
    protected void onResume(){super.onResume();active=true;getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    protected void onPause(){active=false;halt();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);super.onPause();}
    public boolean onKeyUp(int code,KeyEvent e) {if(e.getScanCode()==514 || code==KeyEvent.KEYCODE_BACK){finish();return true;}return super.onKeyUp(code,e);}
    public boolean dispatchKeyEvent(KeyEvent e) {
        int sc=e.getScanCode(),code=e.getKeyCode();
        if(sc==514 || code==KeyEvent.KEYCODE_BACK) {
            if(e.getAction()==KeyEvent.ACTION_UP)finish();return true;
        }
        // Sony's centre key uses scan 232, rather than Android's normal DPAD mapping.
        if(sc==232 || code==KeyEvent.KEYCODE_DPAD_CENTER || code==KeyEvent.KEYCODE_ENTER) {
            if(e.getAction()==KeyEvent.ACTION_UP && controls!=null) {
                View selected=getCurrentFocus();if(!(selected instanceof Button))selected=controls.getChildAt(0);
                selected.performClick();
            }
            return true;
        }
        int dir=(sc==105 || sc==103)?-1:(sc==106 || sc==108)?1:0;
        if(dir!=0 && controls!=null) {
            if(e.getAction()==KeyEvent.ACTION_DOWN) {
                int index=controls.indexOfChild(getCurrentFocus());if(index<0)index=0;
                controls.getChildAt((index+dir+controls.getChildCount())%controls.getChildCount()).requestFocus();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
    public void surfaceCreated(SurfaceHolder h){}
    public void surfaceChanged(SurfaceHolder h,int f,int w,int v){}
    public void surfaceDestroyed(SurfaceHolder h){halt();}
    private void say(final String s){ui.post(new Runnable(){public void run(){if(active)status.setText(s);}});}
    private void log(String s) {
        android.util.Log.i("SonyLutProbe",s);
        synchronized(logs){try{FileWriter f=new FileWriter(new File(workDir(),"RESULTS.JSN"),true);f.write(s+"\n");f.close();}catch(Exception e){say("log failed: "+e);}}
    }
    private void flushFrames(java.util.List<String> records) throws IOException {
        if(records.isEmpty())return;
        synchronized(logs) {
            FileWriter file=new FileWriter(new File(workDir(),"RESULTS.JSN"),true);
            try {for(String record:records){file.write(record);file.write('\n');}}
            finally {file.close();}
        }
        records.clear();
    }
    private File workDir() throws IOException {
        if(!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()))throw new IOException("SD card not mounted writable");
        File root=RunFiles.file(Environment.getExternalStorageDirectory(),"SONYLUT");
        File work=RunFiles.file(root,"WORK");
        if(!work.isDirectory() && !work.mkdirs())throw new IOException("cannot create SD work directory");
        return work;
    }
    private void clearLegacyFixtures() {
        File dir=getFilesDir();
        for(String name:new String[]{"TEST24.JPG","ID33.CUB","COLOR17.CUB","PROBE.TXT","PARAMS.TXT","RESULTS.JSONL"}) {
            File file=new File(dir,name);
            if(file.exists())android.util.Log.i("SonyLutProbe","legacy cache cleanup "+name+" bytes="+file.length()+" deleted="+file.delete());
        }
        File[] staged=dir.listFiles();
        if(staged!=null)for(File file:staged)if(file.getName().startsWith("asset-")&&file.getName().endsWith(".tmp"))file.delete();
    }
    private void failure(String stage,Throwable error) {
        Throwable root=error;while(root.getCause()!=null && root.getCause()!=root)root=root.getCause();
        StringWriter trace=new StringWriter();error.printStackTrace(new PrintWriter(trace));
        say(stage+" FAIL: "+root);
        log("{\"kind\":\"error\",\"stage\":"+org.json.JSONObject.quote(stage)+",\"message\":"+
            org.json.JSONObject.quote(String.valueOf(root))+",\"stack\":"+org.json.JSONObject.quote(trace.toString())+"}");
    }
    private File asset(String name) throws IOException {
        File dst=Assets.extract(new File(getApplicationInfo().sourceDir),name,workDir());
        log("{\"kind\":\"asset_verified\",\"name\":"+org.json.JSONObject.quote(name)+",\"bytes\":"+dst.length()+"}");
        return dst;
    }
    private void exportRun(String phase) throws IOException {
        if(wiredRun==null)return;
        synchronized(logs) {
            for(String name:new String[]{"PROBE.TXT","PARAMS.TXT","RESULTS.JSN"}) {
                File source=new File(workDir(),name);
                if(source.isFile())RunFiles.copy(source,RunFiles.file(wiredRun,name));
            }
            RunFiles.state(wiredRun,phase+"\n"+wiredSummary);
        }
    }
    private void finishWired(boolean ok) {
        final boolean passed=ok;busy=true;
        worker=new Thread(new Runnable(){public void run(){
            try {
                exportRun(passed?"DONE":"FAILED_OR_STOPPED");
                if(getIntent().getBooleanExtra("adb_export",false)) {
                    try {exportAdbRun(wiredRun);}catch(Throwable e){failure("adb_export",e);}
                }
                say((passed?"USB DONE: ":"USB FAILED/STOPPED: ")+wiredSummary+" | results: SD/SONYLUT");
            }catch(Throwable e){failure("sd_export",e);}
            finally {wiredRun=null;busy=false;}
        }},"test-export");worker.start();
    }
    // Opt-in diagnostic copy, after all timing. Sony denies shell access to SD;
    // this existing 32MiB RAM mount lets adb pull just this app's run without root.
    private void exportAdbRun(File run) throws IOException {
        File root=new File("/mnt/wv_cache/SONYLUT");
        if(!root.isDirectory() && !root.mkdir())throw new IOException("ADB export RAM directory unavailable");
        root.setReadable(true,false);root.setExecutable(true,false);
        File[] files=run.listFiles();if(files==null)throw new IOException("cannot list test run");
        long bytes=0;for(File file:files)if(file.isFile())bytes+=file.length();
        if(bytes>24L*1024*1024 || root.getUsableSpace()<bytes+1024*1024)throw new IOException("insufficient bounded RAM export space");
        File out=new File(root,run.getName());if(!out.mkdir())throw new IOException("ADB export run already exists");
        out.setReadable(true,false);out.setExecutable(true,false);
        for(File file:files)if(file.isFile()) {
            File copy=RunFiles.file(out,file.getName());RunFiles.copy(file,copy);copy.setReadable(true,false);
        }
        log("{\"kind\":\"adb_export\",\"directory\":"+org.json.JSONObject.quote(out.getPath())+",\"bytes\":"+bytes+"}");
    }
    private void wiredTest() {
        if(busy)return;
        try {
            if(!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()))throw new IOException("SD card not mounted writable");
            wiredRun=RunFiles.create(Environment.getExternalStorageDirectory());wiredSummary="";
            RunFiles.state(wiredRun,"RUNNING");
        }catch(Throwable e){wiredRun=null;failure("sd_start",e);return;}
        busy=true;stop=false;
        worker=new Thread(new Runnable(){public void run(){long engine=0;boolean passed=false;
            try {
                log("{\"kind\":\"usb_start\",\"directory\":"+org.json.JSONObject.quote(wiredRun.getPath())+"}");
                writeProbe();exportRun("PROBE_DONE");
                long init=System.currentTimeMillis();engine=Native.create(asset("COLOR17.CUB").getPath(),1);
                log("{\"kind\":\"engine_init\",\"elapsed_ms\":"+(System.currentTimeMillis()-init)+"}");
                File input=asset("TEST24.JPG");
                for(int scale:new int[]{2,1})for(int mode=0;mode<2;++mode) {
                    if(!active || stop)throw new InterruptedException("USB test stopped");
                    boolean apply=mode==1;String name=(scale==1?"J24":"J6")+(apply?"L":"C")+".JPG";
                    say("USB "+(apply?"LUT":"codec")+" "+(scale==1?"24MP":"6MP")+" ...");
                    String result=Native.file(engine,input.getPath(),RunFiles.file(wiredRun,name).getPath(),scale,apply);
                    log("{\"kind\":\"jpeg\",\"lut\":\"COLOR17.CUB\",\"apply\":"+apply+",\"output\":\""+name+"\",\"stats\":"+result+"}");
                    if(scale==1 && apply)wiredSummary="24MP LUT "+Math.round(new org.json.JSONObject(result).getDouble("total_ms"))+" ms";
                    exportRun("JPEG_RUNNING");
                }
                passed=true;exportRun("JPEG_DONE");
            }catch(Throwable e){failure("usb_jpeg",e);}
            finally {
                if(engine!=0)Native.destroy(engine);final boolean ready=passed;
                ui.post(new Runnable(){public void run(){busy=false;
                    if(!ready || !active || stop){finishWired(false);return;}
                    say("USB identity preview 10s ...");
                    preview("ID33.CUB",1,new Completion(){public void run(boolean ok){
                        if(!ok){finishWired(false);return;}
                        say("USB colour LUT preview 10s ...");
                        preview("COLOR17.CUB",1,new Completion(){public void run(boolean success){finishWired(success);}});
                    }});
                }});
            }
        }},"usb-test");worker.start();
    }
    private void fileTest() {
        if(busy)return;busy=true;stop=false;
        final String selected=lut;final int count=workers;
        worker=new Thread(new Runnable(){public void run(){long engine=0;
            try {
                long init=System.currentTimeMillis();engine=Native.create(asset(selected).getPath(),count);
                log("{\"kind\":\"engine_init\",\"elapsed_ms\":"+(System.currentTimeMillis()-init)+"}");
                File input=asset("TEST24.JPG");
                File outputDir=RunFiles.create(Environment.getExternalStorageDirectory());
                for(int scale:new int[]{2,1})for(int mode=0;mode<2;++mode) {
                    if(!active || stop)return;
                    boolean apply=mode==1;String name=(scale==1?"J24":"J6")+(apply?"L":"C")+".JPG";
                    say((apply?"LUT":"codec")+" scale="+scale+" threads="+count);
                    String s=Native.file(engine,input.getPath(),RunFiles.file(outputDir,name).getPath(),scale,apply);
                    log("{\"kind\":\"jpeg\",\"lut\":\""+selected+"\",\"apply\":"+apply+",\"stats\":"+s+"}");say(s);
                }
            }catch(Throwable e){failure("jpeg",e);}
            finally{if(engine!=0)Native.destroy(engine);busy=false;}
        }},"jpeg-test");worker.start();
    }
    private void probe() {
        if(busy)return;busy=true;
        worker=new Thread(new Runnable(){public void run(){try {
            writeProbe();say("Probe saved");
        }catch(Throwable e){failure("probe",e);}finally{busy=false;}}},"probe");worker.start();
    }
    private void writeProbe() throws Exception {
            File file=new File(workDir(),"PROBE.TXT");PrintWriter out=new PrintWriter(new FileWriter(file));
            try {
                out.println("package="+getPackageName()+" version="+getPackageManager().getPackageInfo(getPackageName(),0).versionName);
                out.println("abi="+android.os.Build.CPU_ABI+" sdk="+android.os.Build.VERSION.SDK_INT);
                out.println("cores="+Runtime.getRuntime().availableProcessors());
                for(String p:new String[]{"/proc/cpuinfo","/proc/meminfo","/proc/self/status","/sys/devices/system/cpu/online"}) {
                    out.println(p);try{BufferedReader r=new BufferedReader(new FileReader(p));String s;while((s=r.readLine())!=null)out.println(s);r.close();}catch(Exception e){out.println(e);}
                }
                for(String name:new String[]{"com.sony.scalar.hardware.CameraEx","com.sony.scalar.hardware.CameraEx$ParametersModifier","com.sony.scalar.hardware.CameraSequence","com.sony.scalar.hardware.DeviceBuffer","com.sony.scalar.hardware.DSP","com.sony.scalar.graphics.JpegExporter"}) {
                    out.println(name);try{for(Method m:Class.forName(name).getMethods())out.println(m.toString());}catch(Throwable t){out.println(t);}
                }
            }finally{out.close();}
    }
    private void halt() {
        stop=true;Object seq=sequence;
        // Stop the producer before the frame reader releases camera resources.
        final Object pending=seq;
        if(pending!=null)new Thread(new Runnable(){public void run(){synchronized(pending){
            if(sequence!=pending)return;
            try{pending.getClass().getMethod("stopPreviewSequence").invoke(pending);}
            catch(Throwable e){say("Stop FAIL: "+e);}
        }}},"preview-stop").start();
        Thread t=worker;if(t!=null)t.interrupt();
    }
    private void preview() {
        if(getIntent().getBooleanExtra("capture_preview",false)) {
            if(busy)return;
            try {wiredRun=RunFiles.create(Environment.getExternalStorageDirectory());wiredSummary="preview only";}
            catch(Throwable e){failure("sd_start",e);return;}
            log("{\"kind\":\"usb_start\",\"directory\":"+org.json.JSONObject.quote(wiredRun.getPath())+"}");
            preview(lut,workers,new Completion(){public void run(boolean ok){finishWired(ok);}});
        }else preview(lut,workers,null);
    }
    private void preview(final String selected,final int count,final Completion done) {
        if(busy || holder.getSurface()==null || !holder.getSurface().isValid()) {
            if(done!=null)done.run(false);return;
        }
        if(retainedFrame!=null){view.frame=null;retainedFrame.recycle();retainedFrame=null;}
        view.reportNext=true;view.drawn=0;view.lastGeneration=-1;
        final int width=getIntent().getIntExtra("preview_width",640);
        final int height=width==320?240:480;
        if(width!=320 && width!=640){failure("preview",new IOException("preview width must be 320 or 640"));if(done!=null)done.run(false);return;}
        busy=true;stop=false;
        worker=new Thread(new Runnable(){public void run(){long engine=0;Object cx=null,seq=null;Camera cam=null;
            final Bitmap[] images=new Bitmap[2];int used=0,frames=0;long start=System.currentTimeMillis();
            java.util.List<String> frameRecords=new java.util.ArrayList<String>();Runnable watchdog=null;
            boolean passed=false;final File run=wiredRun;
            try {
                long init=System.currentTimeMillis();engine=Native.create(asset(selected).getPath(),count);
                log("{\"kind\":\"engine_init\",\"elapsed_ms\":"+(System.currentTimeMillis()-init)+"}");
                Class<?> c=Class.forName("com.sony.scalar.hardware.CameraEx");
                cx=c.getMethod("open",int.class,Class.forName("com.sony.scalar.hardware.CameraEx$OpenOptions")).invoke(null,0,null);
                cam=(Camera)c.getMethod("getNormalCamera").invoke(cx);
                log("{\"kind\":\"preview_start\",\"lut\":\""+selected+"\",\"workers\":"+count+"}");
                // Save complete runtime params for tomorrow's colour/input audit.
                FileWriter params=new FileWriter(new File(workDir(),"PARAMS.TXT"));params.write(cam.getParameters().flatten());params.close();
                cam.setPreviewDisplay(holder);cam.startPreview();
                Class<?> s=Class.forName("com.sony.scalar.hardware.CameraSequence"),o=Class.forName("com.sony.scalar.hardware.CameraSequence$Options");
                seq=s.getMethod("open",c).invoke(null,cx);sequence=seq;Object options=o.newInstance();Method set=o.getMethod("setOption",String.class,int.class);
                set.invoke(options,"PREVIEW_FRAME_WIDTH",width);set.invoke(options,"PREVIEW_FRAME_HEIGHT",height);
                set.invoke(options,"PREVIEW_FRAME_RATE",30000);set.invoke(options,"PREVIEW_FRAME_FORMAT",256);
                set.invoke(options,"PREVIEW_FRAME_MAX_NUM",1);set.invoke(options,"JPEG_COMPRESS_MAX_SIZE",256*1024);
                s.getMethod("startPreviewSequence",o).invoke(seq,options);
                final Thread previewThread=Thread.currentThread();
                watchdog=new Runnable(){public void run(){if(worker==previewThread&&busy)halt();}};
                ui.postDelayed(watchdog,12000);
                ByteBuffer bytes=ByteBuffer.allocateDirect(2*1024*1024);
                images[0]=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);images[1]=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                start=System.currentTimeMillis();int lastLength=0;
                while(active && !stop && System.currentTimeMillis()-start<10000) {
                    long frameStart=System.currentTimeMillis();Object[] memory=(Object[])s.getMethod("getPreviewSequenceFrames",int.class).invoke(seq,1);
                    if(memory==null || memory.length==0)throw new IOException("no preview buffer");
                    int length;
                    try {
                        Object buffer=memory[0];length=((Integer)buffer.getClass().getMethod("getSize").invoke(buffer)).intValue();
                        if(length<=0 || length>bytes.capacity())throw new IOException("preview size="+length);
                        bytes.clear();int r=((Integer)buffer.getClass().getMethod("read",ByteBuffer.class,int.class,int.class).invoke(buffer,bytes,length,0)).intValue();
                        if(r<0)throw new IOException("DeviceBuffer.read="+r);
                    }finally{for(Object m:memory)if(m!=null)m.getClass().getMethod("release").invoke(m);}
                        long acquired=System.currentTimeMillis();
                        final Bitmap next=images[used];String result;
                        // Writer and renderer lock each bitmap separately. Rendering
                        // one frame can overlap processing the other without tearing.
                        synchronized(next){result=Native.frame(engine,bytes,length,next);}
                        long processed=System.currentTimeMillis();
                        view.frame=new RenderFrame(next,frames);view.postInvalidate();
                        used=1-used;++frames;lastLength=length;
                    long submitted=System.currentTimeMillis();
                    String record="{\"kind\":\"preview_frame\",\"acquire_ms\":"+(acquired-frameStart)+",\"ui_submit_ms\":"+(submitted-processed)+",\"frame_ms\":"+(submitted-frameStart)+",\"stats\":"+result+"}";
                    frameRecords.add(record);android.util.Log.i("SonyLutProbe",record);
                    long wait=100-(System.currentTimeMillis()-frameStart);if(wait>0)Thread.sleep(wait);
                }
                long elapsed=System.currentTimeMillis()-start;
                ui.removeCallbacks(watchdog);
                flushFrames(frameRecords);
                log("{\"kind\":\"preview_end\",\"frames\":"+frames+",\"drawn_frames\":"+view.drawn+",\"elapsed_ms\":"+elapsed+"}");say("Preview processed="+frames+" drawn="+view.drawn+" / "+elapsed+" ms");
                passed=active && !stop && frames>0;
                // Save the last raw JPEG and its processed bitmap after timing ends.
                if(passed && run!=null) {
                    String label=selected.equals("ID33.CUB")?"ID":"LUT";
                    RunFiles.bytes(bytes,lastLength,RunFiles.file(run,"RAW_"+label+".JPG"));
                    FileOutputStream image=new FileOutputStream(RunFiles.file(run,"LIVE_"+label+".PNG"));
                    try {if(!images[1-used].compress(Bitmap.CompressFormat.PNG,100,image))throw new IOException("preview PNG export failed");image.flush();}
                    finally {image.close();}
                    RunFiles.copy(new File(workDir(),"PARAMS.TXT"),RunFiles.file(run,"PAR_"+label+".TXT"));
                    exportRun("PREVIEW_"+label+"_DONE");
                }
            }catch(Throwable e){
                passed=false;
                if(stop || !active)log("{\"kind\":\"preview_stopped\"}");
                else failure("preview",e);
            }
            finally {
                if(watchdog!=null)ui.removeCallbacks(watchdog);
                try {flushFrames(frameRecords);}catch(IOException e){android.util.Log.e("SonyLutProbe","frame log flush failed",e);}
                sequence=null;
                if(seq!=null)synchronized(seq){try{seq.getClass().getMethod("stopPreviewSequence").invoke(seq);}catch(Throwable ignored){}try{seq.getClass().getMethod("release").invoke(seq);}catch(Throwable ignored){}}
                if(cam!=null)try{cam.stopPreview();}catch(Throwable ignored){}
                if(cx!=null)try{cx.getClass().getMethod("release").invoke(cx);}catch(Throwable ignored){}
                if(engine!=0)Native.destroy(engine);
                final boolean complete=passed;
                final Bitmap held=passed && getIntent().getBooleanExtra("hold_last_frame",false)?images[1-used]:null;
                ui.post(new Runnable(){public void run(){view.frame=held==null?null:new RenderFrame(held,Integer.MAX_VALUE);retainedFrame=held;view.invalidate();for(Bitmap b:images)if(b!=null&&b!=held)b.recycle();busy=false;if(done!=null)done.run(complete);}});
            }
        }},"lut-preview");worker.start();
    }
}
