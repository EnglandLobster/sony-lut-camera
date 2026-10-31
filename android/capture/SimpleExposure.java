package org.hongen.sonylutprobe;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/** Session-owned automatic exposure. Writes only exposure fields; never image quality.
 * Driver reads fresh camera parameters and merges each patch into that snapshot. */
final class SimpleExposure {
    interface Driver {
        Map<String,String> read() throws Exception;
        void write(Map<String,String> patch) throws Exception;
    }
    private final Driver driver;
    private Map<String,String> original;
    private int min,max,index;
    private float step;
    private boolean ready;
    SimpleExposure(Driver driver){this.driver=driver;}
    private static String required(Map<String,String> p,String key)throws IOException{
        String v=p.get(key);if(v==null)throw new IOException("missing exposure parameter: "+key);return v;
    }
    private static boolean supports(Map<String,String> p,String key,String value)throws IOException{
        return (","+required(p,key).replace(" ","")+",").contains(","+value+",");
    }
    private static Map<String,String> one(String key,String value){
        Map<String,String> p=new LinkedHashMap<String,String>();p.put(key,value);return p;
    }
    void open()throws Exception{
        if(original!=null)throw new IllegalStateException("exposure session already entered");
        Map<String,String> p=driver.read();
        if(!supports(p,"scene-mode-values","program-auto")||!supports(p,"iso-sensitivity-values","0")
                ||!supports(p,"metering-mode-values","multi"))throw new IOException("automatic exposure is unavailable");
        min=Integer.parseInt(required(p,"min-exposure-compensation"));max=Integer.parseInt(required(p,"max-exposure-compensation"));
        step=Float.parseFloat(required(p,"exposure-compensation-step"));
        if(min>0||max<0||!(step>0)||Float.isInfinite(step))throw new IOException("invalid exposure compensation range");
        Map<String,String> saved=new LinkedHashMap<String,String>();
        for(String key:new String[]{"scene-mode","iso-sensitivity","metering-mode","exposure-compensation"})saved.put(key,required(p,key));
        // Retain manual shutter/aperture when switching away from M/A/S. In P,
        // those fields are metering results and must not be written back.
        String mode=p.get("scene-mode");
        if("manual-exposure".equals(mode)||"aperture-priority".equals(mode))saved.put("aperture",required(p,"aperture"));
        if("manual-exposure".equals(mode)||"shutter-priority".equals(mode)||"shutter-speed".equals(mode)){
            saved.put("shutter-speed-nume",required(p,"shutter-speed-nume"));
            saved.put("shutter-speed-deno",required(p,"shutter-speed-deno"));
        }
        if(p.containsKey("auto-exposure-lock"))saved.put("auto-exposure-lock",p.get("auto-exposure-lock"));
        original=saved; // Keep rollback data even if a vendor write partially fails.
        if(!"program-auto".equals(mode))driver.write(one("scene-mode","program-auto"));
        Map<String,String> automatic=new LinkedHashMap<String,String>();
        automatic.put("iso-sensitivity","0");automatic.put("metering-mode","multi");automatic.put("exposure-compensation","0");
        if(saved.containsKey("auto-exposure-lock"))automatic.put("auto-exposure-lock","false");
        driver.write(automatic);checkAutomatic(driver.read(),0);index=0;ready=true;
    }
    private void checkAutomatic(Map<String,String> p,int expected)throws IOException{
        if(!"program-auto".equals(p.get("scene-mode"))||!"0".equals(p.get("iso-sensitivity"))
                ||!"multi".equals(p.get("metering-mode"))||!Integer.toString(expected).equals(p.get("exposure-compensation"))
                ||"true".equals(p.get("auto-exposure-lock")))throw new IOException("automatic exposure readback differs from request");
    }
    void adjust(int direction,boolean reset)throws Exception{
        if(!ready)throw new IllegalStateException("exposure not ready");
        Map<String,String> p=driver.read();checkAutomatic(p,index);
        int target=reset?0:Math.max(min,Math.min(max,index+(direction<0?-1:direction>0?1:0)));
        if(target!=index){driver.write(one("exposure-compensation",Integer.toString(target)));checkAutomatic(driver.read(),target);index=target;}
    }
    String compensationLabel(){return String.format(Locale.US,"%+.1f EV",index*step);}
    String label(){return "亮度 "+compensationLabel();}
    String state(){return "scene=program-auto iso=auto metering=multi ev_index="+index+" ev_step="+step+" range="+min+".."+max;}
    void close()throws Exception{
        ready=false;if(original==null)return;
        // Mode first: Sony may reset dependent fields while changing modes.
        Map<String,String> current=driver.read();String mode=original.get("scene-mode");
        if(!mode.equals(current.get("scene-mode")))driver.write(one("scene-mode",mode));
        driver.write(original);Map<String,String> restored=driver.read();
        for(Map.Entry<String,String> e:original.entrySet())if(!e.getValue().equals(restored.get(e.getKey())))
            throw new IOException("exposure restore differs: "+e.getKey());
        original=null;
    }
}
