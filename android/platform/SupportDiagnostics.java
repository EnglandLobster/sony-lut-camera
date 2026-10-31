package org.hongen.sonylutprobe;

import android.content.Context;
import android.os.*;
import java.io.*;
import java.util.UUID;

/** Per-process capture/UI recorder. No camera ownership, root, logcat or network. */
final class SupportDiagnostics {
    private static DiagnosticStore internal,sd;
    private static File internalDir,sdDir;
    private static String session,role,metadata;
    private static long sequence;
    private static String internalError="",sdError="";
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
        try{BufferedReader boot=new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"));try{details.append(" boot_id=").append(boot.readLine());}finally{boot.close();}}catch(Exception ignored){}
        metadata=details.toString();String header=line("session_header",metadata);
        try{internal=new DiagnosticStore(internalDir,role,header);}catch(Exception e){internalError=e.toString();}
        try{sd=new DiagnosticStore(sdDir,role,header);}catch(Exception e){sdError=e.toString();}
        event("process_start","role="+role+" pid="+android.os.Process.myPid()+" internal="+(internal!=null)+" sd="+(sd!=null)+" "+memory()+" internal_free="+free(internalDir)+" sd_free="+free(Environment.getExternalStorageDirectory()));
        final Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler(){public void uncaughtException(Thread thread,Throwable error){
            try{failure("uncaught "+thread.getName(),error);}catch(Throwable ignored){}
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
        boolean durable=event.equals("failed")||event.equals("uncaught")||event.equals("closed")||event.equals("process_start")||event.equals("camera_open_begin")||event.equals("sequence_open_begin")||event.equals("native_exit_requested")||event.equals("capture_interrupted");
        if(internal!=null)try{internal.append(line,durable);}catch(Exception e){internalError=e.toString();try{internal.close();}catch(Exception ignored){}internal=null;}
        if(sd!=null)try{sd.append(line,durable);}catch(Exception e){sdError=e.toString();try{sd.close();}catch(Exception ignored){}sd=null;}
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
    private static String free(File dir){try{StatFs stat=new StatFs(dir.getPath());return Long.toString((long)stat.getAvailableBlocks()*stat.getBlockSize());}catch(Exception e){return "unavailable";}}
    static synchronized File exportReport()throws IOException {
        if(internalDir==null)throw new IOException("diagnostic recorder unavailable");
        event("export_begin",memory());
        String summary="LUT Camera diagnostics / schema 1\n"+metadata+"\n"+memory()+"\ninternal_free="+free(internalDir)+" sd_free="+free(Environment.getExternalStorageDirectory())
            +"\ninternal_writer="+(internal!=null)+" sd_writer="+(sd!=null)+"\ninternal_error="+DiagnosticText.clean(internalError)+"\nsd_error="+DiagnosticText.clean(sdError)
            +"\n\nJSON lines: group by session; deduplicate mirrored events by session + seq.\n"
            +"wall_ms may be wrong if the camera clock is unset. uptime_ms is monotonic within one boot.\n"
            +"A capture session without closed may have been interrupted; it is not proof of a crash.\n"
            +"No photos, LUT payloads, network credentials or system logcat are included.\n"
            +"Please describe the visible symptom and the action immediately before it.\n";
        // Sony SD forbids a reader while a writer owns the same file (EBUSY).
        // This process lock also pauses event appends until both writers resume.
        File report;
        try{
            if(internal!=null)internal.close();if(sd!=null)sd.close();
            report=DiagnosticStore.export(internalDir,sdDir,new File(Environment.getExternalStorageDirectory(),"SONYLUT/DIAG"),summary);
        }finally{
            if(internal!=null)try{internal.resume();}catch(Exception error){internalError=error.toString();internal=null;}
            if(sd!=null)try{sd.resume();}catch(Exception error){sdError=error.toString();sd=null;}
        }
        int entries=DiagnosticStore.verify(report);event("export_complete","bytes="+report.length()+" entries="+entries);
        android.util.Log.i("LutSupport","export_complete bytes="+report.length()+" entries="+entries+" path=SONYLUT/DIAG/REPORT.ZIP");return report;
    }
}
