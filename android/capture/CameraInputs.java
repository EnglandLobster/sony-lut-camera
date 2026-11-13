package io.github.englandlobster.lutcamera;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Asks Sony's ScalarInput which rotary controls and PASM dial a body has.
 * Sony's own app framework decides key availability from
 * ScalarInput.getKeyStatus(code).valid; null means the camera would not say. */
final class CameraInputs {
    private CameraInputs(){}
    static Boolean has(int code){
        try{
            Class<?> type=Class.forName("com.sony.scalar.sysutil.ScalarInput");
            Method status=type.getMethod("getKeyStatus",int.class);
            Object value=status.invoke(null,Integer.valueOf(code));
            if(value==null)return null;
            return Boolean.valueOf(value.getClass().getField("valid").getInt(value)==1);
        }catch(Throwable e){return null;}
    }
    /** Empty when the probe is unavailable (ControlLayout then assumes two). */
    static List<ControlLayout.Input> rotary(){
        List<ControlLayout.Input> found=new ArrayList<ControlLayout.Input>();
        if(Boolean.TRUE.equals(has(ControlLayout.DIAL1_STATUS)))found.add(ControlLayout.Input.DIAL1);
        if(Boolean.TRUE.equals(has(ControlLayout.DIAL2_STATUS)))found.add(ControlLayout.Input.DIAL2);
        if(Boolean.TRUE.equals(has(ControlLayout.KURU_STATUS))||Boolean.TRUE.equals(has(ControlLayout.DIAL3_STATUS)))
            found.add(ControlLayout.Input.WHEEL);
        if(Boolean.TRUE.equals(has(ControlLayout.RING_STATUS)))found.add(ControlLayout.Input.RING);
        return found;
    }
    static Integer status(int code){
        try{
            Class<?> type=Class.forName("com.sony.scalar.sysutil.ScalarInput");
            Object value=type.getMethod("getKeyStatus",int.class).invoke(null,Integer.valueOf(code));
            if(value==null||value.getClass().getField("valid").getInt(value)!=1)return null;
            return Integer.valueOf(value.getClass().getField("status").getInt(value));
        }catch(Throwable e){return null;}
    }
    /** The PASM dial position as Sony's ISV_KEY_MODE_* code, or -1 when the
     * camera does not say. The A6300 reports it as the status of
     * ISV_KEY_MODE_DIAL itself (572 status 539 with the dial on A,
     * 2026-11-12); a body exposing per-position key status is also read. */
    static int dialPosition(){
        Integer dial=status(ControlLayout.MODE_DIAL);
        if(dial!=null&&dial.intValue()>=ControlLayout.MODE_FIRST&&dial.intValue()<=ControlLayout.MODE_LAST)return dial.intValue();
        int found=-1;
        for(int code=ControlLayout.MODE_FIRST;code<=ControlLayout.MODE_LAST;code++){
            Integer value=status(code);
            if(value!=null&&value.intValue()==1){if(found!=-1)return -1;found=code;}
        }
        return found;
    }
    /** Every valid mode-key status, for device evidence. */
    static String dialStatus(){
        StringBuilder s=new StringBuilder("572=").append(status(ControlLayout.MODE_DIAL));
        for(int code=ControlLayout.MODE_FIRST;code<=ControlLayout.MODE_LAST;code++){
            Integer value=status(code);if(value!=null)s.append(' ').append(code).append('=').append(value);
        }
        return s.toString();
    }
    /** TRUE/FALSE from the camera, null when unknown. */
    static Boolean modeDial(){return has(ControlLayout.MODE_DIAL);}
    static String describe(){
        StringBuilder s=new StringBuilder();
        int[] codes={ControlLayout.DIAL1_STATUS,ControlLayout.DIAL2_STATUS,ControlLayout.KURU_STATUS,
            ControlLayout.DIAL3_STATUS,ControlLayout.RING_STATUS,ControlLayout.MODE_DIAL};
        for(int code:codes)s.append(code).append('=').append(has(code)).append(' ');
        return s.toString().trim();
    }
}
