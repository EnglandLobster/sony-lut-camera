package org.hongen.sonylutprobe;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Staged CameraSequence capture. Independent of the legacy JPEG photo path.
 * preview: camera-only control; handoff: return RAW to Sony;
 * develop: Sony RAW->YC then Sony storeImage; develop-xfine changes only
 * JPEG quality to 95 for a single, transferred-image store. lut: preserve original, grade,
 * then store a second JPEG from the retained YC. init_only defaults to true;
 * init_stage defaults to defaults (no extra map). Configured is explicit.
 * continuous=true rearms the paired LUT session; verify_payloads=true enables
 * the full diagnostic scan. Neither changes pixels, JPEG quality or retention.
 * No JpegExporter, custom memory map, software codec, or auto shutter.
 */
public class PreJpegProbeActivity extends Activity implements SurfaceHolder.Callback {
    protected boolean productUi(){return false;}
    protected boolean previewIsolationProbe(){return false;}
    protected boolean automaticNativePreview(){return false;}
    private NativePreviewSession isolationPreview;
    private PreviewCaptureBarrier isolationBarrier;
    private AutoPreviewGate autoPreview;
    private CameraNativePreviewDriver previewDriver;
    private NativePreviewProfile automaticProfile;
    private LutLibrary.Entry selectedLut;
    private PhotoHud productHud;
    private volatile boolean developEnabledThisShot;
    private SimpleExposure exposure;
    private CaptureSetup captureSetup;
    private CaptureDiagnostics startupDiagnostics;
    private volatile String startupFailure;
    private CaptureTelemetry telemetry;
    private int pendingReceipts;
    private long recordedPairShot=-1;
    private boolean showingOriginal;
    private boolean playbackRequested;
    private int playbackOffset;
    private static boolean hasEnteredProbe;
    private final Handler ui=new Handler();
    private final PreJpegGate gate=new PreJpegGate();
    private HandlerThread thread;private Handler work;
    private SurfaceHolder holder;private TextView status;
    private Object cameraEx,sequence,filter;private Camera camera;
    private Class<?> cameraType,sequenceType,rawType,imageType;
    private volatile boolean active,prepared,workerActive,destroyed;
    private boolean opening,closed,initOnly;private String phase,mapPath,initStage,mapRevision;private File run,sd;
    private boolean normalizeZoom,continuous,verifyPayloads,overlapOriginal,sourceReferenceEnabled,alternateOverlap,overlapThisShot;
    // Store notifications queue on the same worker as grading. The original
    // remains the sole pending store until confirmed, even with overlap enabled.
    private boolean lutApplied;private Bitmap sourceReference;
    private PhotoFiles photoFiles;
    private Object retainedImage;private long lutCache;private Bitmap lutReview;
    private File originalFile,gradedFile;private String originalQuality;
    private ReviewView review;private boolean awaitFirstFrame;
    private long displayedAt,nextReadyAt,rearmAt;private boolean finishingRearm,reviewing;
    private boolean reviewClearCheck;
    private Thread.UncaughtExceptionHandler previousExceptionHandler,exceptionHandler;
    private final Map<String,String> originalZoom=new LinkedHashMap<String,String>();
    private static final String[] ZOOM_KEYS={"smart-zoom","super-resolution-zoom","precision-zoom"};
    private long pressAt,rawAt,storeAt;private String timingOrigin="unset";
    private FileOutputStream journal;private boolean journalClosed;private long journalMs,journalAtPress;
    private boolean processingVisible,awaitProcessingFrame;private String processingText;
    private final CaptureProgress processingProgress=new CaptureProgress();
    private final Runnable processingTick=new Runnable(){public void run(){
        if(!active||destroyed||!processingVisible)return;
        long tenths=(SystemClock.elapsedRealtime()-pressAt)/100;
        if(productHud!=null){productHud.setVisibility(View.VISIBLE);productHud.mode="processing";productHud.detail=processingText;productHud.processingStage=processingProgress.stage();productHud.progress=(tenths/10)+"."+(tenths%10)+" 秒";
            productHud.footer="正在保存原片与成片，请保持相机开机";productHud.update();status.setVisibility(View.GONE);}
        else status.setText(processingText+"\n"+(tenths/10)+"."+(tenths%10)+" 秒");
        // Updating the hidden legacy TextView would request a new layout and
        // defeat the product HUD's partial redraw while the negative is still.
        ui.postDelayed(this,250);
    }};
    private final Runnable restoreGraphics=new Runnable(){public void run(){
        if(!active||closed||destroyed||!processingVisible)return;
        try{
            review.showGraphicsOutput();awaitProcessingFrame=true;status.invalidate();
            event("graphics_restored","from_press_ms="+(SystemClock.elapsedRealtime()-pressAt));
        }catch(Exception e){
            // A display failure must not invalidate an in-flight photo/save.
            android.util.Log.e("SonyPreJpeg","graphics_restore_failed",e);
        }
    }};
    private boolean paired(){return "lut".equals(phase);}
    private boolean develops(){return "develop".equals(phase)||"develop-xfine".equals(phase)||paired();}
    private boolean extraFine(){return "develop-xfine".equals(phase)||paired();}
    private final Runnable hideReviewStatus=new Runnable(){public void run(){
        if(active&&review!=null&&review.getVisibility()==View.VISIBLE)status.setVisibility(View.INVISIBLE);
    }};
    private final class StatusView extends TextView {
        StatusView(){super(PreJpegProbeActivity.this);}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);
            if(awaitProcessingFrame){awaitProcessingFrame=false;
                android.util.Log.i("SonyPreJpeg",SystemClock.elapsedRealtime()+" processing_frame from_press_ms="+(SystemClock.elapsedRealtime()-pressAt));
            }
        }
    }
    private final class ReviewView extends SonyReviewView {
        ReviewView(){super(PreJpegProbeActivity.this);}
        @Override protected void pixelAspectChanged(float value){if(productHud!=null){productHud.pixelAspect=value;productHud.update();}}
        @Override protected void displayFailed(Exception e){failure("display_geometry",e);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            if(reviewClearCheck&&getWidth()>0&&getHeight()>0){
                reviewClearCheck=false;post(new Runnable(){public void run(){checkReviewClear();}});
            }
            if(awaitFirstFrame&&getDrawable()!=null){
                awaitFirstFrame=false;final long shownAt=SystemClock.elapsedRealtime();
                final long shot=gate.shot();displayedAt=shownAt;
                savedStatus("成片 "+seconds(shownAt-pressAt)+" 秒"+(continuous?" · 正在准备下一张":" · 已保存原片 + LUT"));
                work.post(new Runnable(){public void run(){
                    if(closed||shot!=gate.shot())return;
                    event("first_frame","shutter_to_displayed_ms="+(shownAt-pressAt)+" journal_write_ms="+journalWriteMs()+" journal_shot_ms="+(journalWriteMs()-journalAtPress)+" original="+originalFile+" graded="+gradedFile);
                    if(verifyPayloads)verifyPairPayloads();
                    else event("payload_verification_skipped","mode=normal saved_notification_and_header_checked=true");
                    if(continuous&&gate.state()==PreJpegGate.State.SAVED)ui.post(new Runnable(){public void run(){rearm(shot);}});
                }});
            }
        }
    }

    private synchronized void event(String name,String detail){
        String line=SystemClock.elapsedRealtime()+" "+name+" "+detail+" shot="+gate.shot();
        android.util.Log.i("SonyPreJpeg",line);
        if(startupDiagnostics!=null&&gate.shot()==0)try{startupDiagnostics.append(line);}
        catch(IOException e){android.util.Log.e("SonyPreJpeg","startup_diagnostic_write_failed",e);}
        long start=SystemClock.elapsedRealtime();
        if(run!=null&&!journalClosed)try{
            // Keep one descriptor for the run. Each unbuffered write still
            // reaches the filesystem; avoid repeating SD path lookup/open/close.
            if(journal==null)journal=new FileOutputStream(RunFiles.file(run,"PROBE.JSN"),true);
            journal.write((line+"\n").getBytes("UTF-8"));
        }catch(IOException e){android.util.Log.e("SonyPreJpeg","journal_write_failed",e);}
        journalMs+=SystemClock.elapsedRealtime()-start;
    }
    private synchronized void closeJournal(){journalClosed=true;
        if(journal!=null)try{journal.close();}catch(IOException e){android.util.Log.e("SonyPreJpeg","journal_close_failed",e);}finally{journal=null;}
    }
    private synchronized long journalWriteMs(){return journalMs;}
    private static String seconds(long ms){return String.format(java.util.Locale.US,"%.3f",ms/1000.0);}
    private void processing(final CaptureProgress.Stage stage,final String text){
        final long shot=gate.shot();
        ui.post(new Runnable(){public void run(){
        if(!active||destroyed||closed||!gate.busy()||!processingProgress.advance(shot,stage))return;
        processingText=text;
        android.util.Log.i("LutUI","capture_progress shot="+shot+" stage="+stage+" text="+text);
        if(!processingVisible){
            processingVisible=true;ui.removeCallbacks(hideReviewStatus);
            status.setLayoutParams(new FrameLayout.LayoutParams(-1,-1));
            status.setBackgroundColor(0xff181818);status.setGravity(Gravity.CENTER);status.setTextSize(24);
            status.setSingleLine(false);status.setMaxLines(3);status.setVisibility(View.VISIBLE);
        }
        ui.removeCallbacks(processingTick);processingTick.run();
    }});}
    private void endProcessing(){
        boolean wasVisible=processingVisible;
        if(productHud!=null)productHud.endDeveloping();
        processingVisible=false;awaitProcessingFrame=false;ui.removeCallbacks(processingTick);ui.removeCallbacks(restoreGraphics);
        if(!wasVisible)return;
        status.setLayoutParams(new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        status.setBackgroundColor(0xb0000000);status.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);status.setTextSize(17);
    }
    private void message(final String value){ui.post(new Runnable(){public void run(){if(!destroyed){
        ui.removeCallbacks(hideReviewStatus);status.setVisibility(View.VISIBLE);status.setSingleLine(false);status.setMaxLines(3);status.setText(value);
        if(productHud!=null){status.setVisibility(View.GONE);
            if(gate.state()==PreJpegGate.State.READY&&!reviewing)productLive();
            else if(gate.busy()){productHud.mode="processing";productHud.detail=value.startsWith("接口等待")?"保存时间较长，请继续等待":value;productHud.progress="";productHud.update();}
        }
    }}});}
    private void savedStatus(String value){
        endProcessing();
        ui.removeCallbacks(hideReviewStatus);status.setSingleLine(true);status.setVisibility(View.VISIBLE);status.setText(value);
        if(productHud!=null){status.setVisibility(View.GONE);productHud.setVisibility(View.VISIBLE);productHud.mode="review";productHud.original=showingOriginal;
            productHud.detail="";
            productHud.footer="←→ / 拨轮翻图  ● 对照  ↑ 信息  半按继续";productHud.progress="";productHud.update();
        }else ui.postDelayed(hideReviewStatus,3500);
    }
    private static Throwable cause(Throwable e){while(e.getCause()!=null)e=e.getCause();return e;}
    private void failure(final String where,final Throwable e){
        android.util.Log.e("SonyPreJpeg","failure stage="+where,e);
        if(productUi()&&gate.shot()==0){
            startupFailure="阶段："+where+"\n"+cause(e).toString();
            if(startupDiagnostics!=null)try{startupDiagnostics.failure(e);}catch(IOException writeError){android.util.Log.e("SonyPreJpeg","startup_diagnostic_write_failed",writeError);}
        }
        event("failed",where+" "+cause(e));gate.fail();message("测试停止："+where+"\n请保留当前页面，读取日志。");
        ui.post(new Runnable(){public void run(){
            if(autoPreview!=null){
                autoPreview.close();
                if(sequence!=null)try{sequenceType.getMethod("setReleaseLock",boolean.class).invoke(sequence,true);}
                catch(Exception lockError){event("failure_lock_error",cause(lockError).toString());}
            }
            endProcessing();
            if(productHud!=null){status.setVisibility(View.GONE);productHud.mode="error";productHud.detail="拍摄未完成";
                productHud.progress=startupFailure!=null?where+": "+cause(e).toString():originalFile!=null?"原片已保存":"请返回 LUT 库重新进入拍摄";
                productHud.footer=startupFailure!=null?"详情：SD卡 SONYLUT/DIAG/INIT.TXT · MENU 返回":"MENU 返回 LUT 库后重试";
                if(startupFailure!=null)setResult(Activity.RESULT_FIRST_USER,new android.content.Intent().putExtra("startup_failure",startupFailure));productHud.update();}
            if(active&&review!=null)try{review.showGraphicsOutput();}catch(Exception x){android.util.Log.e("SonyPreJpeg","error_graphics_restore_failed",x);}
        }});
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);
        if(productUi())try{
            startupDiagnostics=new CaptureDiagnostics(Environment.getExternalStorageDirectory());
            event("activity_create","model="+Build.MODEL+" sdk="+Build.VERSION.SDK_INT+" abi="+Build.CPU_ABI
                +" max_heap="+Runtime.getRuntime().maxMemory()+" app="+getPackageManager().getPackageInfo(getPackageName(),0).versionName);
        }catch(Exception e){android.util.Log.e("SonyPreJpeg","startup_diagnostic_open_failed",e);}
        previousExceptionHandler=Thread.getDefaultUncaughtExceptionHandler();
        exceptionHandler=new Thread.UncaughtExceptionHandler(){public void uncaughtException(Thread thread,Throwable error){
            android.util.Log.e("SonyPreJpeg","uncaught thread="+thread.getName()+" shot="+gate.shot(),error);
            if(startupDiagnostics!=null)try{startupDiagnostics.failure(error);}catch(Throwable ignored){}
            try{if(run!=null){PrintWriter out=new PrintWriter(new FileOutputStream(RunFiles.file(run,"CRASH.TXT"),true));
                try{out.println("thread="+thread.getName()+" state="+gate.state()+" shot="+gate.shot());error.printStackTrace(out);out.flush();}finally{out.close();}}}
            catch(Throwable ignored){}
            if(previousExceptionHandler!=null)previousExceptionHandler.uncaughtException(thread,error);
            else{android.os.Process.killProcess(android.os.Process.myPid());System.exit(10);}
        }};
        Thread.setDefaultUncaughtExceptionHandler(exceptionHandler);
        if(getIntent().getBooleanExtra("review_clear_check",false)){
            phase="review";initOnly=true;reviewClearCheck=true;createPage();
            lutReview=Bitmap.createBitmap(30,20,Bitmap.Config.ARGB_8888);
            review.setImageBitmap(lutReview);review.setVisibility(View.VISIBLE);
            message("显示组件检查 · 不打开拍摄接口");return;
        }
        final String reviewPhoto=getIntent().getStringExtra("review_photo");
        if(reviewPhoto!=null){
            // Inspect an already-saved result through the exact same display
            // layout. No CameraEx, capture sequence, LUT or new output file.
            phase="review";initOnly=true;createPage();
            work.post(new Runnable(){public void run(){loadExistingReview(reviewPhoto);}});return;
        }
        phase=getIntent().getStringExtra("phase");String token=getIntent().getStringExtra("probe_token");
        initOnly=getIntent().getBooleanExtra("init_only",true);
        initStage=getIntent().getStringExtra("init_stage");if(initStage==null)initStage="defaults";
        mapRevision=getIntent().getStringExtra("map_revision");if(mapRevision==null)mapRevision="firmware00";
        continuous=getIntent().getBooleanExtra("continuous",false);
        verifyPayloads=getIntent().getBooleanExtra("verify_payloads",false);
        overlapOriginal=getIntent().getBooleanExtra("overlap_original",false);
        sourceReferenceEnabled=getIntent().getBooleanExtra("source_reference",false);
        alternateOverlap=getIntent().getBooleanExtra("alternate_overlap",false);
        if(alternateOverlap&&(!continuous||!overlapOriginal)){
            status=new TextView(this);status.setText("交替测速需要开启连续保存重叠测试。");setContentView(status);return;
        }
        if((overlapOriginal&&!paired())||(sourceReferenceEnabled&&(!paired()||!verifyPayloads||continuous))){
            status=new TextView(this);status.setText("原片对照仅用于单张诊断；保存重叠仅用于双文件 LUT 流程。");setContentView(status);return;
        }
        if(continuous&&(!paired()||initOnly)){
            status=new TextView(this);status.setText("连续拍摄仅用于已验证的双文件 LUT 流程。");setContentView(status);return;
        }
        boolean freshEntry=!hasEnteredProbe;hasEnteredProbe=true;
        normalizeZoom=getIntent().getBooleanExtra("normalize_zoom",false);
        // Revision00 stalls even in a fresh process. The reference application
        // actually selects its newer bundled revision01, now verified to save
        // a full EXTRA FINE JPEG. All EXTRA FINE probes still require a fresh,
        // explicitly selected revision01 session; revision00 stays blocked.
        if((!"firmware00".equals(mapRevision)&&!"reference01".equals(mapRevision))||
                (extraFine()&&!initOnly&&(!"reference01".equals(mapRevision)||
                !freshEntry||!getIntent().getBooleanExtra("fresh_session",false)))){
            status=new TextView(this);status.setText("此保存组合尚未开放测试。\n正常拍摄请打开 LUT Camera。");setContentView(status);return;
        }
        if(getIntent().getBooleanExtra("restore_smart_zoom",false)&&(!"preview".equals(phase)||!initOnly)){
            status=new TextView(this);status.setText("设置恢复仅允许在无拍摄的预览检查中执行。");setContentView(status);return;
        }
        if((!"preview".equals(phase)&&!"handoff".equals(phase)&&!develops())||token==null||token.length()<8
                ||(!"defaults".equals(initStage)&&!"configured".equals(initStage))
                ||(!initOnly&&!"configured".equals(initStage))
                ||token.equals(getPreferences(0).getString("entered",null))
                ||!getPreferences(0).edit().putString("entered",token).commit()){
            status=new TextView(this);status.setText("单次测试已结束或缺少测试标识。\n正常拍照请打开 LUT Camera。");setContentView(status);return;
        }
        createPage();
        work.post(new Runnable(){public void run(){prepare();}});
    }
    private void createPage(){
        SurfaceView surface=new SurfaceView(this);holder=surface.getHolder();holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);holder.addCallback(this);
        FrameLayout frame=new FrameLayout(this);frame.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        review=new ReviewView();review.setBackgroundColor(Color.BLACK);review.setVisibility(View.INVISIBLE);
        frame.addView(review,new FrameLayout.LayoutParams(-1,-1));
        status=new StatusView();status.setTextSize(17);status.setMaxLines(3);status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xb0000000);status.setPadding(8,3,8,3);status.setText("检测相机拍摄接口…");
        frame.addView(status,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        if(productUi()){productHud=new PhotoHud(this);frame.addView(productHud,new FrameLayout.LayoutParams(-1,-1));status.setVisibility(View.GONE);}
        setContentView(frame);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        thread=new HandlerThread("prejpeg-probe");thread.start();work=new Handler(thread.getLooper());
    }
    private void loadExistingReview(String path){try{
        File root=new File(Environment.getExternalStorageDirectory(),"DCIM").getCanonicalFile();
        final File file=new File(path).getCanonicalFile();
        if(!file.getPath().startsWith(root.getPath()+File.separator)||!file.getName().matches("[A-Z0-9_]{1,8}\\.JPG")||
                !PhotoFiles.jpegHeader(file,6000,4000))throw new IOException("review requires a saved 6000x4000 DCIM JPEG");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=8;
        final Bitmap bitmap=BitmapFactory.decodeFile(file.getPath(),options);
        if(bitmap==null)throw new IOException("saved review decode failed");
        ui.post(new Runnable(){public void run(){
            if(destroyed||closed){bitmap.recycle();return;}
            lutReview=bitmap;review.setImageBitmap(bitmap);review.setVisibility(View.VISIBLE);
            savedStatus("已保存的 LUT 成片");
            event("review_loaded","file="+file+" bitmap="+bitmap.getWidth()+"x"+bitmap.getHeight()+" camera_opened=false");
        }});
    }catch(Throwable e){failure("review_file",e);}
    }
    private void prepare(){try{
        sd=Environment.getExternalStorageDirectory();run=RunFiles.create(sd);event("prepare","phase="+phase+" init_only="+initOnly+" init_stage="+initStage+" run="+run+" pid="+android.os.Process.myPid()+" fresh_session="+getIntent().getBooleanExtra("fresh_session",false)+" continuous="+continuous+" verify_payloads="+verifyPayloads+" overlap_original="+overlapOriginal+" source_reference="+sourceReferenceEnabled+" probe_token="+getIntent().getStringExtra("probe_token"));
        if(!initOnly)photoFiles=new PhotoFiles(sd,"handoff".equals(phase)?PhotoFiles.Format.ARW:PhotoFiles.Format.JPEG);
        Class<?> props=Class.forName("com.sony.scalar.sysutil.ScalarProperties");Method get=props.getMethod("getString",String.class);
        String memory=(String)get.invoke(null,"device.memory"),pixels=(String)get.invoke(null,"mem.rawimage.size.in.mega.pixel");
        event("platform","memory="+memory+" pixels="+pixels);
        for(String field:new String[]{"PROP_MODEL_NAME","PROP_VERSION_PLATFORM"})try{
            event("platform_property",field+"="+get.invoke(null,props.getField(field).get(null)));
        }catch(Exception unavailable){event("platform_property",field+" unavailable="+cause(unavailable));}
        try{event("firmware",String.valueOf(props.getMethod("getFirmwareVersion").invoke(null)));}
        catch(Exception unavailable){event("firmware","unavailable="+cause(unavailable));}
        if(!"ILDCSC_MUSASHI8Gb".equals(memory)||!"24".equals(pixels))throw new IOException("当前内存布局尚未适配："+memory+"/"+pixels);
        File map;
        if("reference01".equals(mapRevision)){
            // These bytes are the original exact-platform reference asset.
            // They are data consumed by Sony's map loader, not a shared library.
            map=Assets.extract(new File(getApplicationInfo().sourceDir),"MAP24R1.BIN",getCacheDir());
            if(map.length()!=1860)throw new IOException("reference map size mismatch");
        }else{
            map=new File("/system/etc/memmap/libILDCSC_MUSASHI8Gb24m_PictureEffectPlus_00_00.so");
            if(!map.isFile()||map.length()!=1020)throw new IOException("validated firmware map unavailable");
        }
        // Sony AV service resolves native paths outside the Android chroot.
        mapPath="/android"+map.getPath();event("profile","memory="+memory+" pixels="+pixels+" map_revision="+mapRevision+" map="+mapPath);
        cameraType=Class.forName("com.sony.scalar.hardware.CameraEx");sequenceType=Class.forName("com.sony.scalar.hardware.CameraSequence");
        rawType=Class.forName("com.sony.scalar.hardware.CameraSequence$RawData");imageType=Class.forName("com.sony.scalar.graphics.OptimizedImage");
        if(paired()&&!initOnly){
            File custom=RunFiles.file(RunFiles.file(sd,"SONYLUT"),"CUSTOM.CUB");
            File lut;
            if(productUi()){
                selectedLut=new LutLibrary(ProductFiles.library()).find(getIntent().getStringExtra("lut_id"));
                if(selectedLut==null)throw new IOException("selected LUT is unavailable");
                lut=selectedLut.file;
                ui.post(new Runnable(){public void run(){if(!destroyed&&productHud!=null){productHud.name=selectedLut.name;productHud.tag="";productHud.update();}}});
            }else lut=custom.isFile()?custom:Assets.extract(new File(getApplicationInfo().sourceDir),"COLOR17.CUB",run);
            long loadStart=SystemClock.elapsedRealtime();
            if(productUi()){
                LutBinaryCache.verifySource(selectedLut);
                if(automaticNativePreview()&&PreviewProfileStore.choice(selectedLut).enabled){
                    automaticProfile=PreviewProfileStore.load(selectedLut);
                    if(automaticProfile==null)throw new IOException("请返回 LUT 库生成近似预览");
                    event("preview_profile_loaded","fit_version="+automaticProfile.fitVersion+" mae="+automaticProfile.mae+" warning="+automaticProfile.largeError());
                }
                event("lut_source_verified","ms="+(SystemClock.elapsedRealtime()-loadStart));
                event("lut_load_begin","workers=4");
                lutCache=Native.prepareDeviceLutCompiled(lut.getPath(),LutBinaryCache.binary(lut).getPath(),selectedLut.id,4);
            }else lutCache=Native.prepareDeviceLut(lut.getPath(),4);
            event("lut_load",Native.deviceLutLoadInfo(lutCache)+" source_and_load_ms="+(SystemClock.elapsedRealtime()-loadStart));
            long bitmapStart=SystemClock.elapsedRealtime();lutReview=Bitmap.createBitmap(750,500,Bitmap.Config.ARGB_8888);
            if(sourceReferenceEnabled||productUi())sourceReference=Bitmap.createBitmap(750,500,Bitmap.Config.ARGB_8888);
            event("review_buffers_ready","ms="+(SystemClock.elapsedRealtime()-bitmapStart));
            event("lut_prepared","file="+lut+" workers=4 review=750x500");
        }
        prepared=true;ui.post(new Runnable(){public void run(){open();}});
    }catch(Throwable e){failure("prepare",e);}}
    private Object listener(String iface,InvocationHandler callback)throws Exception{
        Class<?> type=Class.forName(iface);return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},callback);
    }
    private static Object objectMethod(Object proxy,Method method,Object[] args){
        if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
        if(method.getName().equals("equals"))return proxy==args[0];return "PreJpegProbeListener";
    }
    private void open(){
        if(!active||!prepared||opening||closed||holder==null||!holder.getSurface().isValid())return;
        opening=true;
        try{
            // Start EE after listener registration so its readiness event is not
            // lost during open(). Retain the user's existing shooting settings.
            event("camera_api_lookup","CameraEx.OpenOptions");
            Class<?> openType=Class.forName("com.sony.scalar.hardware.CameraEx$OpenOptions");Object options=openType.newInstance();
            openType.getMethod("setPreview",boolean.class).invoke(options,false);
            event("camera_open_begin","preview=false inherit_setting=default");cameraEx=cameraType.getMethod("open",int.class,openType).invoke(null,0,options);
            camera=(Camera)cameraType.getMethod("getNormalCamera").invoke(cameraEx);Camera.Parameters p=camera.getParameters();Camera.Size size=p.getPictureSize();
            event("camera_parameters","drive="+p.get("drive-mode")+" storage="+p.get("storage-fmt")+" jpeg_quality="+p.get("jpeg-quality")+" size="+(size==null?"null":size.width+"x"+size.height));
            event("camera_zoom",zoomValues(p));
            event("camera_capabilities","drive="+p.get("drive-mode-values")+" sizes="+p.get("picture-size-values")
                +" quality="+p.get("jpeg-quality-values")+" matrix="+p.get("rgb-matrix-supported")+" gamma="+p.get("extended-gamma-table-supported"));
            if(productUi()){
                captureSetup=new CaptureSetup(new CameraExposureDriver(camera));
                event("capture_settings_begin","single/jpeg/6000x4000");captureSetup.open();
                p=camera.getParameters();size=p.getPictureSize();event("capture_settings_ready","single/jpeg/6000x4000");
            }
            if(!"single".equals(p.get("drive-mode"))||!"jpeg".equals(p.get("storage-fmt")))throw new IOException("probe requires existing single/JPEG settings");
            if(size==null||size.width!=6000||size.height!=4000)throw new IOException("probe requires existing 6000x4000 setting");
            // Explicit recovery for the recorded 0.6.18 transaction: the native
            // close restored quality50 but force-stop left smart zoom disabled.
            // Run within this proven Activity lifecycle, never app_process.
            if(getIntent().getBooleanExtra("restore_smart_zoom",false)){
                if(p.getJpegQuality()!=50||!"false".equals(p.get("super-resolution-zoom"))||!"false".equals(p.get("precision-zoom"))
                        ||(!"true".equals(p.get("smart-zoom"))&&!"false".equals(p.get("smart-zoom"))))
                    throw new IOException("recovery preconditions differ from recorded original settings");
                if(!"true".equals(p.get("smart-zoom"))){
                    event("recorded_zoom_restore_begin","smart-zoom=true original_run=R54CCEF1");
                    p.set("smart-zoom","true");camera.setParameters(p);
                }
                if(!"true".equals(camera.getParameters().get("smart-zoom")))throw new IOException("smart zoom restore readback failed");
                event("recorded_zoom_restore_done",zoomValues(camera.getParameters()));
            }
            if("develop".equals(phase)&&p.getJpegQuality()!=50)throw new IOException("accepted Fine control requires jpeg-quality=50; current="+p.getJpegQuality());
            if(normalizeZoom){
                for(String key:ZOOM_KEYS){String value=p.get(key);if(value==null)throw new IOException("missing zoom capability "+key);
                    if(!"false".equals(value)){originalZoom.put(key,value);p.set(key,"false");}}
                if(!originalZoom.isEmpty()){
                    event("zoom_disable_begin",originalZoom.toString());camera.setParameters(p);
                    event("zoom_disable_done",zoomValues(camera.getParameters()));
                }
            }
            if(extraFine()){
                String supported=p.get("jpeg-quality-values");event("quality_capabilities","values="+supported);
                if(supported==null||!(","+supported.replace(" ","")+",").contains(",95,"))throw new IOException("EXTRA FINE not advertised; test skipped");
                originalQuality=p.get("jpeg-quality");if(originalQuality==null)throw new IOException("missing original JPEG quality");
                p.setJpegQuality(95);camera.setParameters(p);
                if(camera.getParameters().getJpegQuality()!=95)throw new IOException("EXTRA FINE readback failed");
                event("quality_applied","jpeg_quality=95 original="+originalQuality);
            }
            if(productUi()){
                exposure=new SimpleExposure(new CameraExposureDriver(camera));exposure.open();
                event("automatic_exposure_ready",exposure.state());
            }
            registerCameraListeners();
            if(productUi()){
                telemetry=new CaptureTelemetry(this,ui,new CaptureTelemetry.Observer(){
                    public void changed(CaptureReadout values){
                        if(closed||destroyed||productHud==null)return;
                        productHud.readout=values;
                        if(productHud.mode.equals("live")&&productHud.liveInfoVisible)productHud.update();
                    }
                });
                telemetry.open(cameraEx,camera.getParameters());
            }
            event("preview_request_begin","");camera.setPreviewDisplay(holder);camera.startPreview();event("preview_request_done","");
            ui.postDelayed(new Runnable(){public void run(){if(!closed&&gate.state()==PreJpegGate.State.NEW){
                failure("preview_start_timeout",new IOException("no native preview notification; RAW registration skipped"));close();
            }}},5000);
        }catch(Throwable e){failure("open",e);close();}
    }
    private void onPreviewStarted(){
        event("preview_started","state="+gate.state());
        if(autoPreview!=null&&autoPreview.state()==AutoPreviewGate.State.WAITING){
            ui.post(new Runnable(){public void run(){if(active&&!closed)autoPreview.previewReady();}});return;
        }
        if(previewIsolationProbe()&&isolationBarrier!=null&&active&&!closed
                &&isolationBarrier.state()==PreviewCaptureBarrier.State.WAITING_PREVIEW){
            ui.post(new Runnable(){public void run(){try{
                if(!active||closed)return;
                if(isolationBarrier.previewReady()){
                    event("isolation_capture_ready","baseline_verified=true direct_shutter=true");
                    message("原色已恢复 · 现在可半按对焦、全按拍照");
                    if(productHud!=null){productHud.showPersistentLiveHint("隔离测试：原色已恢复 · 按快门拍一张");productHud.update();}
                }
            }catch(Exception e){isolationBarrier.fail();failure("isolation_unlock",e);}}});return;
        }
        if(active&&!closed&&gate.state()==PreJpegGate.State.REARMING){
            final long shot=gate.shot();
            ui.post(new Runnable(){public void run(){finishRearm(shot);}});return;
        }
        if(!active||closed||gate.state()!=PreJpegGate.State.NEW)return;
        gate.preview();
        // Return to the event loop before issuing the next synchronous vendor RPC.
        ui.post(new Runnable(){public void run(){
            if(!active||closed)return;
            if("preview".equals(phase)){initComplete("camera_only");return;}
            configureSequence();
        }});
    }
    private void initComplete(String scope){
        event("init_done","scope="+scope+" shutter_enabled=false");message("初始化验证完成\n未触发拍摄，正在退出测试。");
        ui.postDelayed(new Runnable(){public void run(){close();finish();}},1500);
    }
    private void configureSequence(){
        try{
            // The A6300 ScalarDaemon rejects RAW mode while electronic/super-
            // resolution zoom is enabled. Its JNI error cleanup then aborts.
            // Check before calling the vendor entry, including init-only tests.
            Camera.Parameters checked=camera.getParameters();event("raw_preconditions",zoomValues(checked));
            for(String key:ZOOM_KEYS)if(!"false".equals(checked.get(key)))throw new IOException("RAW requires disabled "+key);
            gate.registering();
            event("sequence_open_begin","");sequence=sequenceType.getMethod("open",cameraType).invoke(null,cameraEx);event("sequence_open_done","");
            Class<?> optionsType=Class.forName("com.sony.scalar.hardware.CameraSequence$Options");
            Class<?> callback=Class.forName("com.sony.scalar.hardware.CameraSequence$ShutterSequenceCallback");
            Object rawListener=listener(callback.getName(),new InvocationHandler(){public Object invoke(Object proxy,Method method,Object[] args){
                if(method.getDeclaringClass()==Object.class)return objectMethod(proxy,method,args);
                if(method.getName().equals("onShutterSequence"))receiveRaw(args[0]);return null;
            }});
            // Match ImagingAdapterImpl.prepare(): first default registration and
            // unlock. The device wrapper disables the old mode before the second
            // callback registration. Keep both calls visible in the SD journal.
            Object defaults=optionsType.newInstance();
            optionsType.getMethod("setOption",String.class,boolean.class).invoke(defaults,"AUTO_RELEASE_LOCK_ENABLED",true);
            event("default_register_begin","no memory map; automatic release lock; shutter disabled");
            sequenceType.getMethod("setShutterSequenceCallback",callback,optionsType).invoke(sequence,rawListener,defaults);
            gate.defaultRegistered();event("default_register_done","");
            event("default_unlock_begin","");sequenceType.getMethod("setReleaseLock",boolean.class).invoke(sequence,false);
            gate.defaultUnlocked();event("default_unlock_done","");
            if("defaults".equals(initStage)){gate.defaultsComplete();initComplete("default_registration");return;}
            gate.configuring();
            Object options=optionsType.newInstance();
            optionsType.getMethod("setOption",String.class,String.class).invoke(options,"MEMORY_MAP_FILE",mapPath);
            optionsType.getMethod("setOption",String.class,int.class).invoke(options,"EXPOSURE_COUNT",1);
            optionsType.getMethod("setOption",String.class,int.class).invoke(options,"RECORD_COUNT",paired()?2:1);
            optionsType.getMethod("setOption",String.class,boolean.class).invoke(options,"AUTO_RELEASE_LOCK_ENABLED",true);
            event("register_begin","two-stage map="+mapRevision+" exposure=1 record="+(paired()?2:1)+" automatic release lock");
            sequenceType.getMethod("setShutterSequenceCallback",callback,optionsType).invoke(sequence,rawListener,options);event("register_done","");
            event("configured_unlock_begin","");sequenceType.getMethod("setReleaseLock",boolean.class).invoke(sequence,false);event("configured_unlock_done","");
            if(extraFine()){
                int quality=camera.getParameters().getJpegQuality();event("quality_after_register","jpeg_quality="+quality);
                if(quality!=95)throw new IOException("RAW registration changed requested EXTRA FINE quality");
            }
            if(initOnly){gate.registered(true);initComplete("raw_registration");return;}
            if(develops())prepareFilter();
            if(previewIsolationProbe()){
                gate.registered(false);prepareIsolationPreview();return;
            }
            if(automaticNativePreview()&&automaticProfile!=null){
                gate.registered(false);prepareAutomaticPreview();return;
            }
            gate.registered(false);cameraType.getMethod("startDirectShutter").invoke(cameraEx);
            sequenceType.getMethod("setReleaseLock",boolean.class).invoke(sequence,false);
            event("ready","phase="+phase+" continuous="+continuous);message(paired()?(continuous?"按快门拍照 · 连续会话\n成片后半按返回取景，继续拍下一张。\n原片保留 · 24MP · EXTRA FINE":"编码前 LUT 单次测试\n请正常半按对焦、全按拍一张。\n保留原片，两张均为 EXTRA FINE。"):
                ("handoff".equals(phase)?"接口验证 1：RAW 交还相机保存":(extraFine()?"单张 EXTRA FINE 保存验证":"接口验证 2：索尼显影后直接保存"))+"\n请正常半按对焦、全按拍一张。\n本次不添加 LUT。");
            maybeOpenGallery();
        }catch(Throwable e){failure("configure_sequence",e);close();}
    }
    private void prepareIsolationPreview()throws Exception{
        // DirectShutter has never been started in this branch. Keep the native
        // release lock too; a Java key filter is not an exposure barrier.
        sequenceType.getMethod("setReleaseLock",boolean.class).invoke(sequence,true);
        CameraNativePreviewDriver driver=new CameraNativePreviewDriver(cameraEx,camera);previewDriver=driver;
        isolationPreview=new NativePreviewSession(driver);isolationPreview.open();
        java.io.InputStream in=getAssets().open("native-preview/IDENT.PRV");NativePreviewProfile profile;
        try{profile=NativePreviewProfile.read(in,selectedLut.id);}finally{in.close();}
        isolationBarrier=new PreviewCaptureBarrier(new PreviewCaptureBarrier.Driver(){
            public void restoreAndVerify()throws Exception{
                isolationPreview.restore();isolationPreview.requireBaseline();
                event("isolation_baseline_restored","direct_shutter=false native_lock=true quality="+camera.getParameters().getJpegQuality());
            }
            public void restartPreview()throws Exception{
                camera.stopPreview();event("isolation_preview_restart","direct_shutter=false native_lock=true");camera.startPreview();
            }
            public void enableCapture()throws Exception{
                isolationPreview.requireBaseline();
                cameraType.getMethod("startDirectShutter").invoke(cameraEx);
                sequenceType.getMethod("setReleaseLock",boolean.class).invoke(sequence,false);
            }
        });
        isolationPreview.apply(profile);event("isolation_preview_ready","direct_shutter=false native_lock=true profile="+profile.cubeSha256);
        message("隔离测试：近似预览 · 快门关闭\n中央键恢复原色并准备拍摄");
        if(productHud!=null){productHud.showPersistentLiveHint("隔离测试：快门关闭 · 中央键准备拍摄");productHud.update();}
    }
    private void prepareIsolationCapture(){
        if(isolationBarrier==null||isolationBarrier.state()!=PreviewCaptureBarrier.State.PREVIEW_ONLY)return;
        try{
            isolationBarrier.prepare();
            ui.postDelayed(new Runnable(){public void run(){
                if(!closed&&isolationBarrier.state()==PreviewCaptureBarrier.State.WAITING_PREVIEW){
                    isolationBarrier.fail();failure("isolation_preview_timeout",new IOException("native preview did not acknowledge restored baseline"));
                }
            }},5000);
        }catch(Exception e){isolationBarrier.fail();failure("isolation_restore",e);}
    }
    private void watchAutomaticTransition(){
        if(autoPreview==null)return;
        final AutoPreviewGate owner=autoPreview;final long token=owner.generation();
        ui.postDelayed(new Runnable(){public void run(){if(active&&!closed&&autoPreview==owner)owner.timeout(token);}},5000);
    }
    private void prepareAutomaticPreview()throws Exception{
        previewDriver=new CameraNativePreviewDriver(cameraEx,camera);
        isolationPreview=new NativePreviewSession(previewDriver);isolationPreview.open();
        final NativePreviewProfile profile=automaticProfile;
        if(profile==null)throw new IOException("preview profile not prepared");
        final Object owner=cameraEx;
        autoPreview=new AutoPreviewGate(new AutoPreviewGate.Driver(){
            public void lock()throws Exception{sequenceType.getMethod("setReleaseLock",boolean.class).invoke(sequence,true);}
            public void stopDirect(final Runnable done)throws Exception{
                final String type="com.sony.scalar.hardware.CameraEx$DirectShutterStoppedCallback";
                Object callback=listener(type,new InvocationHandler(){public Object invoke(Object p,Method m,Object[] args){
                    if(m.getDeclaringClass()==Object.class)return objectMethod(p,m,args);
                    ui.post(new Runnable(){public void run(){if(!active||closed||cameraEx!=owner)return;
                        event("auto_preview_shutter_stopped","");done.run();}});return null;
                }});
                event("auto_preview_stop_requested","");
                cameraType.getMethod("stopDirectShutter",Class.forName(type)).invoke(cameraEx,callback);
                watchAutomaticTransition();
            }
            public void apply()throws Exception{
                isolationPreview.apply(profile);event("auto_preview_applied","direct_shutter=false native_lock=true lut="+profile.cubeSha256);
            }
            public void restore()throws Exception{
                event("auto_preview_restore_begin","");isolationPreview.restore();isolationPreview.requireBaseline();
                event("auto_preview_restored","direct_shutter=false native_lock=true quality="+camera.getParameters().getJpegQuality());
            }
            public void restartPreview()throws Exception{
                camera.stopPreview();camera.startPreview();watchAutomaticTransition();
            }
            public void enable()throws Exception{
                isolationPreview.requireBaseline();
                cameraType.getMethod("startDirectShutter").invoke(cameraEx);
                sequenceType.getMethod("setReleaseLock",boolean.class).invoke(sequence,false);
                event("auto_preview_capture_ready","baseline_verified=true direct_shutter=true");
            }
        },new AutoPreviewGate.Listener(){
            public void colored(){
                if(gate.state()==PreJpegGate.State.REARMING)completeRearm(gate.shot());
                if(!reviewing&&productHud!=null)productLive();
                event("auto_preview_ready","state="+gate.state());maybeOpenGallery();
            }
            public void ready(){
                if(gate.state()==PreJpegGate.State.READY&&!reviewing&&productHud!=null){productHud.hideLiveHint();productLive();}
            }
            public void failed(Exception error){failure("automatic_preview",error);}
        });
        autoPreview.start();
    }
    private void registerCameraListeners()throws Exception{
        final Object owner=cameraEx;
        for(final String kind:new String[]{"ShutterListener","StoreImageCompleteListener","PreviewStartListener"}){
            final String name="com.sony.scalar.hardware.CameraEx$"+kind;
            Object value=listener(name,new InvocationHandler(){public Object invoke(Object proxy,Method method,Object[] args){
                if(method.getDeclaringClass()==Object.class)return objectMethod(proxy,method,args);
                if(closed||cameraEx!=owner)return null;
                if(kind.equals("ShutterListener")){
                    int status=((Integer)args[0]).intValue();event("shutter","status="+status);
                    // CameraEx.ShutterListener: OK=0, CANCELED=1, ERROR=2.
                    if(status==0)arm("shutter_callback");
                    else if(status==1)cancelShutter();
                    else failure("shutter",new IOException("status "+status));
                }else if(kind.equals("StoreImageCompleteListener")){
                    try{
                        final String directory=(String)args[1].getClass().getField("DirectoryName").get(args[1]);
                        final String file=(String)args[1].getClass().getField("FileName").get(args[1]);
                        final long shot=gate.shot();
                        final long at=SystemClock.elapsedRealtime();event("stored_notification","value="+args[0]+" dir="+directory+" file="+file+" state="+gate.state());
                        work.post(new Runnable(){public void run(){
                            if(closed||shot!=gate.shot()){event("stale_store_ignored","event_shot="+shot+" file="+file);return;}
                            try{if(photoFiles.knownStore(directory,file)){event("duplicate_store_ignored","file="+file);return;}}
                            catch(IOException e){failure("store_path",e);return;}
                            verifyStored(directory,file,at);
                        }});
                    }catch(Throwable e){failure("store_notification",e);}
                }else onPreviewStarted();return null;
            }});
            cameraType.getMethod("set"+kind,Class.forName(name)).invoke(cameraEx,value);
        }
    }
    private void cancelShutter(){
        if(gate.state()!=PreJpegGate.State.ARMED){
            event("shutter_cancel_ignored","state="+gate.state());return;
        }
        try{
            // Match Sony ImagingAdapterImpl.enableNextCapture: release the
            // automatic shutter lock after cancellation, retaining the filter.
            if(autoPreview==null)sequenceType.getMethod("setReleaseLock",boolean.class).invoke(sequence,false);
            if(!gate.cancelShutter())return;
            endProcessing();reviewing=false;clearOriginalReview();
            review.setImageBitmap(null);review.setVisibility(View.INVISIBLE);
            if(active)review.showGraphicsOutput();
            if(productHud!=null){status.setVisibility(View.GONE);productLive();
                productHud.detail="拍摄已取消 · 可重新半按对焦后拍摄";productHud.showLiveHint(productHud.detail);productHud.update();}
            else{status.setText("拍摄已取消 · 可重新拍摄");status.setVisibility(View.VISIBLE);}
            event("shutter_canceled","ready=true filter_preserved=true");
            if(autoPreview!=null)autoPreview.shotCanceled();
            maybeOpenGallery();
        }catch(Throwable e){failure("cancel_recovery",e);}
    }
    private void arm(String origin){
        if(gate.state()==PreJpegGate.State.READY&&autoPreview!=null&&!autoPreview.shotStarted()){
            autoPreview.fail(new IOException("shutter before preview baseline barrier: "+origin));return;
        }
        if(gate.arm()){
        final long shot=gate.shot();processingProgress.begin(shot);pressAt=SystemClock.elapsedRealtime();timingOrigin=origin;journalAtPress=journalWriteMs();
        originalFile=null;gradedFile=null;displayedAt=0;nextReadyAt=0;awaitFirstFrame=false;lutApplied=false;
        overlapThisShot=overlapOriginal&&(!alternateOverlap||shot%2==0);
        event("armed","origin="+origin+" lut_cache_reused="+(shot>1)+" overlap_original="+overlapThisShot);
        ui.post(new Runnable(){public void run(){if(shot==gate.shot()){clearOriginalReview();reviewing=false;review.setVisibility(View.INVISIBLE);review.setImageBitmap(null);}}});
        developEnabledThisShot=productUi()&&getIntent().getBooleanExtra("develop_animation",true)
                &&(!getIntent().getBooleanExtra("develop_animation_alternate",false)||shot%2==1);
        if(productHud!=null){productHud.hideLiveHint();productHud.beginDeveloping(shot,developEnabledThisShot);}
        event("develop_animation_mode","enabled="+developEnabledThisShot);
        processing(CaptureProgress.Stage.CAPTURE,"正在拍摄");
        ui.postDelayed(new Runnable(){public void run(){if(gate.busyShot(shot)){
            endProcessing();
            if(active&&review!=null)try{review.showGraphicsOutput();}catch(Exception e){android.util.Log.e("SonyPreJpeg","timeout_graphics_restore_failed",e);}
            event("timeout","state="+gate.state());message("接口等待超过 25 秒\n请保留当前页面，读取日志。");
        }}},25000);
    }}
    private void receiveRaw(final Object raw){
        try{gate.raw();rawAt=SystemClock.elapsedRealtime();event("raw_received","from_press_ms="+(rawAt-pressAt));workerActive=true;
            processing(develops()?CaptureProgress.Stage.DEVELOP:CaptureProgress.Stage.SAVE,develops()?"正在显影":"正在保存原片");
            // RAW delivery confirms exposure is complete. Follow Sony's
            // progress-layout protocol: draw first, restore graphics 300ms later.
            ui.post(new Runnable(){public void run(){ui.removeCallbacks(restoreGraphics);ui.postDelayed(restoreGraphics,300);}});
            work.post(new Runnable(){public void run(){process(raw);}});
        }catch(Throwable e){
            // A rejected callback still transfers RAW ownership to us. Release
            // it so camera teardown cannot wait forever for an orphaned buffer.
            release(raw);event("unexpected_raw",cause(e).toString());
            if(gate.state()!=PreJpegGate.State.FAILED)failure("raw_state",e);
        }
    }
    private void process(Object raw){Object image=null;boolean rawTransferred=false,imageTransferred=false;
        try{
            if(develops()){
                Object f=filter;event("develop_begin","release_source=true");long begin=SystemClock.elapsedRealtime();
                f.getClass().getMethod("setSource",rawType,boolean.class).invoke(f,raw,true);
                rawTransferred=true;
                if(!((Boolean)f.getClass().getMethod("execute").invoke(f)).booleanValue())throw new IOException("develop returned false");
                image=f.getClass().getMethod("getOutput").invoke(f);if(image==null)throw new IOException("develop returned no image");
                event("develop_done","ms="+(SystemClock.elapsedRealtime()-begin)+" raw_valid="+rawType.getMethod("isValid").invoke(raw));
                f.getClass().getMethod("release").invoke(f);filter=null;
                int w=((Integer)imageType.getMethod("getWidth").invoke(image)).intValue(),h=((Integer)imageType.getMethod("getHeight").invoke(image)).intValue();
                int format=((Integer)imageType.getMethod("getImageFormat").invoke(image)).intValue();
                event("image","width="+w+" height="+h+" format="+format+" "+Native.deviceInfo(image));
                if(w!=6000||h!=4000||format!=0)throw new IOException("unexpected developed image contract");
                if(sourceReference!=null){
                    long referenceAt=SystemClock.elapsedRealtime();Native.devicePreview(image,sourceReference);
                    event("source_reference_snapshot","ms="+(SystemClock.elapsedRealtime()-referenceAt)+" source=developed_before_original_store sample=750x500_nearest");
                    publishDevelopPreview(false);
                }
                if(paired()){
                    gate.savingOriginal();retainedImage=image;storeAt=SystemClock.elapsedRealtime();
                    // Original preservation prepares grading; only the graded
                    // file's store is the final SAVE milestone in the product UI.
                    processing(CaptureProgress.Stage.GRADE,productUi()?"正在准备调色":"正在保存原片");
                    event("store_begin","type=YC role=original release_source=false");
                    sequenceType.getMethod("storeImage",imageType,boolean.class).invoke(sequence,image,false);
                }else{
                    gate.saving();storeAt=SystemClock.elapsedRealtime();event("store_begin","type=YC release_source=true");imageTransferred=true;
                    processing(CaptureProgress.Stage.SAVE,"正在保存原片");
                    sequenceType.getMethod("storeImage",imageType,boolean.class).invoke(sequence,image,true);
                }
            }else{
                gate.saving();storeAt=SystemClock.elapsedRealtime();event("store_begin","type=RAW release_source=true");rawTransferred=true;
                sequenceType.getMethod("storeImage",rawType,boolean.class).invoke(sequence,raw,true);
            }
            event("store_accepted","call_ms="+(SystemClock.elapsedRealtime()-storeAt));
            // Explicit experiment: CreateStream replies from CaptureDevExit
            // after encode/sort. The original completed-file event still gates
            // the second store. Never issue concurrent stores on this sequence.
            if(paired()&&overlapThisShot)applyRetainedLut("store_return_before_file_confirmation");
        }catch(Throwable e){failure("process",e);}
        finally{
            if(!imageTransferred&&image!=retainedImage)release(image);if(!rawTransferred)release(raw);workerActive=false;
            if(!active&&!gate.busy())ui.post(new Runnable(){public void run(){close();}});
        }
    }
    private void verifyStored(String directory,String name,long notifiedAt){
        if(paired()){verifyPairStore(directory,name,notifiedAt);return;}
        try{
        if(gate.state()!=PreJpegGate.State.SAVING)throw new IOException("unexpected store event in "+gate.state());
        photoFiles.confirmStored(directory,name);File file=photoFiles.pollReady(true);
        String expected="handoff".equals(phase)?"ARW":"JPEG";
        if(file==null)throw new IOException("new "+expected+" file not visible: "+directory+name);
        event("verify_begin","expected="+expected+" file="+file+" bytes="+file.length());
        if(!photoFiles.complete(file))throw new IOException("saved "+expected+" structure or payload incomplete: "+file);
        long bytes=file.length();photoFiles.accepted(file);
        gate.saved();long total=SystemClock.elapsedRealtime()-pressAt;
        event("done","phase="+phase+" timing_origin="+timingOrigin+" total_ms="+total+" store_notification_ms="+(notifiedAt-pressAt)+" raw_ms="+(rawAt-pressAt)+" store_ms="+(notifiedAt-storeAt)+" bytes="+bytes+" file="+file);
        ui.post(new Runnable(){public void run(){endProcessing();}});
        message("验证成功："+expected+" 原片已保存\n保存 "+((notifiedAt-pressAt)/1000.0)+" 秒；核验 "+(total/1000.0)+" 秒\n请保留此页，读取测试结果。");
        // Keep the one-shot release lock until this probe exits; no second shot.
        if(!active)ui.post(new Runnable(){public void run(){close();}});
    }catch(Throwable e){failure("verify",e);}}
    private void verifyPairStore(String directory,String name,long notifiedAt){workerActive=true;try{
        boolean original=gate.state()==PreJpegGate.State.ORIGINAL_SAVING;
        if(!original&&gate.state()!=PreJpegGate.State.SAVING)throw new IOException("unexpected pair store in "+gate.state());
        long checkBegin=SystemClock.elapsedRealtime();
        photoFiles.confirmStored(directory,name);File file=photoFiles.pollReady(true);
        long headerBegin=SystemClock.elapsedRealtime();
        if(file==null||!PhotoFiles.jpegHeader(file,6000,4000))throw new IOException("completed pair JPEG header missing or invalid: "+directory+name);
        long checkedAt=SystemClock.elapsedRealtime();
        photoFiles.accepted(file);
        event(original?"original_saved":"graded_saved","file="+file+" bytes="+file.length()+" from_press_ms="+(notifiedAt-pressAt)+" store_ms="+(notifiedAt-storeAt)
                +" notification_to_check_ms="+(checkBegin-notifiedAt)+" file_lookup_ms="+(headerBegin-checkBegin)+" header_ms="+(checkedAt-headerBegin));
        if(original){
            originalFile=file;gate.originalSaved();
            if(!lutApplied)applyRetainedLut("original_file_confirmed");
            gate.savingGraded();storeAt=SystemClock.elapsedRealtime();event("store_begin","type=YC role=graded release_source=true");
            processing(CaptureProgress.Stage.SAVE,"正在保存成片");
            Object image=retainedImage;retainedImage=null;
            sequenceType.getMethod("storeImage",imageType,boolean.class).invoke(sequence,image,true);
            event("store_accepted","role=graded call_ms="+(SystemClock.elapsedRealtime()-storeAt));
        }else{
            if(originalFile==null||originalFile.equals(file))throw new IOException("graded save did not preserve distinct original");
            gradedFile=file;gate.saved();event("pair_saved","shutter_to_saved_ms="+(notifiedAt-pressAt));
            ui.post(new Runnable(){public void run(){
                if(!active||destroyed){close();return;}
                try{endProcessing();long start=SystemClock.elapsedRealtime();camera.stopPreview();long stopped=SystemClock.elapsedRealtime();
                    review.setImageBitmap(lutReview);reviewing=true;awaitFirstFrame=true;review.setVisibility(View.VISIBLE);review.invalidate();
                    android.util.Log.i("SonyPreJpeg","review_setup stop_preview_ms="+(stopped-start)+" view_ms="+(SystemClock.elapsedRealtime()-stopped));
                }
                catch(Throwable e){failure("show_review",e);}
            }});
        }
    }catch(Throwable e){failure("pair_store",e);}
    finally{workerActive=false;if(!active&&!gate.busy())ui.post(new Runnable(){public void run(){close();}});}}
    private void applyRetainedLut(String trigger)throws Exception{
        if(lutApplied)throw new IllegalStateException("LUT applied twice");
        if(retainedImage==null||!((Boolean)imageType.getMethod("isValid").invoke(retainedImage)).booleanValue())
            throw new IOException("retained YC invalid before LUT");
        event("lut_begin","trigger="+trigger+" original_confirmed="+(originalFile!=null)+" no_jpeg_decode=true");
        long start=SystemClock.elapsedRealtime();processing(CaptureProgress.Stage.GRADE,"正在应用 LUT");
        String stats=Native.deviceLutReview(retainedImage,lutCache,lutReview);lutApplied=true;
        event("lut_done","wall_ms="+(SystemClock.elapsedRealtime()-start)+" "+stats);
        publishDevelopPreview(true);
    }
    // Runs only after the native preview writer has completed, on the same
    // worker. UI receives an owned copy; it never races native buffer reuse.
    private void publishDevelopPreview(final boolean graded){
        if(!developEnabledThisShot)return;
        final long shot=gate.shot();long start=SystemClock.elapsedRealtime();
        DevelopingPreview.Source ownedSource=null;Bitmap ownedBitmap=null;boolean posted=false;
        try{
            if(graded)ownedBitmap=DevelopingPreview.thumbnail(lutReview);
            else ownedSource=DevelopingPreview.createSource(sourceReference);
            final DevelopingPreview.Source source=ownedSource;final Bitmap bitmap=ownedBitmap;
            posted=ui.post(new Runnable(){public void run(){
                if(!active||destroyed||closed||shot!=gate.shot()||productHud==null){
                    if(source!=null)source.release();DevelopingPreview.recycle(bitmap);return;
                }
                if(graded)productHud.gradedDeveloping(shot,bitmap);else productHud.sourceDeveloping(shot,source);
            }});
            event("develop_preview_copy","role="+(graded?"graded":"source")+" ms="+(SystemClock.elapsedRealtime()-start)+" posted="+posted);
        }catch(RuntimeException e){developEnabledThisShot=false;event("develop_preview_disabled",e.toString());}
        catch(OutOfMemoryError e){developEnabledThisShot=false;event("develop_preview_disabled",e.toString());}
        finally{if(!posted){if(ownedSource!=null)ownedSource.release();DevelopingPreview.recycle(ownedBitmap);}}
    }
    private void verifyPairPayloads(){workerActive=true;try{
        long start=SystemClock.elapsedRealtime();
        if(!PhotoFiles.completeJpeg(originalFile)||!PhotoFiles.completeJpeg(gradedFile))throw new IOException("paired JPEG payload incomplete");
        event("pair_payloads_verified","after_display_ms="+(SystemClock.elapsedRealtime()-start)+" both_complete=true");
        if(sourceReference!=null){
            saveReference(sourceReference,"SOURCE.PNG");saveReference(lutReview,"GRADED.PNG");
            event("source_reference_ready","original="+originalFile+" graded="+gradedFile);
            // Sony refuses a second open of the SD journal while its writer
            // remains open. Export only this finished, single-shot journal.
            closeJournal();
            try{
                File exported=RunFiles.exportAdb(run,originalFile);
                event("source_reference_exported","directory="+exported+" original="+originalFile+" graded="+gradedFile);
                message("诊断完成 · 已保存原片与 LUT 成片\n请保留此页，正在核对原片。");
            }catch(IOException e){
                android.util.Log.e("SonyPreJpeg","diagnostic_export_failed; photos already saved",e);
                event("diagnostic_export_failed",e.toString());
                message("原片与 LUT 成片均已保存\n诊断导出失败，可稍后重试。");
            }
        }
    }catch(Throwable e){failure("pair_payloads",e);}
    finally{workerActive=false;if(!active)ui.post(new Runnable(){public void run(){close();}});}}

    private void saveReference(Bitmap bitmap,String name)throws IOException{
        FileOutputStream out=new FileOutputStream(RunFiles.file(run,name));
        try{if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("reference PNG failed");}
        finally{out.close();}
    }
    private void prepareFilter()throws Exception{
        if(filter!=null)throw new IllegalStateException("previous develop filter retained");
        event("filter_setup_begin","DEFAULT_DEVELOP");
        filter=Class.forName("com.sony.scalar.hardware.CameraSequence$DefaultDevelopFilter").newInstance();
        if(!((Boolean)filter.getClass().getMethod("isSupported").invoke(filter)).booleanValue())throw new IOException("DEFAULT_DEVELOP unsupported");
        event("filter_setup_done","supported=true");
    }
    private void checkReviewClear(){
        try{
            // Reproduce the old Android empty-bitmap contract with a plain
            // ImageView; catch the old fit failure before the system handler.
            ImageView legacy=new ImageView(this);legacy.setImageBitmap(null);
            android.graphics.drawable.Drawable empty=legacy.getDrawable();
            if(empty==null)throw new IllegalStateException("legacy Android empty drawable contract differs");
            int w=empty.getIntrinsicWidth(),h=empty.getIntrinsicHeight();boolean rejected=false;
            try{ReviewGeometry.fit(w,h,review.getWidth(),review.getHeight(),1);}
            catch(IllegalArgumentException expected){rejected=true;event("legacy_review_clear_reproduced","width="+w+" height="+h+" exception="+expected);}
            if(!rejected)throw new IllegalStateException("old null-bitmap fit did not fail");
            for(int i=0;i<8;i++){
                review.setImageBitmap(lutReview);review.setImageBitmap(null);
                if(review.getDrawable()!=null)throw new IllegalStateException("clear retained a drawable");
            }
            review.setImageBitmap(lutReview);
            event("review_clear_check_passed","cycles=8 camera_opened=false");message("显示组件检查通过 · 等待下一阶段");
        }catch(Throwable e){failure("review_clear_check",e);}
    }
    private void rearm(final long shot){
        if(!active||destroyed||closed||!continuous)return;
        if(!gate.beginRearm(shot))return;
        try{
            if(workerActive||retainedImage!=null||originalFile==null||gradedFile==null||originalFile.equals(gradedFile))
                throw new IllegalStateException("cannot rearm before distinct pair completes and buffers transfer");
            if(camera.getParameters().getJpegQuality()!=95)throw new IOException("EXTRA FINE changed between shots");
            prepareFilter();rearmAt=SystemClock.elapsedRealtime();
            event("next_preview_request","from_press_ms="+(rearmAt-pressAt)+" cache_preserved=true");
            // Keep the result visible while the native live view resumes behind
            // it. Only its PreviewStart notification allows the next release.
            camera.startPreview();
            ui.postDelayed(new Runnable(){public void run(){
                if(!closed&&gate.shot()==shot&&gate.state()==PreJpegGate.State.REARMING)
                    failure("next_preview_timeout",new IOException("Sony preview did not report ready"));
            }},5000);
        }catch(Throwable e){failure("rearm",e);}
    }
    private void finishRearm(long shot){
        if(!active||closed||destroyed||finishingRearm||gate.shot()!=shot||gate.state()!=PreJpegGate.State.REARMING)return;
        finishingRearm=true;
        try{
            if(autoPreview!=null){autoPreview.shotFinished();return;}
            sequenceType.getMethod("setReleaseLock",boolean.class).invoke(sequence,false);
            completeRearm(shot);
        }catch(Throwable e){failure("unlock_next_shot",e);}
        finally{finishingRearm=false;}
    }
    private void completeRearm(long shot){
        try{
            if(!gate.rearmed(shot))throw new IllegalStateException("capture state changed while unlocking");
            nextReadyAt=SystemClock.elapsedRealtime();
            event("next_ready","shutter_to_ready_ms="+(nextReadyAt-pressAt)+" displayed_ms="+(displayedAt-pressAt)
                +" display_to_ready_ms="+(nextReadyAt-displayedAt)+" preview_resume_ms="+(nextReadyAt-rearmAt)+" cache_preserved=true");
            savedStatus("成片 "+seconds(displayedAt-pressAt)+" 秒 · 下张可拍 "+seconds(nextReadyAt-pressAt)+" 秒");
            if(productUi())recordPair();
            maybeOpenGallery();
        }catch(Throwable e){failure("unlock_next_shot",e);}
    }
    private void showViewfinder(){
        if(!reviewing||gate.state()!=PreJpegGate.State.READY)return;
        reviewing=false;review.setVisibility(View.INVISIBLE);review.setImageBitmap(null);
        ui.removeCallbacks(hideReviewStatus);status.setVisibility(View.VISIBLE);status.setText("按快门拍照 · 原片保留 · EXTRA FINE");
        clearOriginalReview();if(productHud!=null){status.setVisibility(View.GONE);productLive();}
        event("viewfinder_revealed","since_ready_ms="+(SystemClock.elapsedRealtime()-nextReadyAt));
    }
    private void productLive(){
        if(productHud==null)return;
        boolean entering=!productHud.mode.equals("live");
        productHud.setVisibility(View.VISIBLE);productHud.mode="live";
        productHud.liveExposure=exposure==null?"自动曝光":exposure.label();
        productHud.liveCompensation=exposure==null?"-- EV":exposure.compensationLabel();
        productHud.detail="自动曝光    "+productHud.liveExposure;
        productHud.footer="";productHud.progress="";
        if(previewIsolationProbe()&&isolationBarrier!=null)
            productHud.showPersistentLiveHint(isolationBarrier.captureReady()
                ?"隔离测试：原色已恢复 · 按快门拍一张"
                :"隔离测试：快门关闭 · 中央键准备拍摄");
        else if(autoPreview!=null){
            if(autoPreview.state()==AutoPreviewGate.State.PREVIEW)
                productHud.showPersistentLiveHint("近似色彩预览 · 半按对焦，按下拍摄");
            else if(autoPreview.state()==AutoPreviewGate.State.WAITING||autoPreview.state()==AutoPreviewGate.State.RESTORING)
                productHud.showPersistentLiveHint("正在准备拍摄 · 请保持半按");
            else if(autoPreview.ready())productHud.showLiveHint("已恢复拍摄原色 · 按下快门拍摄");
        }
        else if(entering&&productHud.liveInfoVisible)productHud.showLiveHint("拨轮调亮度 · 中央归零 · ↑ 隐藏信息");
        productHud.update();
    }
    private void adjustExposure(int direction,boolean reset){
        if(!active||closed||reviewing||workerActive||gate.state()!=PreJpegGate.State.READY||exposure==null)return;
        try{exposure.adjust(direction,reset);event("exposure_adjusted",exposure.state());productLive();
            if(!productHud.liveInfoVisible)productHud.showLiveHint(productHud.liveExposure);}
        catch(Exception e){failure("exposure_adjust",e);}
    }
    private void clearOriginalReview(){showingOriginal=false;}
    private void maybeOpenGallery(){
        if(!playbackRequested||!productUi()||!active||closed||workerActive||pendingReceipts!=0||gate.state()!=PreJpegGate.State.READY)return;
        playbackRequested=false;
        event("playback_handoff","all_saves_complete=true receipt_writes=0");
        close(); // Complete vendor release before asking the library to open history.
        setResult(RESULT_OK,new android.content.Intent().putExtra("open_gallery",true).putExtra("page_offset",playbackOffset));finish();
    }
    private void compareOriginal(boolean original){
        if(!reviewing||gate.state()!=PreJpegGate.State.READY||productHud==null)return;
        long begin=SystemClock.elapsedRealtime();
        if(sourceReference==null)throw new IllegalStateException("original display cache missing");
        showingOriginal=original;review.setImageBitmap(original?sourceReference:lutReview);
        productHud.setVisibility(View.VISIBLE);productHud.original=original;productHud.detail="";productHud.update();
        event("review_compare","role="+(original?"original":"graded")+" switch_ms="+(SystemClock.elapsedRealtime()-begin)+" jpeg_decode=false");
    }
    private void recordPair(){
        if(selectedLut==null||originalFile==null||gradedFile==null||recordedPairShot==gate.shot())return;
        recordedPairShot=gate.shot();
        final File original=originalFile,graded=gradedFile;final long display=Math.max(0,displayedAt-pressAt),ready=Math.max(0,nextReadyAt-pressAt);
        final LutLibrary.Entry lut=selectedLut;pendingReceipts++;
        work.post(new Runnable(){public void run(){try{
            new PhotoCatalog(ProductFiles.photos(),sd).save(original,graded,lut.name,lut.size,display,ready,ReviewLoader.capturedAt(original));
            event("pair_catalogued","original="+original+" graded="+graded);
        }catch(final IOException e){event("catalog_failed",e.toString());ui.post(new Runnable(){public void run(){if(!destroyed&&productHud!=null){productHud.detail="照片已保存在 DCIM；回看记录保存失败";productHud.update();}}});}
        finally{ui.post(new Runnable(){public void run(){pendingReceipts--;maybeOpenGallery();exitProductProcess();}});}}});
    }
    private void exitProductProcess(){
        // No camera handles or receipt writes may outlive the isolated process.
        // Finishing it makes the next entry satisfy the established fresh-JNI guard.
        if(productUi()&&destroyed&&closed&&pendingReceipts==0){
            if(thread!=null)thread.quit();
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }
    private void release(Object value){if(value!=null)try{
        Method valid=value.getClass().getMethod("isValid");if(((Boolean)valid.invoke(value)).booleanValue())value.getClass().getMethod("release").invoke(value);
    }catch(Throwable e){event("release_error",cause(e).toString());}}
    private static String zoomValues(Camera.Parameters p){StringBuilder s=new StringBuilder();
        for(String key:ZOOM_KEYS)s.append(key).append('=').append(p.get(key)).append(' ');
        return s.append("zoom=").append(p.get("zoom")).toString();}
    private void close(){if(closed||workerActive||gate.busy())return;
        if(productUi())recordPair();
        closed=true;
        if(autoPreview!=null)autoPreview.close();
        if(isolationBarrier!=null)isolationBarrier.fail();
        if(isolationPreview!=null)try{isolationPreview.close();event("isolation_closed","baseline_restored=true");}
            catch(Exception e){event("isolation_restore_error",cause(e).toString());}
        if(productHud!=null)productHud.endDeveloping();
        if(telemetry!=null){telemetry.close();telemetry=null;}
        release(retainedImage);retainedImage=null;
        if(filter!=null)try{filter.getClass().getMethod("release").invoke(filter);}catch(Throwable e){event("filter_release_error",cause(e).toString());}filter=null;
        for(Object value:new Object[]{sequence,cameraEx})if(value!=null){
            if(value==cameraEx&&exposure!=null){try{
                exposure.close();event("exposure_restored","original settings readback matched");exposure=null;
            }catch(Exception e){event("exposure_restore_error",cause(e).toString());}}
            if(value==cameraEx&&originalQuality!=null){try{
                Camera.Parameters restore=camera.getParameters();restore.set("jpeg-quality",originalQuality);camera.setParameters(restore);
                event("quality_restored","jpeg_quality="+camera.getParameters().get("jpeg-quality"));originalQuality=null;
            }catch(Throwable e){event("quality_restore_error",cause(e).toString());}}
            if(value==cameraEx&&!originalZoom.isEmpty()){
                try{
                    Camera.Parameters restore=camera.getParameters();for(Map.Entry<String,String> entry:originalZoom.entrySet())restore.set(entry.getKey(),entry.getValue());
                    event("zoom_restore_begin",originalZoom.toString());camera.setParameters(restore);
                    event("zoom_restore_done",zoomValues(camera.getParameters()));originalZoom.clear();
                }catch(Throwable e){event("zoom_restore_error",cause(e).toString());}
            }
            if(value==cameraEx&&captureSetup!=null)try{
                captureSetup.close();captureSetup=null;event("capture_settings_restored","original settings readback matched");
            }catch(Exception e){event("capture_settings_restore_error",cause(e).toString());}
            try{
            event("release_begin",value.getClass().getName());value.getClass().getMethod("release").invoke(value);event("release_done",value.getClass().getName());
            if(value==cameraEx&&previewDriver!=null)previewDriver.cameraReleased();
            }catch(Throwable e){event("close_error",cause(e).toString());}
        }
        sequence=null;cameraEx=null;camera=null;
        if(lutCache!=0){Native.releaseDeviceLut(lutCache);lutCache=0;}
        if(lutReview!=null){if(review!=null)review.setImageBitmap(null);lutReview.recycle();lutReview=null;}
        clearOriginalReview();
        if(sourceReference!=null){sourceReference.recycle();sourceReference=null;}
        event("closed","journal_write_ms="+journalWriteMs());closeJournal();if(destroyed&&thread!=null&&pendingReceipts==0)thread.quit();
        exitProductProcess();
    }
    @Override protected void onResume(){super.onResume();active=true;
        if(closed&&startupFailure==null){finish();return;}
        if(closed&&startupFailure!=null){
            if(review!=null)try{review.startDisplayTracking();review.showGraphicsOutput();}
            catch(Exception e){android.util.Log.e("SonyPreJpeg","error_display_resume_failed",e);}
            return;
        }
        if(productUi())SonyAppPolicy.register(this);
        if(review!=null)try{review.startDisplayTracking();}catch(Exception e){failure("display_geometry",e);close();return;}
        open();
    }
    @Override protected void onPause(){active=false;ui.removeCallbacks(hideReviewStatus);ui.removeCallbacks(processingTick);ui.removeCallbacks(restoreGraphics);if(review!=null)review.stopDisplayTracking();close();super.onPause();}
    @Override protected void onDestroy(){destroyed=true;close();if(closed&&thread!=null&&pendingReceipts==0)thread.quit();
        if(Thread.getDefaultUncaughtExceptionHandler()==exceptionHandler)Thread.setDefaultUncaughtExceptionHandler(previousExceptionHandler);
        super.onDestroy();exitProductProcess();}
    @Override public void onBackPressed(){if(!workerActive&&!gate.busy())finish();}
    public void surfaceCreated(SurfaceHolder h){open();}public void surfaceChanged(SurfaceHolder h,int f,int w,int height){open();}public void surfaceDestroyed(SurfaceHolder h){close();}
    @Override public boolean dispatchKeyEvent(KeyEvent e){int key=e.getKeyCode(),scan=e.getScanCode();
        if(automaticNativePreview()&&(UiKeys.shutter(e)||key==KeyEvent.KEYCODE_FOCUS||key==772||scan==772||key==774||scan==774)){
            if(autoPreview==null){
                if(gate.state()!=PreJpegGate.State.READY)return true;
            }else{
                event("auto_preview_key","key="+key+" scan="+scan+" action="+e.getAction()+" state="+autoPreview.state());
                boolean half=key==516||scan==516||key==KeyEvent.KEYCODE_FOCUS;
                boolean releaseHalf=key==772||scan==772||(half&&e.getAction()==KeyEvent.ACTION_UP);
                if(releaseHalf){autoPreview.release();return true;}
                if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0&&(half||key==518||scan==518||key==KeyEvent.KEYCODE_CAMERA)){
                    if(gate.state()==PreJpegGate.State.READY){
                        showViewfinder();autoPreview.press();productLive();
                    }
                }
                if(!autoPreview.ready())return true;
            }
        }
        if(previewIsolationProbe()&&(isolationBarrier==null||!isolationBarrier.captureReady())){
            if(UiKeys.shutter(e)){
                if(e.getAction()==KeyEvent.ACTION_DOWN)event("isolation_shutter_blocked","key="+key+" direct_shutter=false");
                return true;
            }
            if(UiKeys.key(e)==UiKeys.OK){
                if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0)prepareIsolationCapture();return true;
            }
        }
        if(productUi()){
            int action=UiKeys.key(e);
            if(action==UiKeys.PLAY){
                if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0){
                    event("playback_key","key="+key+" scan="+scan+" state="+gate.state());
                    playbackOffset=0;playbackRequested=true;maybeOpenGallery();
                }
                return true;
            }
            int dial=UiKeys.dial(e);
            if(reviewing&&(dial!=0||action==UiKeys.LEFT||action==UiKeys.RIGHT||action==UiKeys.OK||action==UiKeys.UP||action==UiKeys.DOWN)){
                if(e.getAction()==KeyEvent.ACTION_DOWN&&(dial!=0||e.getRepeatCount()==0)){
                    int turn=dial!=0?dial:action==UiKeys.RIGHT?1:action==UiKeys.LEFT?-1:0;
                    if(turn<0){playbackOffset=1;playbackRequested=true;maybeOpenGallery();}
                    else if(action==UiKeys.OK)compareOriginal(!showingOriginal);
                    else if(action==UiKeys.UP&&gate.state()==PreJpegGate.State.READY)
                        productHud.setVisibility(productHud.getVisibility()==View.VISIBLE?View.INVISIBLE:View.VISIBLE);
                }
                return true;
            }
            if(action==UiKeys.UP){
                if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0&&gate.state()==PreJpegGate.State.READY){
                    productHud.liveInfoVisible=!productHud.liveInfoVisible;productHud.hideLiveHint();productHud.update();
                    event("viewfinder_info","visible="+productHud.liveInfoVisible);
                }
                return true;
            }
            if(dial!=0){
                if(e.getAction()==KeyEvent.ACTION_DOWN)adjustExposure(dial,false);
                return true;
            }
            if(action==UiKeys.LEFT||action==UiKeys.RIGHT||action==UiKeys.OK){
                if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0){
                    adjustExposure(action==UiKeys.LEFT?-1:1,action==UiKeys.OK);
                }
                return true;
            }
        }
        if(key==KeyEvent.KEYCODE_BACK||key==KeyEvent.KEYCODE_MENU||scan==514){if(e.getAction()==KeyEvent.ACTION_UP)onBackPressed();return true;}
        boolean shutter=scan==516||scan==517||scan==518||key==516||key==517||key==518||key==KeyEvent.KEYCODE_CAMERA;
        if(shutter){
            if(e.getAction()==KeyEvent.ACTION_UP)return super.dispatchKeyEvent(e);
            if(gate.state()!=PreJpegGate.State.READY)return true;
            if(continuous)showViewfinder();
            if(productHud!=null)productHud.hideLiveHint();
            if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0&&(scan==518||key==518||key==KeyEvent.KEYCODE_CAMERA))arm("physical_full_press");
        }
        return super.dispatchKeyEvent(e);
    }
}
