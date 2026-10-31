package org.hongen.sonylutprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Read-only CameraEx notifications; never polls or changes exposure settings. */
final class CaptureTelemetry {
    interface Observer { void changed(CaptureReadout values); }
    private final Context context;
    private final Handler ui;
    private final Observer observer;
    private final CaptureReadout values=new CaptureReadout();
    private final List<Method> registered=new ArrayList<Method>();
    private Object cameraEx;
    private boolean closed,batteryRegistered,scheduled;
    private String published="";
    private final Runnable deliver=new Runnable(){public void run(){
        scheduled=false;if(closed)return;
        String state=values.state();if(state.equals(published))return;
        published=state;observer.changed(values);Log.i("LutTelemetry",state);
    }};
    private final BroadcastReceiver batteryReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent intent){
            if(closed||!Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction()))return;
            values.battery=CaptureReadout.batteryPercent(intent.getIntExtra("level",-1),intent.getIntExtra("scale",-1));
            int status=intent.getIntExtra("status",-1);
            values.charging=status==2; // BatteryManager.BATTERY_STATUS_CHARGING.
            schedule();
        }
    };
    CaptureTelemetry(Context context,Handler ui,Observer observer){this.context=context;this.ui=ui;this.observer=observer;}
    void open(Object owner,Camera.Parameters initial){
        cameraEx=owner;
        values.shutterNumerator=number(initial.get("shutter-speed-nume"));
        values.shutterDenominator=number(initial.get("shutter-speed-deno"));
        values.aperture=number(initial.get("aperture"));
        // iso-sensitivity=0 is the AUTO setting, not a measured sensitivity.
        for(String kind:new String[]{"ShutterSpeedChangeListener","ApertureChangeListener","AutoISOSensitivityListener"})register(kind);
        try{context.registerReceiver(batteryReceiver,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));batteryRegistered=true;}
        catch(RuntimeException e){Log.w("LutTelemetry","battery_unavailable",e);}
        schedule();
    }
    private static int number(String value){try{return Integer.parseInt(value);}catch(RuntimeException e){return 0;}}
    private static int field(Object value,String name)throws Exception{return value.getClass().getField(name).getInt(value);}
    private void register(final String kind){
        try{
            final Object owner=cameraEx;
            Class<?> listenerType=Class.forName("com.sony.scalar.hardware.CameraEx$"+kind);
            Object listener=Proxy.newProxyInstance(listenerType.getClassLoader(),new Class<?>[]{listenerType},new InvocationHandler(){
                public Object invoke(Object proxy,Method method,Object[] args){
                    if(method.getDeclaringClass()==Object.class){
                        if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                        if(method.getName().equals("equals"))return proxy==args[0];
                        return "LutCaptureTelemetry:"+kind;
                    }
                    if(closed||cameraEx!=owner)return null;
                    try{
                        final int first,second;
                        if(kind.equals("ShutterSpeedChangeListener")){
                            first=field(args[0],"currentShutterSpeed_n");second=field(args[0],"currentShutterSpeed_d");
                        }else if(kind.equals("ApertureChangeListener")){
                            first=field(args[0],"currentAperture");second=0;
                        }else{first=((Integer)args[0]).intValue();second=0;}
                        Runnable update=new Runnable(){public void run(){
                            if(closed||cameraEx!=owner)return;
                            if(kind.equals("ShutterSpeedChangeListener")){values.shutterNumerator=first;values.shutterDenominator=second;}
                            else if(kind.equals("ApertureChangeListener"))values.aperture=first;
                            else values.autoIso=first;
                            schedule();
                        }};
                        if(Looper.myLooper()==ui.getLooper())update.run();else ui.post(update);
                    }catch(Exception e){Log.w("LutTelemetry","notification_invalid kind="+kind,e);}
                    return null;
                }
            });
            Method setter=owner.getClass().getMethod("set"+kind,listenerType);
            setter.invoke(owner,listener);registered.add(setter);
            Log.i("LutTelemetry","listening "+kind);
        }catch(Exception e){Log.w("LutTelemetry","notification_unavailable kind="+kind,e);}
    }
    private void schedule(){if(!closed&&!scheduled){scheduled=true;ui.postDelayed(deliver,250);}}
    void close(){
        if(closed)return;closed=true;ui.removeCallbacks(deliver);
        if(batteryRegistered){context.unregisterReceiver(batteryReceiver);batteryRegistered=false;}
        for(Method setter:registered)try{setter.invoke(cameraEx,new Object[]{null});}
        catch(Exception e){Log.w("LutTelemetry","detach_failed method="+setter.getName(),e);}
        Log.i("LutTelemetry","closed listeners="+registered.size());
        registered.clear();cameraEx=null;
    }
}
