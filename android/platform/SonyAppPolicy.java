package org.hongen.sonylutprobe;
import android.app.Activity;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.os.Parcel;

/** Sony AppInfo protocol used by PictureEffectPlus. Keep playback in the APK;
 * USB handoff remains native. Resume through the launcher, not an isolated
 * capture Activity which requires a selected LUT and a fresh JNI session. */
final class SonyAppPolicy {
    private static final String HELPER="org.hongen.sonydevconnect";
    interface ExitListener {void failed(Exception error);}
    /** Ordinary installs exit directly. When our optional debug helper exists,
     * drain its link BEFORE Sony kills background apps/unmounts Android. */
    static final class ExitRequest {
        private final Activity activity;
        private final ExitListener listener;
        private final NativeExitGate gate=new NativeExitGate();
        private final long token;
        private boolean checkCompleted;
        private final Runnable checkSuccess;
        ExitRequest(Activity activity,ExitListener listener){this(activity,listener,null);}
        ExitRequest(Activity activity,ExitListener listener,Runnable checkSuccess){
            this.activity=activity;this.listener=listener;this.checkSuccess=checkSuccess;token=gate.begin();
        }
        void cancel(){gate.cancel();}
        void start(){
            try{
                PackageManager pm=activity.getPackageManager();
                int helperVersion;
                try{helperVersion=pm.getPackageInfo(HELPER,0).versionCode;}catch(PackageManager.NameNotFoundException absent){
                    if(checkSuccess!=null)throw new IllegalStateException("测试连接未安装");complete(true,null);return;
                }
                if(pm.checkSignatures(activity.getPackageName(),HELPER)!=PackageManager.SIGNATURE_MATCH)
                    throw new SecurityException("调试连接应用签名不匹配，未执行退出");
                if(helperVersion<5)throw new IllegalStateException("请先更新测试连接到 0.5，再退出应用");
                sendToHelper(true);
            }catch(Exception e){complete(false,e);}
        }
        private void sendToHelper(final boolean checkOnly){
            if(!gate.current(token))return;
            try{
                PackageManager pm=activity.getPackageManager();
                Intent prepare=new Intent("org.hongen.sonydevconnect.PREPARE_CAMERA_EXIT");
                prepare.setComponent(new ComponentName(HELPER,HELPER+".PrepareExitService"));
                prepare.putExtra("check_only",checkOnly);
                if(pm.resolveService(prepare,0)==null)throw new IllegalStateException("无法访问测试连接退出服务");
                ResultReceiver localReceiver=new ResultReceiver(new Handler()){
                    @Override protected void onReceiveResult(int code,Bundle data){
                        if(!gate.current(token))return;
                        if(checkOnly&&checkCompleted)return;
                        if(checkOnly&&code==Activity.RESULT_OK){
                            if(checkCompleted)return;checkCompleted=true;
                            android.util.Log.i("LutUI","native_exit_ipc_checked network_unchanged=true");
                            if(checkSuccess!=null){if(gate.accept(token))checkSuccess.run();return;}
                            sendToHelper(false);return;
                        }
                        complete(code==Activity.RESULT_OK,code==Activity.RESULT_OK?null:new IllegalStateException("调试连接清理未完成，已停止退出"));
                    }
                };
                // Old Sony Parcel records the runtime class name. Shipping the
                // anonymous subclass asks the other APK to load our class and
                // crashes its Service. Marshal only the platform wrapper around
                // the same Binder endpoint, not an application-owned subtype.
                Parcel parcel=Parcel.obtain();
                try{
                    localReceiver.writeToParcel(parcel,0);parcel.setDataPosition(0);
                    ResultReceiver remote=ResultReceiver.CREATOR.createFromParcel(parcel);
                    // Exercise the receiving app's platform-only class loader
                    // locally before allowing any cleanup service to start.
                    Bundle envelope=new Bundle();envelope.putParcelable("result",remote);
                    Parcel check=Parcel.obtain();
                    try{
                        check.writeBundle(envelope);check.setDataPosition(0);
                        ResultReceiver decoded=check.readBundle(ResultReceiver.class.getClassLoader()).getParcelable("result");
                        if(decoded==null||decoded.getClass()!=ResultReceiver.class)
                            throw new IllegalStateException("退出回执不是系统 Parcelable 类型");
                        prepare.putExtra("result",decoded);
                        android.util.Log.i("LutUI","native_exit_receiver platform_bundle_roundtrip=true");
                    }finally{check.recycle();}
                }finally{parcel.recycle();}
                android.util.Log.i("LutUI",checkOnly?"native_exit_check_ipc":"native_exit_prepare_debug_link");
                if(activity.startService(prepare)==null)throw new IllegalStateException("无法启动调试连接清理");
            }catch(Exception e){complete(false,e);}
        }
        private void complete(boolean ready,Exception error){
            if(!gate.accept(token))return;
            if(!ready){listener.failed(error);return;}
            try{requestNativeExit(activity);}catch(Exception e){listener.failed(e);}
        }
    }
    static boolean hasDebugHelper(Activity activity){
        try{return activity.getPackageManager().checkSignatures(activity.getPackageName(),HELPER)==PackageManager.SIGNATURE_MATCH;}
        catch(RuntimeException e){return false;}
    }
    static void openDebugHelper(Activity activity){
        if(!hasDebugHelper(activity))throw new IllegalStateException("测试连接不可用");
        activity.startActivity(new Intent().setComponent(new ComponentName(HELPER,HELPER+".ConnectActivity")));
    }
    /** Call only from the idle library after capture ownership has been released. */
    private static void requestNativeExit(Activity activity)throws Exception{
        Class<?> type=Class.forName("android.app.DAConnectionManager");
        Object manager=type.getConstructor(android.content.Context.class).newInstance(activity);
        android.util.Log.i("LutUI","native_exit_requested activity="+activity.getClass().getSimpleName());
        type.getMethod("finish").invoke(manager);
    }
    static void register(Activity activity){
        Intent info=new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        info.putExtra("package_name",activity.getPackageName());
        info.putExtra("class_name",LibraryActivity.class.getName());
        info.putExtra("large_category","CATEGORY_REC");info.putExtra("small_category","STILL");
        info.putExtra("pullingback_key",new String[]{"KEY_USB_CONNECT"});
        // Resume automatic sleep/accessory events, but do not request a cold
        // power-on return to this APK. OFF/ON must remain a user recovery route.
        info.putExtra("resume_key",new String[]{"KEY_RELEASE_APO","KEY_PLAY_APO",
            "KEY_MEDIA_INOUT_APO","KEY_LENS_APO","KEY_ACCESSORY_APO","KEY_DEDICATED_APO","KEY_POWER_APO"});
        activity.sendBroadcast(info);
        android.util.Log.i("LutUI","sony_app_policy activity="+activity.getClass().getSimpleName()+" playback=app pullingback=USB");
    }
}
