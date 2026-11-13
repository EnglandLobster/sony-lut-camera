package io.github.englandlobster.lutcamera;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Session-owned P/A/S/M exposure, ISO, white balance and compensation.
 * Pure: no Android types, host-testable.
 *
 * The camera's scene-mode is the source of truth. On bodies with a physical
 * PASM dial Sony applies the dial itself and {@link #sync} follows it; bodies
 * without one use {@link #selectMode}. ISO, white balance and compensation are
 * session values kept across mode changes, as on the native camera. Every
 * write is verified by readback; exit restores the camera's own settings,
 * except that a mode the user moved the physical dial to is left in place. */
final class ManualExposure {
    enum Mode { P, A, S, M }
    interface Driver extends ParameterDriver {
        void stepAperture(int direction) throws Exception;
        void stepShutter(int direction) throws Exception;
        void setIso(int iso) throws Exception;
    }
    interface Log { void log(String name,String detail); }

    static final String SCENE_P="program-auto",SCENE_A="aperture-priority",SCENE_M="manual-exposure";
    private static final String[] WB_PRESETS={"auto","daylight","shade","cloudy-daylight","incandescent",
        "fluorescent-warmwhite","warm-fluorescent","fluorescent-coolwhite","fluorescent-daywhite","fluorescent-daylight",
        "flash","underwater-auto"};

    private final Driver driver;
    private final Log log;
    private Map<String,String> original,last;
    private final List<Integer> isoValues=new ArrayList<Integer>();
    private final List<String> wbValues=new ArrayList<String>();
    private String sceneS;
    private Mode mode=Mode.P;
    private int iso,evIndex,evMin,evMax;
    private float evStep;
    private String whiteBalance;
    private boolean ready,wroteScene,dialMoved;
    private String dialScene,fallbackFrom;
    private String rememberedAperture,rememberedShutter;
    private final List<String> notes=new ArrayList<String>();

    ManualExposure(Driver driver,Log log){this.driver=driver;this.log=log;}

    // ---- persisted session values -------------------------------------------------
    /** What the next session restores. Text form survives SharedPreferences. */
    static final class Memory {
        Mode mode;int iso=-1,ev=Integer.MIN_VALUE;String whiteBalance,aperture,shutter;
        String encode(){
            StringBuilder s=new StringBuilder();
            if(mode!=null)s.append("mode=").append(mode.name()).append(';');
            if(iso>=0)s.append("iso=").append(iso).append(';');
            if(ev!=Integer.MIN_VALUE)s.append("ev=").append(ev).append(';');
            if(whiteBalance!=null)s.append("wb=").append(whiteBalance).append(';');
            if(aperture!=null)s.append("aperture=").append(aperture).append(';');
            if(shutter!=null)s.append("shutter=").append(shutter).append(';');
            return s.toString();
        }
        /** Malformed fields are dropped, never fatal. */
        static Memory decode(String text){
            Memory m=new Memory();if(text==null)return m;
            for(String item:text.split(";")){
                int at=item.indexOf('=');if(at<=0)continue;
                String key=item.substring(0,at),value=item.substring(at+1);
                try{
                    if(key.equals("mode"))m.mode=Mode.valueOf(value);
                    else if(key.equals("iso")){int v=Integer.parseInt(value);if(v>=0)m.iso=v;}
                    else if(key.equals("ev"))m.ev=Integer.parseInt(value);
                    else if(key.equals("wb")&&value.matches("[a-z-]{1,40}"))m.whiteBalance=value;
                    else if(key.equals("aperture")&&value.matches("[0-9]{2,5}"))m.aperture=value;
                    else if(key.equals("shutter")&&value.matches("[0-9]{1,6}/[0-9]{1,6}"))m.shutter=value;
                }catch(RuntimeException ignored){}
            }
            return m;
        }
    }
    Memory memory(){
        Memory m=new Memory();m.mode=mode;m.iso=iso;m.whiteBalance=whiteBalance;
        if(evStep>0)m.ev=evIndex;
        m.aperture=rememberedAperture;m.shutter=rememberedShutter;
        Map<String,String> p=last;
        if(p!=null){
            if((mode==Mode.A||mode==Mode.M)&&p.get("aperture")!=null)m.aperture=p.get("aperture");
            if((mode==Mode.S||mode==Mode.M)&&p.get("shutter-speed-nume")!=null&&p.get("shutter-speed-deno")!=null)
                m.shutter=p.get("shutter-speed-nume")+"/"+p.get("shutter-speed-deno");
        }
        return m;
    }

    // ---- helpers ------------------------------------------------------------------
    private static String required(Map<String,String> p,String key)throws IOException{
        String v=p.get(key);if(v==null)throw new IOException("missing exposure parameter: "+key);return v;
    }
    private static boolean listed(String values,String value){
        return values!=null&&(","+values.replace(" ","")+",").contains(","+value+",");
    }
    private static Map<String,String> one(String key,String value){
        Map<String,String> p=new LinkedHashMap<String,String>();p.put(key,value);return p;
    }
    static int number(String value){try{return Integer.parseInt(value);}catch(RuntimeException e){return 0;}}
    private Map<String,String> read()throws Exception{last=driver.read();return last;}
    String scene(Mode m){
        switch(m){case A:return SCENE_A;case S:return sceneS;case M:return SCENE_M;default:return SCENE_P;}
    }
    Mode fromScene(String scene){
        if(SCENE_P.equals(scene))return Mode.P;
        if(SCENE_A.equals(scene))return Mode.A;
        if(SCENE_M.equals(scene))return Mode.M;
        if(scene!=null&&scene.equals(sceneS))return Mode.S;
        return null;
    }
    boolean supports(Mode m){return m!=Mode.S||sceneS!=null;}

    // ---- lifecycle ----------------------------------------------------------------
    /** @param remembered previous session values; may be empty, never null
     * @param physicalDial true when the body has a PASM dial that owns the mode */
    void open(final Memory remembered,boolean physicalDial)throws Exception{
        if(original!=null)throw new IllegalStateException("exposure session already entered");
        Map<String,String> p=read();
        String modes=required(p,"scene-mode-values");
        if(!listed(modes,SCENE_P)||!listed(p.get("iso-sensitivity-values"),"0"))
            throw new IOException("automatic exposure is unavailable");
        sceneS=listed(modes,"shutter-priority")?"shutter-priority":listed(modes,"shutter-speed")?"shutter-speed":null;
        parseIso(p);parseWhiteBalance(p);
        evMin=number(required(p,"min-exposure-compensation"));evMax=number(required(p,"max-exposure-compensation"));
        evStep=Float.parseFloat(required(p,"exposure-compensation-step"));
        if(evMin>0||evMax<0||!(evStep>0)||Float.isInfinite(evStep))throw new IOException("invalid exposure compensation range");
        String scene=required(p,"scene-mode");
        Map<String,String> saved=new LinkedHashMap<String,String>();
        saved.put("scene-mode",scene);
        saved.put("iso-sensitivity",required(p,"iso-sensitivity"));
        saved.put("exposure-compensation",required(p,"exposure-compensation"));
        if(p.get("whitebalance")!=null)saved.put("whitebalance",p.get("whitebalance"));
        // Retain manual shutter/aperture of M/A/S. In P they are metering
        // results and must not be written back.
        Mode initial=fromScene(scene);
        if((initial==Mode.M||initial==Mode.A)&&p.get("aperture")!=null)saved.put("aperture",p.get("aperture"));
        if(initial==Mode.M||initial==Mode.S){
            saved.put("shutter-speed-nume",required(p,"shutter-speed-nume"));
            saved.put("shutter-speed-deno",required(p,"shutter-speed-deno"));
        }
        if(p.containsKey("auto-exposure-lock"))saved.put("auto-exposure-lock",p.get("auto-exposure-lock"));
        original=saved; // Keep rollback data even if a vendor write partially fails.
        dialScene=scene;
        rememberedAperture=remembered.aperture;rememberedShutter=remembered.shutter;

        Mode target=initial;
        if(target==null){target=Mode.P;fallbackFrom=scene;}
        else if(!physicalDial&&remembered.mode!=null&&supports(remembered.mode))target=remembered.mode;
        if(target!=initial){
            driver.write(one("scene-mode",scene(target)));wroteScene=true;
            p=read();
            if(!scene(target).equals(p.get("scene-mode")))throw new IOException("exposure mode readback differs: "+scene(target));
        }
        mode=target;
        if("true".equals(p.get("auto-exposure-lock"))){driver.write(one("auto-exposure-lock","false"));p=read();}
        iso=number(p.get("iso-sensitivity"));whiteBalance=p.get("whitebalance");
        evIndex=clampEv(number(p.get("exposure-compensation")));
        ready=true;
        // Remembered values are conveniences: an unsupported value is skipped.
        if(remembered.iso>=0&&isoValues.contains(Integer.valueOf(remembered.iso))&&remembered.iso!=iso)
            attempt("iso",new Step(){public void run()throws Exception{applyIso(remembered.iso);}});
        if(remembered.whiteBalance!=null&&wbValues.contains(remembered.whiteBalance)&&!remembered.whiteBalance.equals(whiteBalance))
            attempt("whitebalance",new Step(){public void run()throws Exception{applyWhiteBalance(remembered.whiteBalance);}});
        reapplyModeValues();
        if(remembered.ev!=Integer.MIN_VALUE&&clampEv(remembered.ev)!=evIndex)
            attempt("ev",new Step(){public void run()throws Exception{applyEv(clampEv(remembered.ev));}});
        read();
        log("exposure_open",state()+" physical_dial="+physicalDial+" fallback_from="+fallbackFrom+" skipped="+notes);
    }
    private interface Step { void run() throws Exception; }
    private void attempt(String what,Step step){
        try{step.run();}catch(Exception e){notes.add(what);log("exposure_restore_skipped",what+" "+e);}
    }
    /** Absolute aperture/shutter of the remembered session for A/S/M. */
    private void reapplyModeValues(){
        if((mode==Mode.A||mode==Mode.M)&&rememberedAperture!=null&&!rememberedAperture.equals(last.get("aperture")))
            attempt("aperture",new Step(){public void run()throws Exception{
                driver.write(one("aperture",rememberedAperture));
                if(!rememberedAperture.equals(read().get("aperture")))throw new IOException("aperture readback "+last.get("aperture"));
            }});
        if((mode==Mode.S||mode==Mode.M)&&rememberedShutter!=null){
            final String[] parts=rememberedShutter.split("/");
            if(parts.length==2&&!(parts[0].equals(last.get("shutter-speed-nume"))&&parts[1].equals(last.get("shutter-speed-deno"))))
                attempt("shutter",new Step(){public void run()throws Exception{
                    Map<String,String> patch=new LinkedHashMap<String,String>();
                    patch.put("shutter-speed-nume",parts[0]);patch.put("shutter-speed-deno",parts[1]);driver.write(patch);
                    Map<String,String> r=read();
                    if(!parts[0].equals(r.get("shutter-speed-nume"))||!parts[1].equals(r.get("shutter-speed-deno")))
                        throw new IOException("shutter readback "+r.get("shutter-speed-nume")+"/"+r.get("shutter-speed-deno"));
                }});
        }
    }
    private void parseIso(Map<String,String> p)throws IOException{
        isoValues.clear();
        for(String raw:required(p,"iso-sensitivity-values").split(",")){
            int value=number(raw.trim());
            if(value>=0&&!isoValues.contains(Integer.valueOf(value))&&(value>0||"0".equals(raw.trim())))isoValues.add(Integer.valueOf(value));
        }
        java.util.Collections.sort(isoValues);
    }
    private void parseWhiteBalance(Map<String,String> p){
        wbValues.clear();String values=p.get("whitebalance-values");
        for(String preset:WB_PRESETS)if(listed(values,preset))wbValues.add(preset);
        // A custom or colour-temperature setting stays selectable as itself.
        String current=p.get("whitebalance");
        if(current!=null&&!wbValues.contains(current))wbValues.add(current);
    }
    private int clampEv(int v){return Math.max(evMin,Math.min(evMax,v));}
    private void requireReady(){if(!ready)throw new IllegalStateException("exposure not ready");}
    private void log(String name,String detail){if(log!=null)log.log(name,detail);}

    // ---- mode ---------------------------------------------------------------------
    /** Follow a physical dial change. Returns true when the mode changed.
     * A dial position outside P/A/S/M (AUTO, SCN, MR, movie…) runs as P. */
    boolean sync()throws Exception{
        requireReady();
        Map<String,String> p=read();
        String scene=p.get("scene-mode");
        Mode next=fromScene(scene);
        if(scene!=null&&!scene.equals(scene(mode))){
            dialMoved=true;dialScene=scene;
        }
        if(next==null){
            fallbackFrom=scene;
            driver.write(one("scene-mode",SCENE_P));wroteScene=true;
            p=read();next=fromScene(p.get("scene-mode"));
            if(next!=Mode.P)throw new IOException("exposure mode readback differs after dial: "+p.get("scene-mode"));
        }else fallbackFrom=null;
        boolean changed=next!=mode;
        mode=next;
        reapplySession(changed);
        log("exposure_sync","scene="+scene+" "+state()+" fallback_from="+fallbackFrom);
        return changed;
    }
    /** Follow a physical dial position read from ScalarInput. Sony does not
     * apply the dial while an app owns the camera (A6300, 2026-11-12), so the
     * session writes the mode. A non-P/A/S/M position runs as P and exit then
     * restores the camera's original mode.
     * @param target dial mode, or null for AUTO/SCN/MR/movie…
     * @param label dial position name for the on-screen fallback note */
    boolean follow(Mode target,String label)throws Exception{
        requireReady();
        boolean usable=target!=null&&supports(target);
        Mode next=usable?target:Mode.P;
        boolean changed=next!=mode;
        if(changed)rememberModeValues();
        Map<String,String> p=read();
        if(!scene(next).equals(p.get("scene-mode"))){
            driver.write(one("scene-mode",scene(next)));wroteScene=true;p=read();
            if(!scene(next).equals(p.get("scene-mode")))throw new IOException("exposure mode readback differs: "+scene(next));
        }
        mode=next;fallbackFrom=usable?null:label;
        // Leave the camera agreeing with the dial; an original A/M/S
        // aperture or shutter then no longer applies.
        dialScene=usable?scene(next):original.get("scene-mode");
        dialMoved=!dialScene.equals(original.get("scene-mode"));
        reapplySession(changed);
        log("exposure_follow","dial="+label+" "+state()+" fallback_from="+fallbackFrom);
        return changed;
    }
    /** Sony may reset ISO/WB/EV with the mode. Keep the session values. */
    private void reapplySession(boolean modeChanged)throws Exception{
        Map<String,String> p=last;
        if(number(p.get("iso-sensitivity"))!=iso&&isoValues.contains(Integer.valueOf(iso)))
            attempt("iso",new Step(){public void run()throws Exception{applyIso(iso);}});
        if(whiteBalance!=null&&!whiteBalance.equals(last.get("whitebalance")))
            attempt("whitebalance",new Step(){public void run()throws Exception{applyWhiteBalance(whiteBalance);}});
        if(modeChanged)reapplyModeValues();
        if(number(last.get("exposure-compensation"))!=evIndex)
            attempt("ev",new Step(){public void run()throws Exception{applyEv(evIndex);}});
        read();
    }
    /** App-selected mode for bodies without a PASM dial. */
    void selectMode(Mode next)throws Exception{
        requireReady();
        if(!supports(next))throw new IOException("exposure mode unavailable: "+next);
        if(next==mode)return;
        rememberModeValues();
        driver.write(one("scene-mode",scene(next)));wroteScene=true;
        Map<String,String> p=read();
        if(!scene(next).equals(p.get("scene-mode")))throw new IOException("exposure mode readback differs: "+scene(next));
        mode=next;
        if(number(p.get("iso-sensitivity"))!=iso)attempt("iso",new Step(){public void run()throws Exception{applyIso(iso);}});
        if(whiteBalance!=null&&!whiteBalance.equals(p.get("whitebalance")))
            attempt("whitebalance",new Step(){public void run()throws Exception{applyWhiteBalance(whiteBalance);}});
        reapplyModeValues();
        if(number(last.get("exposure-compensation"))!=evIndex)attempt("ev",new Step(){public void run()throws Exception{applyEv(evIndex);}});
        read();
        log("exposure_mode",state());
    }
    private void rememberModeValues(){
        Memory m=memory();rememberedAperture=m.aperture;rememberedShutter=m.shutter;
    }

    // ---- adjustments ------------------------------------------------------------
    /** One dial burst; false when the action does not apply in this mode or
     * the camera kept its value (a lens or range limit). */
    boolean step(ControlLayout.Action action,int steps)throws Exception{
        requireReady();
        if(steps==0)return false;
        switch(action){
            case APERTURE:{
                if(mode!=Mode.A&&mode!=Mode.M)return false;
                String before=last.get("aperture");
                for(int i=0;i<Math.abs(steps);i++)driver.stepAperture(steps>0?1:-1);
                String after=read().get("aperture");rememberModeValues();
                return after!=null&&!after.equals(before);
            }
            case SHUTTER:{
                if(mode!=Mode.S&&mode!=Mode.M)return false;
                String before=last.get("shutter-speed-nume")+"/"+last.get("shutter-speed-deno");
                for(int i=0;i<Math.abs(steps);i++)driver.stepShutter(steps>0?1:-1);
                Map<String,String> p=read();rememberModeValues();
                return !before.equals(p.get("shutter-speed-nume")+"/"+p.get("shutter-speed-deno"));
            }
            case EV:{
                if(!evAvailable())return false;
                int target=clampEv(evIndex+steps);
                if(target==evIndex)return false;
                applyEv(target);return true;
            }
            case ISO:{
                int index=isoValues.indexOf(Integer.valueOf(iso));
                if(index<0)index=0;
                int next=Math.max(0,Math.min(isoValues.size()-1,index+steps));
                if(next==index)return false;
                applyIso(isoValues.get(next).intValue());return true;
            }
            default:return false;
        }
    }
    /** M with a fixed ISO has nothing for compensation to act on. */
    boolean evAvailable(){return mode!=Mode.M||iso==0;}
    void resetEv()throws Exception{requireReady();if(evIndex!=0)applyEv(0);}
    void setIso(int value)throws Exception{
        requireReady();
        if(!isoValues.contains(Integer.valueOf(value)))throw new IOException("unsupported ISO "+value);
        if(value!=iso)applyIso(value);
    }
    void setWhiteBalance(String value)throws Exception{
        requireReady();
        if(!wbValues.contains(value))throw new IOException("unsupported white balance "+value);
        if(!value.equals(whiteBalance))applyWhiteBalance(value);
    }
    private void applyEv(int target)throws Exception{
        driver.write(one("exposure-compensation",Integer.toString(target)));
        int actual=number(read().get("exposure-compensation"));
        if(actual!=target)throw new IOException("EV readback "+actual+" expected "+target);
        evIndex=target;
    }
    private void applyIso(int value)throws Exception{
        driver.setIso(value);
        int actual=number(read().get("iso-sensitivity"));
        if(actual!=value)throw new IOException("ISO readback "+actual+" expected "+value);
        iso=value;
    }
    private void applyWhiteBalance(String value)throws Exception{
        driver.write(one("whitebalance",value));
        String actual=read().get("whitebalance");
        if(!value.equals(actual))throw new IOException("white balance readback "+actual+" expected "+value);
        whiteBalance=value;
    }

    // ---- readouts -----------------------------------------------------------------
    Mode mode(){return mode;}
    boolean ready(){return ready;}
    int iso(){return iso;}
    List<Integer> isoValues(){return new ArrayList<Integer>(isoValues);}
    String whiteBalance(){return whiteBalance;}
    List<String> whiteBalanceValues(){return new ArrayList<String>(wbValues);}
    /** Latest verified parameter map; never mutated after assignment. */
    Map<String,String> parameters(){return last;}
    /** Non-P/A/S/M dial position currently running as P, or null. */
    String fallbackFrom(){return fallbackFrom;}
    String compensationLabel(){return evAvailable()?String.format(Locale.US,"%+.1f EV",evIndex*evStep):"EV --";}
    String modeLabel(){return mode.name();}
    static String isoLabel(int value){return value==0?"ISO AUTO":"ISO "+value;}
    static String whiteBalanceLabel(String value){
        if(value==null)return "WB --";
        if(value.equals("auto"))return "AWB";
        if(value.equals("daylight"))return "日光";
        if(value.equals("shade"))return "阴影";
        if(value.equals("cloudy-daylight"))return "阴天";
        if(value.equals("incandescent"))return "白炽灯";
        if(value.equals("fluorescent-warmwhite")||value.equals("warm-fluorescent"))return "荧光灯 暖白";
        if(value.equals("fluorescent-coolwhite"))return "荧光灯 冷白";
        if(value.equals("fluorescent-daywhite"))return "荧光灯 昼白";
        if(value.equals("fluorescent-daylight"))return "荧光灯 日光";
        if(value.equals("flash"))return "闪光灯";
        if(value.equals("underwater-auto"))return "水下自动";
        if(value.equals("color-temp"))return "色温";
        if(value.equals("custom"))return "自定义";
        return value;
    }
    String state(){
        Map<String,String> p=last;
        return "mode="+mode+" scene="+(p==null?null:p.get("scene-mode"))+" iso="+iso+" wb="+whiteBalance
            +" ev_index="+evIndex+" ev_step="+evStep+" range="+evMin+".."+evMax
            +" aperture="+(p==null?null:p.get("aperture"))
            +" shutter="+(p==null?null:p.get("shutter-speed-nume")+"/"+p.get("shutter-speed-deno"));
    }

    // ---- restore ------------------------------------------------------------------
    void close()throws Exception{
        ready=false;if(original==null)return;
        Map<String,String> saved=original;
        // Mode first: Sony may reset dependent fields while changing modes. A
        // mode the physical dial moved to stays, so camera and dial agree;
        // the original A/M/S aperture and shutter then no longer apply.
        String targetScene=dialMoved?dialScene:saved.get("scene-mode");
        Map<String,String> current=driver.read();
        if(targetScene!=null&&!targetScene.equals(current.get("scene-mode")))driver.write(one("scene-mode",targetScene));
        Map<String,String> restore=new LinkedHashMap<String,String>(saved);
        restore.remove("scene-mode");
        if(dialMoved){restore.remove("aperture");restore.remove("shutter-speed-nume");restore.remove("shutter-speed-deno");}
        String savedIso=restore.remove("iso-sensitivity");
        if(!restore.isEmpty())driver.write(restore);
        if(savedIso!=null&&number(driver.read().get("iso-sensitivity"))!=number(savedIso))driver.setIso(number(savedIso));
        Map<String,String> readback=driver.read();
        if(savedIso!=null)restore.put("iso-sensitivity",savedIso);
        if(targetScene!=null)restore.put("scene-mode",targetScene);
        StringBuilder differs=new StringBuilder();
        for(Map.Entry<String,String> e:restore.entrySet())
            if(!e.getValue().equals(readback.get(e.getKey())))differs.append(e.getKey()).append(' ');
        original=null;
        if(differs.length()>0)throw new IOException("exposure restore differs: "+differs.toString().trim());
    }
}
