package io.github.englandlobster.lutcamera;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Read-only CameraEx focus notifications for {@link FocusFeedback}.
 * Listeners only: no parameter writes, no autofocus calls and no Sony
 * face/focus frame rendering (the A7R II cancelled the direct shutter
 * while Sony's face frame overlay was requested). Every notification is
 * logged with its raw values so the frame coordinates can be checked on
 * each body. */
final class FocusNotifications {
    interface Observer { void changed(); }
    private static final String TAG="LutFocus";
    private final Handler ui;
    private final FocusFeedback feedback;
    private final Observer observer;
    private final List<Method> registered=new ArrayList<Method>();
    private Object cameraEx;
    private boolean closed;

    FocusNotifications(Handler ui,FocusFeedback feedback,Observer observer){this.ui=ui;this.feedback=feedback;this.observer=observer;}

    void open(Object owner){
        cameraEx=owner;
        for(String kind:new String[]{"AutoFocusStartListener","AutoFocusDoneListener","FocusAreaListener"})register(kind);
    }
    private void register(final String kind){
        try{
            final Object owner=cameraEx;
            Class<?> type=Class.forName("com.sony.scalar.hardware.CameraEx$"+kind);
            Object listener=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},new InvocationHandler(){
                public Object invoke(Object proxy,Method method,Object[] args){
                    if(method.getDeclaringClass()==Object.class){
                        if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                        if(method.getName().equals("equals"))return proxy==args[0];
                        return "LutFocusNotifications:"+kind;
                    }
                    if(closed||cameraEx!=owner)return null;
                    try{deliver(kind,owner,args);}catch(Exception e){Log.w(TAG,"notification_invalid kind="+kind,e);}
                    return null;
                }
            });
            Method setter=owner.getClass().getMethod("set"+kind,type);
            setter.invoke(owner,listener);registered.add(setter);
            Log.i(TAG,"listening "+kind);
        }catch(Exception e){Log.w(TAG,"notification_unavailable kind="+kind+" "+e);}
    }
    private void deliver(String kind,final Object owner,Object[] args)throws Exception{
        final Runnable update;
        if(kind.equals("AutoFocusStartListener")){
            Log.i(TAG,"af_start");
            update=new Runnable(){public void run(){feedback.start();}};
        }else if(kind.equals("AutoFocusDoneListener")){
            final int status=((Integer)args[0]).intValue();
            final int[] indexes=args[1] instanceof int[]?((int[])args[1]).clone():null;
            Log.i(TAG,"af_done status="+status+" areas="+(indexes==null?"null":Arrays.toString(indexes)));
            update=new Runnable(){public void run(){feedback.done(status,indexes);}};
        }else{
            final List<int[]> frames=frames(args[0]);
            Object mode=args[0]==null?null:args[0].getClass().getField("focusAreaMode").get(args[0]);
            final String areaMode=mode==null?null:mode.toString();
            update=new Runnable(){public void run(){feedback.areas(areaMode,frames);}};
        }
        Runnable post=new Runnable(){public void run(){
            if(closed||cameraEx!=owner)return;
            update.run();observer.changed();
        }};
        if(Looper.myLooper()==ui.getLooper())post.run();else ui.post(post);
    }
    /** CameraEx.FocusAreaInfos: focusAreaMode and rectInfos[] of
     * {enable,index,rect}. Logged in full; enabled frames are kept. */
    private static List<int[]> frames(Object infos)throws Exception{
        List<int[]> out=new ArrayList<int[]>();
        if(infos==null){Log.i(TAG,"af_areas null");return out;}
        Object mode=infos.getClass().getField("focusAreaMode").get(infos);
        Object rects=infos.getClass().getField("rectInfos").get(infos);
        StringBuilder log=new StringBuilder("af_areas mode=").append(mode);
        int count=rects==null?0:Array.getLength(rects);
        log.append(" count=").append(count);
        for(int i=0;i<count;i++){
            Object info=Array.get(rects,i);if(info==null)continue;
            boolean enable=info.getClass().getField("enable").getBoolean(info);
            int index=info.getClass().getField("index").getInt(info);
            Rect r=(Rect)info.getClass().getField("rect").get(info);
            if(i<24)log.append(' ').append(index).append(enable?"+":"-").append(r==null?"null":r.toShortString());
            if(enable&&r!=null)out.add(new int[]{index,r.left,r.top,r.right,r.bottom});
        }
        Log.i(TAG,log.toString());
        return out;
    }
    void close(){
        if(closed)return;closed=true;
        for(Method setter:registered)try{setter.invoke(cameraEx,new Object[]{null});}
        catch(Exception e){Log.w(TAG,"detach_failed method="+setter.getName(),e);}
        Log.i(TAG,"closed listeners="+registered.size());
        registered.clear();cameraEx=null;
    }
}
