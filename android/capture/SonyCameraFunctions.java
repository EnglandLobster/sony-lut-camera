package io.github.englandlobster.lutcamera;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Session-owned native settings and momentary controls. Release events update
 * intent even during capture; flush applies it when camera writes are allowed. */
final class SonyCameraFunctions {
    interface Driver extends ParameterDriver {
        void programShift(int direction)throws Exception;
        void toggleFocus(boolean enabled)throws Exception;
        void focusHold(boolean enabled)throws Exception;
        List<String> settingValues(String key)throws Exception;
        String setting(String key)throws Exception;
        void setting(String key,String value)throws Exception;
    }
    private final Driver driver;
    private final Map<String,String> original=new LinkedHashMap<String,String>();
    private final Map<Integer,Integer> pressed=new LinkedHashMap<Integer,Integer>();
    private final Set<Integer> aeKeys=new HashSet<Integer>(),afKeys=new HashSet<Integer>(),focusKeys=new HashSet<Integer>();
    private boolean aeToggle,afToggle,aeApplied,afApplied,focusApplied;
    SonyCameraFunctions(Driver driver){this.driver=driver;}
    void pressed(int key,int function){pressed.put(key,function);}
    boolean pressed(int key){return pressed.containsKey(key);}
    /** The binding is captured at DOWN, not looked up again after a mode or
     * lever change. Updating intent here is safe while camera writes are busy. */
    Integer released(int key){
        Integer function=pressed.remove(key);
        if(function!=null&&momentary(function))hold(key,function,false);
        return function;
    }
    static boolean momentary(int function){return function==SonyControlAssignments.AEL_HOLD
        ||function==SonyControlAssignments.AFMF_HOLD||function==SonyControlAssignments.FOCUS_HOLD;}
    void hold(int key,int function,boolean down){
        Set<Integer> set=function==SonyControlAssignments.AEL_HOLD?aeKeys:
            function==SonyControlAssignments.AFMF_HOLD?afKeys:focusKeys;
        if(down)set.add(key);else set.remove(key);
    }
    void toggle(int function){if(function==SonyControlAssignments.AEL_TOGGLE)aeToggle=!aeToggle;else afToggle=!afToggle;}
    void flush()throws Exception{
        boolean ae=aeToggle||!aeKeys.isEmpty(),af=afToggle||!afKeys.isEmpty(),focus=!focusKeys.isEmpty();
        if(ae!=aeApplied){set("auto-exposure-lock",Boolean.toString(ae));aeApplied=ae;}
        if(af!=afApplied){driver.toggleFocus(af);afApplied=af;}
        if(focus!=focusApplied){driver.focusHold(focus);focusApplied=focus;}
    }
    List<String> values(String key)throws Exception{
        return driver.settingValues(key);
    }
    String value(String key)throws Exception{return driver.setting(key);}
    void set(String key,String value)throws Exception{
        String before=driver.setting(key);
        if(before==null)throw new IOException("Setting unavailable: "+key);
        if(!original.containsKey(key))original.put(key,before);
        if(key.equals("focus-mode")&&!original.containsKey("auto-focus-mode")){
            String af=driver.setting("auto-focus-mode");if(af!=null)original.put("auto-focus-mode",af);
        }
        driver.setting(key,value);
        if(!value.equals(driver.setting(key)))throw new IOException("Setting refused: "+key+"="+value);
    }
    void programShift(int direction)throws Exception{driver.programShift(direction);}
    void close()throws Exception{
        pressed.clear();
        aeKeys.clear();afKeys.clear();focusKeys.clear();aeToggle=false;afToggle=false;
        Exception failure=null;
        // Independent cleanup: one unavailable control must not strand another.
        if(afApplied)try{driver.toggleFocus(false);afApplied=false;}catch(Exception e){failure=e;}
        if(focusApplied)try{driver.focusHold(false);focusApplied=false;}catch(Exception e){failure=e;}
        for(Map.Entry<String,String> entry:original.entrySet())try{
            driver.setting(entry.getKey(),entry.getValue());
            if(!entry.getValue().equals(driver.setting(entry.getKey())))throw new IOException("Restore refused: "+entry.getKey());
        }catch(Exception e){failure=e;}
        if(failure!=null)throw failure;
        original.clear();aeApplied=false;
    }
}
