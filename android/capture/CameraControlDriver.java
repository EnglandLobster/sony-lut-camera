package io.github.englandlobster.lutcamera;

import android.hardware.Camera;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/** CameraEx-backed exposure and focus primitives. All calls run on the UI
 * thread that owns the preview session, so a full-parameter write can never
 * interleave with a preview matrix write. Exact values are verified by the
 * pure session classes; this driver only chooses Sony's write path. */
final class CameraControlDriver implements ManualExposure.Driver,FocusControls.Driver,SonyCameraFunctions.Driver {
    private final Object cameraEx;
    private final Camera camera;
    private final CameraExposureDriver parameters;

    CameraControlDriver(Object cameraEx,Camera camera){
        this.cameraEx=cameraEx;this.camera=camera;parameters=new CameraExposureDriver(camera);
    }
    public Map<String,String> read(){return parameters.read();}
    public void write(Map<String,String> patch){parameters.write(patch);}

    public void stepAperture(int direction)throws Exception{invoke(direction>0?"incrementAperture":"decrementAperture");}
    public void stepShutter(int direction)throws Exception{invoke(direction>0?"incrementShutterSpeed":"decrementShutterSpeed");}
    public void programShift(int direction)throws Exception{
        cameraEx.getClass().getMethod("adjustProgramLine",int.class).invoke(cameraEx,direction);
    }
    public void toggleFocus(boolean enabled)throws Exception{
        cameraEx.getClass().getMethod("setToggleFocusMode",boolean.class).invoke(cameraEx,enabled);
    }
    public void focusHold(boolean enabled)throws Exception{invoke(enabled?"startFocusHold":"stopFocusHold");}
    private Object currentModifier()throws Exception{
        return cameraEx.getClass().getMethod("createParametersModifier",Camera.Parameters.class).invoke(cameraEx,camera.getParameters());
    }
    private Object modifierValue(String method)throws Exception{
        Object m=currentModifier();return m.getClass().getMethod(method).invoke(m);
    }
    @SuppressWarnings("unchecked") public List<String> settingValues(String key)throws Exception{
        List<String> result=new ArrayList<String>();
        if(key.equals("flash-compensation")){
            int min=(Integer)modifierValue("getMinFlashCompensation"),max=(Integer)modifierValue("getMaxFlashCompensation");
            if(min<max&&max-min<1000)for(int i=min;i<=max;i++)result.add(Integer.toString(i));
            return result;
        }
        String values=read().get(key+"-values");
        if(values!=null)for(String value:values.split(","))if(value.trim().length()>0)result.add(value.trim());
        if(key.equals("focus-mode")&&result.contains("auto"))try{
            List<String> af=(List<String>)modifierValue("getSupportedAutoFocusModes");
            if(af!=null){
                List<String> still=new ArrayList<String>();
                for(String mode:af)if(mode.equals("af-s")||mode.equals("af-c")||mode.equals("af-a"))still.add(mode);
                if(!still.isEmpty()){int at=result.indexOf("auto");result.remove(at);result.addAll(at,still);}
            }
        }catch(NoSuchMethodException ignored){}
        return result;
    }
    public String setting(String key)throws Exception{
        if(key.equals("flash-compensation"))return String.valueOf(modifierValue("getFlashCompensation"));
        if(key.equals("auto-focus-mode"))try{return (String)modifierValue("getAutoFocusMode");}catch(NoSuchMethodException e){return null;}
        String value=read().get(key);
        if(key.equals("focus-mode")&&"auto".equals(value)){
            String af=setting("auto-focus-mode");if(af!=null)value=af;
        }
        return value;
    }
    public void setting(final String key,final String value)throws Exception{
        if(key.equals("flash-compensation")){
            modify(new Patch(){public void apply(Object m,Camera.Parameters p)throws Exception{
                m.getClass().getMethod("setFlashCompensation",int.class).invoke(m,Integer.parseInt(value));
            }});return;
        }
        if(key.equals("auto-focus-mode")||key.equals("focus-mode")&&value.startsWith("af-")){
            modify(new Patch(){public void apply(Object m,Camera.Parameters p)throws Exception{
                if(key.equals("focus-mode"))p.setFocusMode("auto");
                m.getClass().getMethod("setAutoFocusMode",String.class).invoke(m,value);
            }});return;
        }
        write(one(key,value));
    }
    String settingLabel(String key,String value)throws Exception{
        if(key.equals("focus-mode")){
            if(value.equals("manual"))return "MF";if(value.equals("auto"))return "AF";
            return value.toUpperCase(java.util.Locale.US);
        }
        if(key.equals("flash-compensation"))return String.format(java.util.Locale.US,"%+.1f EV",
            Integer.parseInt(value)*((Number)modifierValue("getFlashCompensationStep")).floatValue());
        return value;
    }
    public void pause(int ms)throws InterruptedException{Thread.sleep(ms);}
    public long millis(){return android.os.SystemClock.uptimeMillis();}
    private void invoke(String name)throws Exception{
        try{cameraEx.getClass().getMethod(name).invoke(cameraEx);}
        catch(NoSuchMethodException e){throw new UnsupportedOperationException("CameraEx."+name+" unavailable",e);}
    }

