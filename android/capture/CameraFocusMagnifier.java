package io.github.englandlobster.lutcamera;

import android.hardware.Camera;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Pair;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/** Owns only CameraEx.PreviewMagnificationListener; AF feedback listeners remain
 * independent. All callbacks are marshalled to the camera-owning UI thread. */
final class CameraFocusMagnifier {
    interface Host {
        boolean allowed();void changed(boolean active,int ratio,int x,int y);
        void failed(String operation,Exception error);
    }
    private final Object cameraEx;
    private final Camera camera;
    private final Handler ui;
    private final Host host;
    private FocusMagnifier state;
    private Method listenerSetter;
    private boolean closed;
    private final Runnable timeout=new Runnable(){public void run(){
        if(closed||state==null)return;
        if(!host.allowed()){ui.postDelayed(this,250);return;}
        try{state.expire(SystemClock.uptimeMillis());publish();}catch(Exception e){host.failed("mf_timeout",e);}
    }};
    CameraFocusMagnifier(Object cameraEx,Camera camera,Handler ui,Host host){
        this.cameraEx=cameraEx;this.camera=camera;this.ui=ui;this.host=host;
    }
    private Object modifier()throws Exception{
        return cameraEx.getClass().getMethod("createParametersModifier",Camera.Parameters.class).invoke(cameraEx,camera.getParameters());
    }
    @SuppressWarnings("unchecked") void open()throws Exception{
        Object modifier=modifier();
        List<Integer> levels=(List<Integer>)modifier.getClass().getMethod("getSupportedPreviewMagnification").invoke(modifier);
        state=new FocusMagnifier(new FocusMagnifier.Driver(){
            public void start(int level,int x,int y)throws Exception{
                cameraEx.getClass().getMethod("setPreviewMagnification",int.class,Pair.class).invoke(cameraEx,level,Pair.create(x,y));
            }
            public void stop()throws Exception{cameraEx.getClass().getMethod("stopPreviewMagnification").invoke(cameraEx);}
        },levels);
        Class<?> type=Class.forName("com.sony.scalar.hardware.CameraEx$PreviewMagnificationListener");
        Object listener=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},new InvocationHandler(){
            public Object invoke(Object proxy,Method method,Object[] args){
                if(method.getDeclaringClass()==Object.class){
                    if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                    if(method.getName().equals("equals"))return proxy==args[0];
                    return "LutFocusMagnifier";
                }
                if(closed)return null;
                final String name=method.getName();final Object[] values=args==null?new Object[0]:args.clone();
                ui.post(new Runnable(){public void run(){
                    if(closed)return;
                    try{
                        if(name.equals("onChanged")){
                            Pair<Integer,Integer> position=(Pair<Integer,Integer>)values[3];
                            state.changed((Boolean)values[0],(Integer)values[1],(Integer)values[2],
                                position==null?0:position.first,position==null?0:position.second);
                        }else if(name.equals("onInfoUpdated")){
                            boolean turning=(Boolean)values[0];Pair<Integer,Integer> position=(Pair<Integer,Integer>)values[1];
                            // No parameter read or write while the capture worker owns the camera.
                            if(!host.allowed())return;
                            Object m=modifier();String focus=camera.getParameters().getFocusMode();
                            boolean enabled=(Boolean)m.getClass().getMethod("getPreviewMagnificationOnManualFocus").invoke(m);
                            int duration=((Integer)m.getClass().getMethod("getPreviewMagnificationTimeOnManualFocus").invoke(m))*1000;
                            state.ring(turning,position==null?0:position.first,position==null?0:position.second,
                                enabled,"manual".equals(focus)||"dmf".equals(focus),true,SystemClock.uptimeMillis(),duration);
                        }
                        publish();
                    }catch(Exception e){host.failed("mf_notification",e);}
                }});return null;
            }
        });
        listenerSetter=cameraEx.getClass().getMethod("setPreviewMagnificationListener",type);
        listenerSetter.invoke(cameraEx,listener);
    }
    boolean supported(){return state!=null&&state.supported();}
    boolean active(){return state!=null&&state.active();}
    void next()throws Exception{
        if(!supported())throw new UnsupportedOperationException("Focus magnification unavailable");
        state.next();
        try{Object m=modifier();state.timeout(SystemClock.uptimeMillis(),
            ((Integer)m.getClass().getMethod("getPreviewMagnificationTimeOnManualFocus").invoke(m))*1000);
        }catch(NoSuchMethodException ignored){}
        publish();
    }
    void move(int dx,int dy)throws Exception{if(state!=null){
        state.move(dx,dy);Object m=modifier();state.timeout(SystemClock.uptimeMillis(),
            ((Integer)m.getClass().getMethod("getPreviewMagnificationTimeOnManualFocus").invoke(m))*1000);publish();
    }}
    void shutter(boolean down)throws Exception{if(state!=null){state.shutter(down);publish();}}
    void stop()throws Exception{if(state!=null){state.stop();publish();}}
    private void publish(){
        ui.removeCallbacks(timeout);
        if(state.deadline()>0)ui.postDelayed(timeout,Math.max(1,state.deadline()-SystemClock.uptimeMillis()));
        host.changed(state.active(),state.actual(),state.x(),state.y());
    }
    void close()throws Exception{
        closed=true;ui.removeCallbacks(timeout);Exception failure=null;
        try{if(state!=null)state.stop();}catch(Exception e){failure=e;}
        try{if(listenerSetter!=null)listenerSetter.invoke(cameraEx,new Object[]{null});}catch(Exception e){failure=e;}
        if(failure!=null)throw failure;
    }
}
