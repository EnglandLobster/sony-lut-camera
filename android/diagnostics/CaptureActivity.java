package org.hongen.sonylutprobe;

import android.app.Activity;
import android.graphics.*;
import android.hardware.Camera;
import android.media.ExifInterface;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import org.json.JSONObject;

/** Native Sony viewfinder/shutter, followed by an independent full-resolution LUT JPEG. */
public final class CaptureActivity extends Activity implements SurfaceHolder.Callback {
    private final Handler ui=new Handler();
    private HandlerThread ioThread;private Handler io;
    private volatile boolean active,destroyed,prepared,busy,processing;
    private boolean reviewing,retryPending,recoverRequested,pipeline,resuming;
    private volatile boolean awaitStored;private boolean storeListenerAvailable,previewListenerAvailable;
    private SurfaceHolder holder;private ReviewView review;private TextView status;private Button reviewButton;
    private Object cameraEx;private Camera camera;private String oldDrive,oldStorage;private boolean changedDrive,changedStorage;
    private File sd,work,lutFile,lastOutput,lastSource;private PhotoFiles photos;
    private Bitmap displayed;private long engine,shutterAt;private HardwareJpeg hardware;private String lastOutputUri;
    private long cameraOpenAt,resumeAt;private long lastFocusStartedAt,lastFocusDoneAt;
    private final StringBuilder pendingLog=new StringBuilder();
    private interface ReviewDrawn {void rendered(long at);}
    private final class ReviewView extends ImageView {
        ReviewDrawn nextDraw;Runnable afterDraw;
        ReviewView(){super(CaptureActivity.this);}
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if(getDrawable()!=null&&nextDraw!=null) {
                ReviewDrawn callback=nextDraw;nextDraw=null;
                callback.rendered(SystemClock.elapsedRealtime());
            }
            if(afterDraw!=null){Runnable callback=afterDraw;afterDraw=null;ui.post(callback);}
        }
    }
    private final Runnable resumeTimeout=new Runnable(){public void run(){
        if(resuming)fail("resume_preview",new IOException("Sony preview did not report ready"));
    }};
    private final Runnable resumeOpen=new Runnable(){public void run(){
        if(!resuming||!active||destroyed)return;
        log("resume_feedback_drawn","ms="+(SystemClock.elapsedRealtime()-resumeAt));
        try {
            if(camera!=null)camera.startPreview();else openCamera();
            if(!resuming)return;
            if(cameraEx==null)throw new IOException("camera unavailable while resuming");
            if(previewListenerAvailable)ui.postDelayed(resumeTimeout,3000);
            else finishResume("preview_command_returned");
        }catch(Throwable e){fail("resume_preview",e);}
    }};
    private final Runnable tick=new Runnable(){public void run(){if(busy&&!destroyed){
        status.setText((processing?"正在生成带 LUT 照片":"等待相机保存原片")+" · "+((SystemClock.elapsedRealtime()-shutterAt)/1000)+" 秒");
        ui.postDelayed(this,1000);
    }}};
    private final Runnable poll=new Runnable(){public void run(){
        if(destroyed || !busy || processing)return;
        try {
            File source=photos.pollReady(awaitStored&&SystemClock.elapsedRealtime()-shutterAt<5000);
            if(source!=null){photos.accepted(source);processPhoto(source);return;}
            if(SystemClock.elapsedRealtime()-shutterAt>120000)throw new IOException("no complete new JPEG within 120 seconds");
            io.postDelayed(this,250);
        }catch(Throwable e){fail("capture_file",e);}
    }};
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        FrameLayout frame=new FrameLayout(this);SurfaceView surface=new SurfaceView(this);
        holder=surface.getHolder();holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);holder.addCallback(this);
        frame.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        review=new ReviewView();review.setScaleType(ImageView.ScaleType.FIT_CENTER);review.setBackgroundColor(Color.BLACK);review.setVisibility(View.INVISIBLE);
        frame.addView(review,new FrameLayout.LayoutParams(-1,-1));page.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        status=new TextView(this);status.setTextSize(17);status.setLines(2);status.setText("正在准备拍照…");page.addView(status);
        LinearLayout buttons=new LinearLayout(this);reviewButton=new Button(this);reviewButton.setText("查看成片");
        reviewButton.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            if(busy||resuming)return;if(reviewing){resumeViewfinder();return;}
            if(retryPending){retry();return;}
            if(lastOutput!=null)loadReview(lastOutput);
            else if(lastSource!=null)retry();
        }});
        buttons.addView(reviewButton,new LinearLayout.LayoutParams(0,-2,1));Button exit=new Button(this);exit.setText("退出");
        exit.setOnClickListener(new View.OnClickListener(){public void onClick(View v){requestExit();}});
        buttons.addView(exit,new LinearLayout.LayoutParams(0,-2,1));page.addView(buttons);setContentView(page);
        ioThread=new HandlerThread("lut-capture-io");ioThread.start();io=new Handler(ioThread.getLooper());
        io.post(new Runnable(){public void run(){prepare();}});
    }
    private void prepare() {
        try {
            if(!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()))throw new IOException("SD card not mounted writable");
            sd=Environment.getExternalStorageDirectory();work=RunFiles.file(RunFiles.file(sd,"SONYLUT"),"WORK");
            if(!work.isDirectory()&&!work.mkdirs())throw new IOException("cannot create SD work directory");
            RunFiles.clearAdbCopies(); // Expire this app's optional RAM copies before allocating image/cache memory.
            File custom=RunFiles.file(RunFiles.file(sd,"SONYLUT"),"CUSTOM.CUB");
            lutFile=custom.isFile()?custom:Assets.extract(new File(getApplicationInfo().sourceDir),"COLOR17.CUB",work);
            pipeline=getIntent().getBooleanExtra("jpeg_pipeline",true);
            int workers=getIntent().getIntExtra("lut_workers",4);
            if(workers!=1&&workers!=2&&workers!=4)throw new IOException("invalid LUT worker count");
            if(getIntent().getBooleanExtra("hardware_jpeg",true))hardware=new HardwareJpeg(getApplicationContext(),sd,lutFile,workers);
            else engine=Native.create(lutFile.getPath(),workers);
            photos=new PhotoFiles(sd);log("dcim_diagnostic",photos.describe());
            String recovery=getIntent().getStringExtra("recover_photo");
            if(recovery!=null){photos.recover(sd,recovery);recoverRequested=true;log("recover_photo",recovery);}
            File last=new File(work,"LAST.TXT");
            if(last.isFile()) {
                BufferedReader reader=new BufferedReader(new FileReader(last));String path;
                try{path=reader.readLine();}finally{reader.close();}
                if(path!=null){File file=new File(path).getCanonicalFile();
                    String prefix=new File(sd,"SONYLUT").getCanonicalPath()+File.separator;
                    String dcim=new File(sd,"DCIM").getCanonicalPath()+File.separator;
                    if(file.isFile()&&((file.getPath().startsWith(prefix)&&file.getName().equals("LUT.JPG"))
                            ||(file.getPath().startsWith(dcim)&&file.getName().matches("[A-Z0-9_]{1,8}\\.JPG"))))lastOutput=file;
                }
            }
            prepared=true;log("ready","lut="+lutFile.getName()+" backend="+(hardware!=null?"sony_hardware":"software"));
            flushLog();
            ui.post(new Runnable(){public void run(){openCamera();}});
        }catch(Throwable e){fail("prepare",e);}
    }
    private Object listener(final String type,final String event) throws Exception {
        final Object owner=cameraEx;
        Class<?> iface=Class.forName("com.sony.scalar.hardware.CameraEx$"+type);
        return Proxy.newProxyInstance(iface.getClassLoader(),new Class<?>[]{iface},new InvocationHandler(){
            public Object invoke(Object proxy,Method method,Object[] args) {
                if(method.getDeclaringClass()==Object.class) {
                    if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                    if(method.getName().equals("equals"))return proxy==args[0];
                    return "LutCapture"+type;
                }
                final int result=args!=null&&args.length>0&&args[0] instanceof Integer?((Integer)args[0]).intValue():0;
                long at=SystemClock.elapsedRealtime();
                String detail=(event.equals("stored")?"notification_value=":"status=")+result+" at_ms="+at;
                if(event.equals("focus_started"))lastFocusStartedAt=at;
                if(event.equals("focus_done"))lastFocusDoneAt=at;
                if(event.equals("preview_started")) {
                    detail+=" open_to_preview_ms="+(at-cameraOpenAt);
                    if(resumeAt!=0)detail+=" resume_to_preview_ms="+(at-resumeAt);
                    if(resuming)ui.post(new Runnable(){public void run(){if(owner==cameraEx)finishResume("preview_started");}});
                    else resumeAt=0;
                }
                if(event.equals("shutter")) {
                    if(busy)detail+=" press_to_callback_ms="+(at-shutterAt);
                    if(lastFocusStartedAt!=0)detail+=" last_focus_start_ms="+lastFocusStartedAt;
                    if(lastFocusDoneAt!=0)detail+=" last_focus_done_ms="+lastFocusDoneAt;
                }
                if(event.equals("stored")&&args!=null&&args.length>1&&args[1]!=null) {
                    for(String name:new String[]{"DirectoryName","FileName","DirectoryNo","FileNo"})try{detail+=" "+name+"="+args[1].getClass().getField(name).get(args[1]);}catch(Exception ignored){}
                    try {
                        final String directory=(String)args[1].getClass().getField("DirectoryName").get(args[1]);
                        final String name=(String)args[1].getClass().getField("FileName").get(args[1]);
                        io.post(new Runnable(){public void run(){try{
                            photos.confirmStored(directory,name);log("store_path_confirmed","ms="+(SystemClock.elapsedRealtime()-shutterAt)+" file="+name);
                            if(busy&&!processing){io.removeCallbacks(poll);poll.run();}
                        }catch(IOException e){log("store_path_ignored",e.toString());}}});
                    }catch(Exception e){log("store_path_unavailable",e.toString());}
                }
                log(event,detail);
                // StoreImageComplete's integer is not ShutterListener.STATUS_*.
                // Firmware forwards it independently with the saved file information.
                // A matching completion path plus stable file size avoids rereading
                // the entire original. Without a notification, the marker walk remains.
                if(event.equals("shutter"))ui.post(new Runnable(){public void run(){
                    if(result==0)arm();else if(busy&&!processing)fail(event,new IOException("shutter status="+result));
                }});
                return null;
            }
        });
    }
    private void openCamera() {
        if(!active||destroyed||!prepared||busy||reviewing||cameraEx!=null||(resuming&&review.afterDraw!=null)||holder.getSurface()==null||!holder.getSurface().isValid())return;
        try {
            cameraOpenAt=SystemClock.elapsedRealtime();lastFocusStartedAt=0;lastFocusDoneAt=0;storeListenerAvailable=false;previewListenerAvailable=false;
            Class<?> type=Class.forName("com.sony.scalar.hardware.CameraEx");
            cameraEx=type.getMethod("open",int.class,Class.forName("com.sony.scalar.hardware.CameraEx$OpenOptions")).invoke(null,0,null);
            camera=(Camera)type.getMethod("getNormalCamera").invoke(cameraEx);
            Camera.Parameters p=camera.getParameters();oldDrive=p.get("drive-mode");oldStorage=p.get("storage-fmt");
            changedDrive=!"single".equals(oldDrive);changedStorage="raw".equals(oldStorage);
            if(changedDrive)p.set("drive-mode","single");
            if(changedStorage)p.set("storage-fmt","rawjpeg");
            if(changedDrive||changedStorage)camera.setParameters(p);
            Camera.Size picture=p.getPictureSize();
            if(picture!=null){final int w=picture.width,h=picture.height;
                io.post(new Runnable(){public void run(){if(!destroyed&&hardware!=null)try {
                    long start=SystemClock.elapsedRealtime();hardware.preparePreview(w,h);
                    log("preview_prepared","ms="+(SystemClock.elapsedRealtime()-start)+" size="+w+"x"+h);
                }catch(Throwable e){log("preview_prepare_failed",root(e).toString());}}});
            }
            for(String[] item:new String[][]{{"ShutterListener","setShutterListener","shutter"},{"StoreImageCompleteListener","setStoreImageCompleteListener","stored"},
                    {"PreviewStartListener","setPreviewStartListener","preview_started"},{"AutoFocusStartListener","setAutoFocusStartListener","focus_started"},
                    {"AutoFocusDoneListener","setAutoFocusDoneListener","focus_done"}}) {
                try {type.getMethod(item[1],Class.forName("com.sony.scalar.hardware.CameraEx$"+item[0])).invoke(cameraEx,listener(item[0],item[2]));if(item[2].equals("stored"))storeListenerAvailable=true;if(item[2].equals("preview_started"))previewListenerAvailable=true;}
                catch(Throwable e){log("listener_unavailable",item[0]+": "+root(e));}
            }
            camera.setPreviewDisplay(holder);camera.startPreview();
            type.getMethod("startDirectShutter").invoke(cameraEx);
            log("camera_open","ms="+(SystemClock.elapsedRealtime()-cameraOpenAt)+" size="+(picture==null?"unknown":picture.width+"x"+picture.height));
            if(getIntent().getBooleanExtra("diagnostic_parameters",false))log("camera_parameters",p.flatten());
            readyMessage();
            if(recoverRequested){recoverRequested=false;arm(true);}
        }catch(Throwable e){closeCamera();fail("camera_open",e);}
    }
    private void arm() {
        arm(false);
    }
    private void arm(boolean recovery) {
        if(!active||destroyed||!prepared||busy||cameraEx==null)return;
        awaitStored=hardware!=null&&storeListenerAvailable&&!recovery;
        busy=true;processing=false;retryPending=false;lastSource=null;shutterAt=SystemClock.elapsedRealtime();ui.post(tick);
        log("capture_armed","at_ms="+shutterAt+" recovery="+recovery);
        io.post(new Runnable(){public void run(){photos.resetCandidate();poll.run();}});
    }
    private void retry() {
        if(busy||lastSource==null)return;busy=true;processing=true;shutterAt=SystemClock.elapsedRealtime();ui.post(tick);
        final File source=lastSource;io.post(new Runnable(){public void run(){processPhoto(source);}});
    }
    private void processPhoto(final File source) {
        processing=true;lastSource=source;
        log("source_ready","ms="+(SystemClock.elapsedRealtime()-shutterAt)+" file="+source.getPath());
        if(hardware==null){processReady(source);return;}
        // The shooting service and still-play decoder share camera resources.
        // Release only after the original is complete, on the camera's UI thread.
        ui.post(new Runnable(){public void run(){
            if(destroyed)return;long start=SystemClock.elapsedRealtime();closeCamera();
            log("capture_release","ms="+(SystemClock.elapsedRealtime()-start));
            io.post(new Runnable(){public void run(){if(!destroyed)processReady(source);}});
        }});
    }
    private void processReady(final File source) {
        File run=null,temp=null;boolean saved=false;
        try {
            final File output;final String outputUri;JSONObject stats;Bitmap memoryPreview=null;long started=SystemClock.elapsedRealtime();
            if(hardware!=null) {
                HardwareJpeg.Result result=hardware.process(source);output=result.output;outputUri=result.uri;stats=result.stats;memoryPreview=result.preview;
                photos.accepted(output); // Native exporter registers a new DCIM photo; it is not the next shutter's original.
            }else{
                run=RunFiles.create(sd);RunFiles.state(run,"PROCESSING");
                temp=RunFiles.file(run,"LUT.TMP");output=RunFiles.file(run,"LUT.JPG");outputUri=null;
                String result=pipeline?Native.filePipelined(engine,source.getPath(),temp.getPath(),1,true):Native.file(engine,source.getPath(),temp.getPath(),1,true);
                if(output.exists()||!temp.renameTo(output))throw new IOException("cannot publish LUT JPEG");
                stats=new JSONObject(result);
            }
            saved=true;lastOutput=output;lastOutputUri=outputUri;
            final long elapsed=SystemClock.elapsedRealtime()-shutterAt;
            final JSONObject record=new JSONObject();record.put("source",source.getPath());record.put("output",output.getPath());record.put("lut",lutFile.getName());
            record.put("shutter_to_saved_ms",elapsed);record.put("processing_wall_ms",SystemClock.elapsedRealtime()-started);record.put("stats",stats);
            log("photo_saved",record.toString());
            long reviewStart=SystemClock.elapsedRealtime();
            final Bitmap image=memoryPreview!=null?memoryPreview:decodeReview(output);
            final long reviewDecodeMs=SystemClock.elapsedRealtime()-reviewStart,shotStart=shutterAt,postedAt=SystemClock.elapsedRealtime();
            final File completedRun=run;
            ui.post(new Runnable(){public void run(){
                final long receivedAt=SystemClock.elapsedRealtime();
                processing=false;retryPending=false;
                if(destroyed){if(image!=null)image.recycle();return;}
                if(!active){busy=false;ui.removeCallbacks(tick);if(image!=null)image.recycle();return;}
                status.setText("照片已保存，正在显示成片…");
                review.nextDraw=new ReviewDrawn(){public void rendered(final long at){
                    ui.post(new Runnable(){public void run(){
                        busy=false;ui.removeCallbacks(tick);
                        status.setText("已显示带 LUT 照片 · 总耗时 "+((at-shotStart)/1000.0)+" 秒\n半按快门继续拍摄");
                        try {log("review_displayed",new JSONObject().put("output",output.getPath())
                            .put("shutter_to_saved_ms",elapsed).put("shutter_to_displayed_ms",at-shotStart)
                            .put("review_decode_ms",reviewDecodeMs).put("ui_queue_ms",receivedAt-postedAt).put("ui_draw_ms",at-receivedAt).toString());}catch(Exception ignored){}
                        // Bookkeeping and optional diagnostic copies are not prerequisites
                        // for showing the photo that Sony has already saved and indexed.
                        io.post(new Runnable(){public void run(){
                            try {
                                File savedRun=completedRun!=null?completedRun:RunFiles.create(sd);
                                record.put("shutter_to_displayed_ms",at-shotStart);record.put("review_decode_ms",reviewDecodeMs);
                                write(RunFiles.file(savedRun,"PHOTO.JSN"),record.toString()+"\n");
                                write(RunFiles.file(savedRun,"SOURCE.TXT"),source.getPath()+"\nLUT="+lutFile.getName()+"\n");
                                RunFiles.state(savedRun,"DONE");write(new File(work,"LAST.TXT"),output.getPath()+"\n");
                                if(getIntent().getBooleanExtra("adb_export",false)) {
                                    File export=RunFiles.exportAdb(savedRun,source,outputUri!=null?output:null);log("adb_export",export.getPath());
                                }
                            }catch(Throwable e){log("bookkeeping_failed",root(e).toString());}
                            flushLog();
                        }});
                    }});
                }};
                showReview(image);
            }});
        }catch(Throwable e){
            if(temp!=null&&temp.exists())temp.delete();
            if(run!=null)try{RunFiles.state(run,"FAILED\n"+root(e));}catch(IOException ignored){}
            if(hardware!=null)try{photos.refresh();}catch(IOException refreshError){log("refresh_failed",refreshError.toString());}
            fail(saved?"review":"process",e);
        }
    }
    private void loadReview(final File file) {
        busy=true;closeCamera();io.post(new Runnable(){public void run(){try {
            final boolean nativeFile=hardware!=null&&file.getCanonicalPath().startsWith(new File(sd,"DCIM").getCanonicalPath()+File.separator);
            final Bitmap image=nativeFile?hardware.review(file,file.equals(lastOutput)?lastOutputUri:null):decodeReview(file);
            ui.post(new Runnable(){public void run(){busy=false;if(!destroyed&&active)showReview(image);else if(image!=null)image.recycle();}});
        }catch(Throwable e){fail("review",e);}}});
    }
    private static Bitmap decodeReview(File file) throws IOException {
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),o);
        if(o.outWidth<1||o.outHeight<1)throw new IOException("cannot decode output preview");
        o.inJustDecodeBounds=false;o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>1000)o.inSampleSize*=2;
        Bitmap image=BitmapFactory.decodeFile(file.getPath(),o);if(image==null)throw new IOException("output preview allocation failed");
        int orientation=new ExifInterface(file.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);
        int degrees=orientation==6?90:orientation==3?180:orientation==8?270:0;
        if(degrees!=0){Matrix m=new Matrix();m.postRotate(degrees);Bitmap rotated=Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),m,true);if(rotated!=image)image.recycle();image=rotated;}
        return image;
    }
    private void showReview(Bitmap image) {
        if(camera!=null)try{camera.stopPreview();}catch(Throwable e){log("stop_preview",root(e).toString());}
        review.setImageBitmap(image);if(displayed!=null&&displayed!=image)displayed.recycle();displayed=image;
        review.setVisibility(View.VISIBLE);reviewing=true;reviewButton.setText("继续拍摄");
    }
    private void resumeViewfinder() {
        if(busy||resuming)return;
        resumeAt=SystemClock.elapsedRealtime();resuming=true;
        log("resume_requested","at_ms="+resumeAt+" camera_open="+(cameraEx!=null));
        // Draw a cleared review and feedback before CameraEx.open blocks the UI
        // thread. Reveal the native layer only after its preview-ready event.
        try {review.nextDraw=null;review.setImageDrawable(null);if(displayed!=null){
                final Bitmap reusable=displayed;displayed=null;
                io.post(new Runnable(){public void run(){if(hardware!=null)hardware.offerPreview(reusable);else reusable.recycle();}});
            }
            reviewing=false;status.setText("正在恢复取景…\n请稍候");reviewButton.setEnabled(false);
            review.afterDraw=resumeOpen;review.setVisibility(View.VISIBLE);review.invalidate();
        }catch(Throwable e){fail("resume_preview",e);}
    }
    private void finishResume(String reason) {
        if(!resuming)return;
        long elapsed=SystemClock.elapsedRealtime()-resumeAt;
        cancelResume();reviewButton.setText("查看成片");readyMessage();
        log("viewfinder_visible","ms="+elapsed+" reason="+reason);
    }
    private void cancelResume() {
        ui.removeCallbacks(resumeOpen);ui.removeCallbacks(resumeTimeout);review.afterDraw=null;
        if(resuming)review.setVisibility(View.INVISIBLE);
        resuming=false;resumeAt=0;reviewButton.setEnabled(true);
    }
    private void readyMessage(){if(!busy&&!resuming&&cameraEx!=null)status.setText("按快门拍照 · "+(lutFile.getName().equals("CUSTOM.CUB")?"自定义 LUT":"测试色彩 LUT")+"\n原生取景，拍后自动生成成片");}
    private void requestExit(){if(busy){status.setText("正在保存，请完成后退出");return;}finish();}
    private static Throwable root(Throwable e){while(e.getCause()!=null&&e.getCause()!=e)e=e.getCause();return e;}
    private void fail(final String phase,Throwable e) {
        log("error",phase+": "+root(e));android.util.Log.e("SonyLutCapture",phase,e);
        ui.post(new Runnable(){public void run(){busy=false;processing=false;ui.removeCallbacks(tick);cancelResume();
            if(!destroyed){status.setText(phase.equals("review")?"成片已保存，回看加载失败。可点击查看成片。":"操作失败，原照片保留。请查看日志。");retryPending=phase.equals("process")&&lastSource!=null;reviewButton.setText(retryPending?"重试处理":"查看成片");}
        }});
        if(io!=null)io.post(new Runnable(){public void run(){flushLog();}});
    }
    private void log(String event,String message) {
        String line=event+" "+message;android.util.Log.i("SonyLutCapture",line);
        try{synchronized(pendingLog){if(pendingLog.length()>65536)pendingLog.delete(0,pendingLog.length());pendingLog.append(new JSONObject().put("event",event).put("message",message).toString()).append('\n');}}catch(Exception ignored){}
    }
    private void flushLog() {
        if(work==null)return;String text;synchronized(pendingLog){text=pendingLog.toString();pendingLog.setLength(0);}
        if(text.length()==0)return;
        try{FileWriter f=new FileWriter(new File(work,"CAPTURE.JSN"),true);try{f.write(text);}finally{f.close();}}
        catch(IOException e){android.util.Log.w("SonyLutCapture","SD log write failed",e);}
    }
    private static void write(File file,String text) throws IOException {FileOutputStream out=new FileOutputStream(file);try{out.write(text.getBytes("UTF-8"));out.flush();}finally{out.close();}}
    private void closeCamera() {
        Camera normal=camera;Object extended=cameraEx;camera=null;cameraEx=null;
        long started=SystemClock.elapsedRealtime(),restored=started,stopped=started;
        if(normal!=null) {
            try {if(changedDrive||changedStorage){Camera.Parameters p=normal.getParameters();
                if(changedDrive&&oldDrive!=null)p.set("drive-mode",oldDrive);
                if(changedStorage&&oldStorage!=null)p.set("storage-fmt",oldStorage);
                normal.setParameters(p);}
            }catch(Throwable e){log("restore_settings",root(e).toString());}
            restored=SystemClock.elapsedRealtime();
            try{normal.stopPreview();}catch(Throwable ignored){}
            stopped=SystemClock.elapsedRealtime();
        }
        if(extended!=null)try{extended.getClass().getMethod("release").invoke(extended);}catch(Throwable e){log("release_camera",root(e).toString());}
        if(extended!=null)log("release_stages","restore_ms="+(restored-started)+" stop_ms="+(stopped-restored)+" release_ms="+(SystemClock.elapsedRealtime()-stopped));
        changedDrive=false;changedStorage=false;
    }
    @Override protected void onResume(){super.onResume();active=true;getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);openCamera();}
    @Override protected void onNewIntent(final android.content.Intent intent) {
        super.onNewIntent(intent);final String recovery=intent.getStringExtra("recover_photo");
        if(recovery==null||busy||resuming||!prepared){log("recovery_ignored","activity busy or no explicit source");return;}
        io.post(new Runnable(){public void run(){try {
            photos.recover(sd,recovery);ui.post(new Runnable(){public void run(){
                if(destroyed)return;setIntent(intent);
                if(reviewing||cameraEx==null){recoverRequested=true;resumeViewfinder();}else arm(true);
            }});
        }catch(Throwable e){fail("recover",e);}}});
    }
    @Override protected void onPause(){active=false;cancelResume();closeCamera();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);super.onPause();}
    @Override protected void onDestroy(){destroyed=true;ui.removeCallbacks(tick);cancelResume();closeCamera();if(io!=null){io.removeCallbacks(poll);io.post(new Runnable(){public void run(){if(engine!=0){Native.destroy(engine);engine=0;}if(hardware!=null){hardware.close();hardware=null;}ioThread.quit();}});}if(displayed!=null)displayed.recycle();super.onDestroy();}
    public void surfaceCreated(SurfaceHolder h){openCamera();}
    public void surfaceChanged(SurfaceHolder h,int format,int width,int height){openCamera();}
    public void surfaceDestroyed(SurfaceHolder h){closeCamera();}
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int sc=e.getScanCode(),key=e.getKeyCode();boolean shutter=sc==516||sc==517||sc==518||key==516||key==517||key==518||key==KeyEvent.KEYCODE_CAMERA;
        if(sc==514||key==KeyEvent.KEYCODE_BACK||key==KeyEvent.KEYCODE_MENU){if(e.getAction()==KeyEvent.ACTION_UP)requestExit();return true;}
        if(shutter) {
            if(e.getRepeatCount()==0)log("shutter_key","at_ms="+SystemClock.elapsedRealtime()+" scan="+sc+" key="+key+" action="+e.getAction()+" busy="+busy+" review="+reviewing+" resuming="+resuming);
            if(e.getAction()==KeyEvent.ACTION_UP)return super.dispatchKeyEvent(e);
            if(busy||resuming||!prepared)return true;
            if(reviewing){if(e.getAction()==KeyEvent.ACTION_DOWN)resumeViewfinder();return true;}
            if(cameraEx==null&&!retryPending){openCamera();return true;}
            if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0&&(sc==518||key==518||key==KeyEvent.KEYCODE_CAMERA))arm();
            return super.dispatchKeyEvent(e);
        }
        if(sc==232||key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){if(e.getAction()==KeyEvent.ACTION_UP)reviewButton.performClick();return true;}
        if(key==624||sc==572||(key>=534&&key<=551))return true;
        return super.dispatchKeyEvent(e);
    }
}