    public void setIso(final int iso)throws Exception{
        try{
            write(one("iso-sensitivity",Integer.toString(iso)));
            if(Integer.toString(iso).equals(read().get("iso-sensitivity")))return;
        }catch(RuntimeException e){android.util.Log.w("LutControls","iso_parameters_failed iso="+iso+" "+e);}
        // Sony's own ISO controller commits through an empty Parameters
        // object and ParametersModifier; some bodies ignore the bare key.
        modify(new Patch(){public void apply(Object m,Camera.Parameters p)throws Exception{
            m.getClass().getMethod("setISOSensitivity",int.class).invoke(m,Integer.valueOf(iso));
        }});
    }
    /** Tries Sony's write paths in order and keeps the first one whose
     * readback shows the area (and flexible-spot point) applied.
     *
     * The point always travels as the focus-point-x/y keys in Sony's
     * -1000..1000 frame space. ParametersModifier.setFocusPoint is not used:
     * on the A6300 it does not take that space (only the centre agrees), so
     * an off-centre point never applied (2026-11-12). A6300 2026-11-13: the
     * full-parameter write committed every point and the exit restore; the
     * empty-parameter keys were mostly refused, and with setFocusAreaMode in
     * the same write the point jumped to -1000,-1000. */
    public void setFocusArea(final String area,final int x,final int y)throws Exception{
        final boolean flex=FocusControls.FLEX.equals(area);
        Map<String,String> before=read();
        StringBuilder attempts=new StringBuilder();
        // The modifier path moved a flexible spot to -1000,-1000 on the
        // A6300; it is only tried for areas without a point.
        for(int path=0;path<(flex?2:3);path++){
            String name=path==0?"parameters":path==1?"keys":"keys_modifier";
            // Switching to a flexible spot, Sony applies the area and drops
            // the point of the same write (A6300 2026-11-13); a second write
            // from inside flex-spot then takes the point.
            for(int pass=0;pass<2;pass++){
                try{writeFocus(path,area,x,y);}
                catch(Exception e){
                    attempts.append(name).append('=').append(root(e)).append("; ");
                    android.util.Log.w("LutControls","focus_area_path_failed path="+name+" area="+area+" x="+x+" y="+y+" "+root(e));
                    break;
                }
                Map<String,String> after=read();
                String problem=FocusControls.verify(area,x,y,before,after);
                if(problem==null){
                    android.util.Log.i("LutControls","focus_area_committed path="+name+" pass="+pass+" area="+area+" requested="+x+","+y
                        +" actual="+after.get("focus-point-x")+","+after.get("focus-point-y"));
                    return;
                }
                attempts.append(name).append('=').append(problem).append("; ");
                android.util.Log.w("LutControls","focus_area_path_unapplied path="+name+" pass="+pass+" "+problem);
                if(!(flex&&area.equals(after.get("focus-area-type"))))break;
            }
        }
        throw new IOException("focus area commit failed: "+area+" "+x+","+y+" "+attempts.toString().trim());
    }
    private void writeFocus(int path,final String area,final int x,final int y)throws Exception{
        final boolean flex=FocusControls.FLEX.equals(area);
        if(path==0){
            Map<String,String> patch=new LinkedHashMap<String,String>();
            patch.put("focus-area-type",area);
            if(flex){patch.put("focus-point-x",Integer.toString(x));patch.put("focus-point-y",Integer.toString(y));}
            write(patch);return;
        }
        final boolean useModifier=path==2;
        modify(new Patch(){public void apply(Object m,Camera.Parameters p)throws Exception{
            if(useModifier)m.getClass().getMethod("setFocusAreaMode",String.class).invoke(m,area);
            p.set("focus-area-type",area);
            if(flex){p.set("focus-point-x",Integer.toString(x));p.set("focus-point-y",Integer.toString(y));}
        }});
    }
    private static Throwable root(Throwable e){while(e.getCause()!=null)e=e.getCause();return e;}
    public void setFaceDetection(final String mode)throws Exception{
        try{
            modify(new Patch(){public void apply(Object m,Camera.Parameters p)throws Exception{
                m.getClass().getMethod("setFaceDetectionMode",String.class).invoke(m,mode);
            }});
            if(mode.equals(read().get("face-detection-mode")))return;
        }catch(NoSuchMethodException e){android.util.Log.w("LutControls","face_modifier_unavailable",e);}
        write(one("face-detection-mode",mode));
    }

    private interface Patch { void apply(Object modifier,Camera.Parameters empty) throws Exception; }
    private void modify(Patch patch)throws Exception{
        Method empty=cameraEx.getClass().getMethod("createEmptyParameters");
        Camera.Parameters p=(Camera.Parameters)empty.invoke(cameraEx);
        if(p==null)throw new IOException("createEmptyParameters returned null");
        Object m=cameraEx.getClass().getMethod("createParametersModifier",Camera.Parameters.class).invoke(cameraEx,p);
        p.set("isEmptyParameters","1");
        try{patch.apply(m,p);}catch(java.lang.reflect.InvocationTargetException e){
            Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw e;
        }
        try{m.getClass().getMethod("tryIgnoreInhibit").invoke(m);}catch(NoSuchMethodException ignored){}
        camera.setParameters(p);
    }
    private static Map<String,String> one(String key,String value){
        Map<String,String> p=new LinkedHashMap<String,String>();p.put(key,value);return p;
    }
}
