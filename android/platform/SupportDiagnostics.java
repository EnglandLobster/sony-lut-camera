package io.github.englandlobster.lutcamera;

import android.content.Context;
import android.os.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Per-process capture/UI recorder. No camera ownership, root, logcat or network. */
final class SupportDiagnostics {
    private static DiagnosticStore internal,sd;
    private static File internalDir,sdDir;
    private static String session,role,metadata,header,bootId;
    private static long sequence;
    private static String internalError="",sdError="";
    /** Buffered events reach the journals at most this long after they happen. */
    static final long FLUSH_DELAY_MS=1000;
    private static Handler flusher;
    private static boolean flushScheduled;
    private static final Runnable FLUSH=new Runnable(){public void run(){flushPending();}};
    static synchronized void initialize(Context context){
        if(session!=null)return;session=UUID.randomUUID().toString();role="UI";
        try{FileInputStream in=new FileInputStream("/proc/self/cmdline");byte[] b=new byte[256];int n;
            try{n=in.read(b);}finally{in.close();}if(n>0&&new String(b,0,n,"UTF-8").contains(":capture"))role="CAP";
        }catch(Exception e){android.util.Log.w("LutSupport","process role unavailable",e);}
        internalDir=new File(context.getFilesDir(),"diagnostics");sdDir=new File(Environment.getExternalStorageDirectory(),"SONYLUT/DIAG/LOGS");
        String version="unknown";try{android.content.pm.PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);version=info.versionName+" code="+info.versionCode;}catch(Exception ignored){}
        StringBuilder details=new StringBuilder("app="+version+" model="+Build.MODEL+" sdk="+Build.VERSION.SDK_INT+" release="+Build.VERSION.RELEASE+" abi="+Build.CPU_ABI+" max_heap="+Runtime.getRuntime().maxMemory());
        try{
            Class<?> props=Class.forName("com.sony.scalar.sysutil.ScalarProperties");
            for(String field:new String[]{"PROP_MODEL_NAME","PROP_VERSION_PLATFORM"})try{
                details.append(' ').append(field).append('=').append(props.getMethod("getString",String.class).invoke(null,props.getField(field).get(null)));
            }catch(Exception absent){details.append(' ').append(field).append("=unavailable");}
            try{details.append(" firmware=").append(props.getMethod("getFirmwareVersion").invoke(null));}catch(Exception ignored){}
        }catch(Throwable unavailable){details.append(" sony_properties=").append(unavailable.getClass().getSimpleName());}
        try{BufferedReader boot=new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"));try{bootId=boot.readLine();details.append(" boot_id=").append(bootId);}finally{boot.close();}}catch(Exception ignored){}
        metadata=details.toString();header=line("session_header",metadata);
        HandlerThread thread=new HandlerThread("LutDiagnostics");thread.start();flusher=new Handler(thread.getLooper());
        // The SD card is the journal. Private storage is the camera's settings RAM
        // disk (about 800 KB free on A6300 2.01), backed up at every standby and
        // restored at a cold boot, so it keeps only a small fallback when SD fails.
        String prior="previous=unavailable";
        try{sd=new DiagnosticStore(sdDir,role,header);prior=sd.previousSession(bootId);DiagnosticStore.remove(internalDir,role);}
        catch(Exception e){sdError=e.toString();openFallback();if(internal!=null)prior=internal.previousSession(bootId);}
        event("process_start","role="+role+" pid="+android.os.Process.myPid()+" internal="+(internal!=null)+" sd="+(sd!=null)+" "+memory()+" internal_free="+free(internalDir)+" sd_free="+free(Environment.getExternalStorageDirectory())+" "+powerSettings());
        event("previous_session",prior);
        registerPowerEvents(context.getApplicationContext());
        final Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler(){public void uncaughtException(Thread thread,Throwable error){
            try{failure("uncaught "+thread.getName(),error);}catch(Throwable ignored){}
            close();
            if(previous!=null)previous.uncaughtException(thread,error);
            else{android.os.Process.killProcess(android.os.Process.myPid());System.exit(10);}
        }});
    }
    private static String line(String event,String detail){return "{\"schema\":1,\"session\":"+DiagnosticText.quote(session)+",\"seq\":"+(sequence++)
        +",\"wall_ms\":"+System.currentTimeMillis()+",\"uptime_ms\":"+SystemClock.elapsedRealtime()+",\"role\":"+DiagnosticText.quote(role)
        +",\"event\":"+DiagnosticText.quote(DiagnosticText.clean(event))+",\"detail\":"+DiagnosticText.quote(DiagnosticText.clean(detail))+"}";}
    static synchronized void event(String event,String detail){
        try{writeEvent(event,detail);}catch(Throwable ignored){/* Logging must not fail the camera operation. */}
    }
    private static void writeEvent(String event,String detail){
        if(session==null)return;String line=line(event,detail);
        boolean durable=event.equals("failed")||event.equals("uncaught")||event.equals("closed")||event.equals("process_start")||event.equals("camera_open_begin")||event.equals("sequence_open_begin")||event.equals("native_exit_requested")||event.equals("capture_interrupted")||event.equals("store_wait")||event.equals("recovery_snapshot_failed")||event.equals("system_suspend");
        if(sd!=null)try{sd.append(line,durable);}catch(Exception e){sdError=e.toString();try{sd.close();}catch(Exception ignored){}sd=null;openFallback();}
        if(sd==null&&internal!=null)try{internal.append(line,durable);}catch(Exception e){internalError=e.toString();try{internal.close();}catch(Exception ignored){}internal=null;}
        if(!flushScheduled&&flusher!=null&&((internal!=null&&internal.hasPending())||(sd!=null&&sd.hasPending())))flushScheduled=flusher.postDelayed(FLUSH,FLUSH_DELAY_MS);
    }
    /** Private-storage journal, opened only once the SD journal is unavailable. */
    private static void openFallback(){
        if(internal!=null||internalDir==null||header==null)return;
        try{internal=new DiagnosticStore(internalDir,role,header,DiagnosticStore.FALLBACK_LIMIT,DiagnosticStore.FALLBACK_COUNT);
            internal.append(line("sd_journal_unavailable",DiagnosticText.clean(sdError)),true);}
        catch(Exception e){internalError=e.toString();internal=null;}
    }
    /** Auto power-off delay as the camera reports it; Sony-only, so read reflectively. */
    static String powerSettings(){
        try{Object value=Class.forName("com.sony.scalar.sysutil.didep.Settings").getMethod("getAutoPowerOffTime").invoke(null);return "auto_power_off="+value;}
        catch(Throwable unavailable){return "auto_power_off=unavailable";}
    }
    static final String SONY_SUSPEND="com.android.server.SyncAndroidService.SUSPEND",SONY_RESUME="com.android.server.SyncAndroidService.RESUME";
    /** Records standby and wake as each process sees them; standby writes everything buffered first. */
    private static void registerPowerEvents(Context context){
        try{
            android.content.IntentFilter filter=new android.content.IntentFilter();
            for(String action:new String[]{SONY_SUSPEND,SONY_RESUME,android.content.Intent.ACTION_SCREEN_OFF,android.content.Intent.ACTION_SCREEN_ON})filter.addAction(action);
            context.registerReceiver(new android.content.BroadcastReceiver(){@Override public void onReceive(Context c,android.content.Intent intent){
                String action=intent.getAction();
                String name=SONY_SUSPEND.equals(action)?"system_suspend":SONY_RESUME.equals(action)?"system_resume":android.content.Intent.ACTION_SCREEN_OFF.equals(action)?"screen_off":"screen_on";
                event(name,powerSettings());
            }},filter);
        }catch(Throwable error){event("power_events_unavailable",error.getClass().getSimpleName());}
    }
    /** Activity lifecycle in the journal, so a pause from standby is told apart from a user exit. */
    static void lifecycle(android.app.Activity activity,String stage){
        event("activity_"+stage,activity.getClass().getSimpleName()+" finishing="+activity.isFinishing());
    }
    static synchronized void flushPending(){
        flushScheduled=false;
        if(internal!=null)try{internal.flush(false);}catch(Exception e){internalError=e.toString();try{internal.close();}catch(Exception ignored){}internal=null;}
        if(sd!=null)try{sd.flush(false);}catch(Exception e){sdError=e.toString();try{sd.close();}catch(Exception ignored){}sd=null;openFallback();}
        // Still buffered only when the SD journal was busy (being exported).
        if(flusher!=null&&((internal!=null&&internal.hasPending())||(sd!=null&&sd.hasPending())))flushScheduled=flusher.postDelayed(FLUSH,FLUSH_DELAY_MS);
    }
    static void failure(String where,Throwable error){
        try{writeFailure(where,error);}catch(Throwable ignored){event("failed","error details unavailable");}
    }
    private static void writeFailure(String where,Throwable error){
        // Bounded walk avoids allocating an unbounded stack trace during OOM.
        StringBuilder out=new StringBuilder(where+" "+memory());Throwable cause=error;
        for(int depth=0;cause!=null&&depth<5;depth++){
            out.append('\n').append(cause.getClass().getName()).append(": ").append(DiagnosticText.clean(cause.getMessage()));
            StackTraceElement[] frames=cause.getStackTrace();for(int i=0;i<frames.length&&i<24;i++)out.append("\n at ").append(frames[i]);
            if(cause.getCause()==cause)break;cause=cause.getCause();
        }event("failed",out.toString());
    }
    private static String memory(){Runtime r=Runtime.getRuntime();return "heap_used="+(r.totalMemory()-r.freeMemory())+" heap_max="+r.maxMemory()+" native_heap="+Debug.getNativeHeapAllocatedSize();}
    /** Write buffered events before intentional process exit, including fatal Java errors. */
    static synchronized void close(){
        if(internal!=null)try{internal.close();}catch(Exception e){internalError=e.toString();}finally{internal=null;}
        if(sd!=null)try{sd.close();}catch(Exception e){sdError=e.toString();}finally{sd=null;}
    }
    private static String free(File dir){try{StatFs stat=new StatFs(dir.getPath());return Long.toString((long)stat.getAvailableBlocks()*stat.getBlockSize());}catch(Exception e){return "unavailable";}}
    static synchronized File exportReport()throws IOException {
        if(internalDir==null)throw new IOException("diagnostic recorder unavailable");
        event("export_begin",memory());
        String summary="LUT Camera diagnostics / schema 1\n"+metadata+"\n"+memory()+"\ninternal_free="+free(internalDir)+" sd_free="+free(Environment.getExternalStorageDirectory())
            +"\ninternal_writer="+(internal!=null)+" sd_writer="+(sd!=null)+"\ninternal_error="+DiagnosticText.clean(internalError)+"\nsd_error="+DiagnosticText.clean(sdError)
            +"\ncapture_recovery_snapshot=\n"+CaptureRecoveryFence.snapshot(internalDir.getParentFile())+"\n\nJSON lines: group by session; deduplicate mirrored events by session + seq.\n"
            +"wall_ms may be wrong if the camera clock is unset. uptime_ms is monotonic within one boot.\n"
            +"A capture session without closed may have been interrupted; it is not proof of a crash.\n"
            +"No photos, LUT payloads, network credentials or system logcat are included.\n"
            +"Please describe the visible symptom and the action immediately before it.\n";
        // Sony SD forbids a reader while a writer has the same file open (EBUSY).
        // Closing flushes this process's buffered events and, under this lock,
        // pauses its appends; the other process opens its journal only to flush.
        File report;List<String> skipped=new ArrayList<String>();
        try{
            if(internal!=null)internal.close();if(sd!=null)sd.close();
            report=DiagnosticStore.export(internalDir,sdDir,new File(Environment.getExternalStorageDirectory(),"SONYLUT/DIAG"),summary,skipped);
        }finally{
            if(internal!=null)try{internal.resume();}catch(Exception error){internalError=error.toString();internal=null;}
            if(sd!=null)try{sd.resume();}catch(Exception error){sdError=error.toString();sd=null;}
        }
        int entries=DiagnosticStore.verify(report);String busy=skipped.isEmpty()?"none":DiagnosticStore.join(skipped);
        event("export_complete","bytes="+report.length()+" entries="+entries+" skipped_busy="+busy);
        android.util.Log.i("LutSupport","export_complete bytes="+report.length()+" entries="+entries+" skipped_busy="+busy+" path=SONYLUT/DIAG/REPORT.ZIP");return report;
    }
}
