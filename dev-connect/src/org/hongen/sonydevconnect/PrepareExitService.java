package org.hongen.sonydevconnect;

import android.app.Activity;
import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.util.Log;

/** Signature-protected pre-exit barrier for our development app. Sony may kill
 * this package before its later SUSPEND broadcast; that broadcast is too late. */
public final class PrepareExitService extends Service {
    private HandlerThread thread;
    private Handler work;
    @Override public void onCreate(){super.onCreate();thread=new HandlerThread("prepare-camera-exit");thread.start();work=new Handler(thread.getLooper());}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,final int startId){
        final ResultReceiver result;
        final boolean checkOnly;
        try{
            if(intent==null||!"org.hongen.sonydevconnect.PREPARE_CAMERA_EXIT".equals(intent.getAction())){stopSelf(startId);return START_NOT_STICKY;}
            result=intent.getParcelableExtra("result");checkOnly=intent.getBooleanExtra("check_only",false);
            if(result==null){stopSelf(startId);return START_NOT_STICKY;}
        }catch(RuntimeException badParcel){
            // An old sender must not crash the helper and take down debugging.
            Log.e("SonyDevConnect","prepare_native_exit bad_request",badParcel);
            stopSelf(startId);return START_NOT_STICKY;
        }
        if(checkOnly){
            Log.i("SonyDevConnect","prepare_native_exit ipc_checked network_unchanged=true");
            result.send(Activity.RESULT_OK,null);stopSelf(startId);return START_NOT_STICKY;
        }
        work.post(new Runnable(){public void run(){
            int code=Activity.RESULT_CANCELED;
            try{
                DebugLifecycle.GATE.suspend();
                Log.i("SonyDevConnect","prepare_native_exit cleanup_begin");
                DebugLifecycle.stopLink(PrepareExitService.this,"prepare_native_exit",true);
                code=Activity.RESULT_OK;
                Log.i("SonyDevConnect","prepare_native_exit ready");
            }catch(Exception e){Log.e("SonyDevConnect","prepare_native_exit failed",e);}
            finally{result.send(code,null);stopSelf(startId);}
        }});
        return START_NOT_STICKY;
    }
    @Override public void onDestroy(){if(thread!=null)thread.quit();super.onDestroy();}
}
