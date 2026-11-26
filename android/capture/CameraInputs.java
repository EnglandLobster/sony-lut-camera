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
    /** Empty means no rotary inputs; null means availability could not be read. */
    static List<ControlLayout.Input> rotary(){
        List<ControlLayout.Input> found=new ArrayList<ControlLayout.Input>();
        int[] codes={ControlLayout.DIAL1_STATUS,ControlLayout.DIAL2_STATUS,ControlLayout.KURU_STATUS,
            ControlLayout.DIAL3_STATUS,ControlLayout.RING_STATUS};
        ControlLayout.Input[] inputs={ControlLayout.Input.DIAL1,ControlLayout.Input.DIAL2,ControlLayout.Input.WHEEL,
            ControlLayout.Input.WHEEL,ControlLayout.Input.RING};
        boolean unknown=false;
        for(int i=0;i<codes.length;i++){
            Boolean available=has(codes[i]);if(available==null)unknown=true;
            if(Boolean.TRUE.equals(available)&&!found.contains(inputs[i]))found.add(inputs[i]);
        }
        return found.isEmpty()&&unknown?null:found;
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
        if(dial!=null&&(dial.intValue()>=ControlLayout.MODE_FIRST&&dial.intValue()<=ControlLayout.MODE_LAST
                ||dial.intValue()==ControlLayout.MODE_HFR))return dial.intValue();
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
    static final SonyControlAssignments.Source ASSIGNMENTS=new SonyControlAssignments.Source(){
        public Integer logic(int key,int category){return integer("com.sony.scalar.sysutil.ScalarInput","getKeyLogicCode",
            new Class<?>[]{int.class,int.class,int.class},new Object[]{key,1,category});}
        public Integer custom(int key){
            try{
                Class<?> type=Class.forName("com.sony.scalar.sysutil.didep.Settings");
                int[] values=(int[])type.getMethod("getKeyFunction",int[].class).invoke(null,new Object[]{new int[]{key}});
                return values!=null&&values.length==1?Integer.valueOf(values[0]):null;
            }catch(Exception e){return null;}
        }
        public Integer setting(String name){return integer("com.sony.scalar.sysutil.didep.Settings",name,new Class<?>[0],new Object[0]);}
        public Integer status(int key){return CameraInputs.status(key);}
    };
    private static Integer integer(String type,String name,Class<?>[] signature,Object[] args){
        try{return (Integer)Class.forName(type).getMethod(name,signature).invoke(null,args);}
        catch(Exception e){return null;}
    }
    static String describe(){
        StringBuilder s=new StringBuilder();
        int[] codes={ControlLayout.DIAL1_STATUS,ControlLayout.DIAL2_STATUS,ControlLayout.KURU_STATUS,
            ControlLayout.DIAL3_STATUS,ControlLayout.RING_STATUS,ControlLayout.MODE_DIAL};
        for(int code:codes)s.append(code).append('=').append(has(code)).append(' ');
        return s.toString().trim();
    }
}
