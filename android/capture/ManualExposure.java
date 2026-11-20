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
 * except that a mode the user moved the physical dial to is left in place.
 *
 * Manual aperture and shutter outlive the mode: A and M share one aperture,
 * S and M one shutter, and both survive a visit to P (A6300 2.01 probe,
 * 2026-11-19). Elsewhere the camera reports metered values in their place.
 * The session therefore tracks the manual values only from a mode that shows
 * them, and exit restores them from such a mode whatever mode it leaves. */
final class ManualExposure {
    enum Mode { P, A, S, M }
    interface Driver extends ParameterDriver {
        void stepAperture(int direction) throws Exception;
        void stepShutter(int direction) throws Exception;
        void setIso(int iso) throws Exception;
        /** Wait for a step's readback; the camera driver sleeps. */
        void pause(int ms) throws Exception;
        long millis();
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
    /** The session's aperture and shutter for A/M and S/M: the previous
     * session's until first applied, then whatever the camera holds. */
    private String rememberedAperture,rememberedShutter;
    private boolean apertureApplied,shutterApplied;
    /** Manual values as the camera last showed them, and as the native
     * camera had them before this session changed them; null until seen. */
    private String cameraAperture,cameraShutter,nativeAperture,nativeShutter;
    private boolean apertureTouched,shutterTouched;
    private NativeSettings.Pending pending;
    private Map<String,String> closedOriginal;
    /** Start of an unsettled aperture/shutter readback, and its value before. */
    private long changedAt=-1;private String changedFrom;
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
    private static boolean showsAperture(Mode m){return m==Mode.A||m==Mode.M;}
    private static boolean showsShutter(Mode m){return m==Mode.S||m==Mode.M;}
    private static String shutterOf(Map<String,String> p){
        String n=p.get("shutter-speed-nume"),d=p.get("shutter-speed-deno");
        return number(n)>0&&number(d)>0?n+"/"+d:null;
    }
    private static String signature(Map<String,String> p){return p==null?null:p.get("aperture")+" "+shutterOf(p);}
    /** After a mode write or a step, scene-mode reads back at once but
     * aperture and shutter keep the previous values for 100–355 ms, then
     * change together (A6300 2.01, 2026-11-19). Until then the readback is
     * not taken as the camera's manual values. */
    private void changing(){if(changedAt<0)changedFrom=signature(last);changedAt=driver.millis();}
    private Map<String,String> read()throws Exception{
        last=driver.read();
        if(changedAt>=0&&driver.millis()-changedAt>=SETTLE_MS){changedAt=-1;changedFrom=null;}
        if(changedAt<0)observe(last);
        return last;
    }
    /** Readback once aperture and shutter caught up with the last change:
     * they differ from before it, or {@link #SETTLE_MS} passed. */
    private Map<String,String> settled()throws Exception{
        Map<String,String> p=read();
        while(changedAt>=0&&signature(p).equals(changedFrom)){driver.pause(SETTLE_POLL_MS);p=read();}
        if(changedAt>=0){changedAt=-1;changedFrom=null;observe(p);}
        return p;
    }
    /** Takes the manual values from a mode that shows them. The first sight
     * is the native value, unless an earlier session left it there. */
    private void observe(Map<String,String> p){
        if(original==null||p==null)return;
        Mode m=fromScene(p.get("scene-mode"));
        if(showsAperture(m)&&number(p.get("aperture"))>0){
            cameraAperture=p.get("aperture");
            if(apertureApplied)rememberedAperture=cameraAperture;
            if(nativeAperture==null){
                Map<String,String> seen=one("aperture",cameraAperture);
                List<String> adopted=NativeSettings.adopt(seen,pending,"aperture");
                if(!adopted.isEmpty())log("exposure_native_recovered",adopted.toString());
                nativeAperture=seen.get("aperture");original.put("aperture",nativeAperture);
                log("exposure_native_seen","aperture="+nativeAperture+" scene="+p.get("scene-mode"));
            }
        }
        String shutter=shutterOf(p);
        if(showsShutter(m)&&shutter!=null){
            cameraShutter=shutter;
            if(shutterApplied)rememberedShutter=cameraShutter;
            if(nativeShutter==null){
                Map<String,String> seen=new LinkedHashMap<String,String>();
                seen.put("shutter-speed-nume",p.get("shutter-speed-nume"));seen.put("shutter-speed-deno",p.get("shutter-speed-deno"));
                List<String> adopted=NativeSettings.adopt(seen,pending,SHUTTER_KEY);
                if(!adopted.isEmpty())log("exposure_native_recovered",adopted.toString());
                nativeShutter=seen.get("shutter-speed-nume")+"/"+seen.get("shutter-speed-deno");
                original.putAll(seen);
                log("exposure_native_seen","shutter="+nativeShutter+" scene="+p.get("scene-mode"));
            }
        }
    }
    private static final String SHUTTER_KEY="shutter-speed-nume+shutter-speed-deno";
    private void writeScene(String scene)throws Exception{changing();driver.write(one("scene-mode",scene));wroteScene=true;}
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
    void open(final Memory remembered,boolean physicalDial)throws Exception{open(remembered,physicalDial,null);}
    /** Native values a session may leave behind, adopted at open; aperture
     * and shutter are adopted when first seen. Exposure lock is momentary
     * and never adopted. */
    private static final String[] NATIVE_KEYS={"iso-sensitivity","exposure-compensation","whitebalance"};
    /** @param pending native settings an earlier session did not restore, or null */
    void open(final Memory remembered,boolean physicalDial,NativeSettings.Pending pending)throws Exception{
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
        Mode initial=fromScene(scene);
        if(p.containsKey("auto-exposure-lock"))saved.put("auto-exposure-lock",p.get("auto-exposure-lock"));
        List<String> adopted=NativeSettings.adopt(saved,pending,NATIVE_KEYS);
        if(!adopted.isEmpty())log("exposure_native_recovered",adopted.toString());
        this.pending=pending;
        original=saved; // Keep rollback data even if a vendor write partially fails.
        observe(p); // manual aperture/shutter when the camera is in A/S/M
        dialScene=scene;
        rememberedAperture=remembered.aperture;rememberedShutter=remembered.shutter;

        Mode target=initial;
        if(target==null){
            // A non-P/A/S/M position must run as P; failing that is fatal.
            target=Mode.P;fallbackFrom=scene;
            writeScene(SCENE_P);
            p=read();
            if(!SCENE_P.equals(p.get("scene-mode")))throw new IOException("exposure mode readback differs: "+SCENE_P);
        }else if(!physicalDial&&remembered.mode!=null&&supports(remembered.mode)&&remembered.mode!=initial){
            // The remembered mode is a convenience: a refusal keeps the
            // camera's mode instead of failing every later start.
            final Mode wanted=remembered.mode;
            try{
                writeScene(scene(wanted));
                p=read();
                if(!scene(wanted).equals(p.get("scene-mode")))throw new IOException("exposure mode readback "+p.get("scene-mode"));
                target=wanted;
            }catch(Exception e){
                notes.add("mode");log("exposure_restore_skipped","mode "+e);
                p=read();
                if(!scene.equals(p.get("scene-mode"))){writeScene(scene);p=read();}
                Mode actual=fromScene(p.get("scene-mode"));
                if(actual==null)throw new IOException("exposure mode unrecoverable: "+p.get("scene-mode"));
                target=actual;
            }
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
    /** The session's aperture/shutter for a mode that shows them, walked
     * from a settled readback (the native value is seen first). */
    private void reapplyModeValues(){
        if(!showsAperture(mode)&&!showsShutter(mode))return;
        attempt("readback",new Step(){public void run()throws Exception{settled();}});
        if(showsAperture(mode)&&rememberedAperture!=null)
            attempt("aperture",new Step(){public void run()throws Exception{moveAperture(rememberedAperture);}});
        if(showsShutter(mode)&&rememberedShutter!=null)
            attempt("shutter",new Step(){public void run()throws Exception{moveShutter(rememberedShutter);}});
        apertureApplied|=showsAperture(mode);shutterApplied|=showsShutter(mode);
        // From here the camera's values are the session's.
        attempt("readback",new Step(){public void run()throws Exception{settled();}});
    }

    // ---- absolute aperture/shutter through Sony's steps ---------------------------
    /** The A6300 ignores absolute aperture and shutter writes (readback kept
     * the old value, 2026-11-12), so absolute targets are reached with the
     * same increment/decrement steps the dials use. */
    private interface Reading { double of(Map<String,String> p); }
    private interface Stepper { void step(int direction) throws Exception; }
    private static final int WALK_LIMIT=60;
    static double apertureValue(Map<String,String> p){int v=number(p.get("aperture"));return v>0?v:Double.NaN;}
    static double shutterSeconds(Map<String,String> p){
        int n=number(p.get("shutter-speed-nume")),d=number(p.get("shutter-speed-deno"));
        return n>0&&d>0?(double)n/d:Double.NaN;
    }
    void moveAperture(String target)throws Exception{
        int value=number(target);
        if(value<=0)throw new IOException("invalid aperture "+target);
        walk("aperture",value,new Reading(){public double of(Map<String,String> p){return apertureValue(p);}},
            new Stepper(){public void step(int d)throws Exception{changing();apertureTouched=true;driver.stepAperture(d);}});
    }
    void moveShutter(String target)throws Exception{
        String[] parts=target.split("/");
        int n=parts.length==2?number(parts[0]):0,d=parts.length==2?number(parts[1]):0;
        if(n<=0||d<=0)throw new IOException("invalid shutter "+target);
        walk("shutter",(double)n/d,new Reading(){public double of(Map<String,String> p){return shutterSeconds(p);}},
            new Stepper(){public void step(int dir)throws Exception{changing();shutterTouched=true;driver.stepShutter(dir);}});
    }
    private static boolean same(double a,double b){return Math.abs(a-b)<=Math.abs(b)*1e-6;}
    /** How long a step may take to show in getParameters before the walk
     * treats the value as stuck. */
    static final int SETTLE_MS=600,SETTLE_POLL_MS=20;
    /** Reads after a step until the value differs from {@code before} or
     * {@link #SETTLE_MS} passes. A7 II 4.01 and A7 3.20 exit walks gave up
     * after steps whose readback never moved (NAS reports, 2026-11); a
     * readback that lags the step looks exactly like that. */
    private double settle(Reading reading,double before,StringBuilder trace)throws Exception{
        double next=reading.of(read());int waited=0;
        while(same(next,before)&&waited<SETTLE_MS){driver.pause(SETTLE_POLL_MS);waited+=SETTLE_POLL_MS;next=reading.of(read());}
        trace.append(' ').append(format(next));if(waited>0)trace.append('@').append(waited).append("ms");
        return next;
    }
    private static String format(double v){return v==Math.rint(v)?Long.toString((long)v):Double.toString(v);}
    /** Steps toward the target, reversing once if the first guess moved
     * away (the step direction is body-defined). Stops at the target, at a
     * lens or range limit, or after passing a target the body does not
     * offer; the last two are reported, never retried. Every walk is
     * logged with its readbacks so a failure on another body is legible. */
    private void walk(String what,double target,Reading reading,Stepper stepper)throws Exception{
        double now=reading.of(read());
        if(Double.isNaN(now))throw new IOException(what+" unreadable");
        if(same(now,target))return;
        StringBuilder trace=new StringBuilder(what).append(" target=").append(format(target)).append(" from=").append(format(now)).append(" steps:");
        long started=driver.millis();
        try{
            int direction=target>now?1:-1;boolean reversed=false;
            for(int i=0;i<WALK_LIMIT;i++){
                stepper.step(direction);trace.append(direction>0?" +":" -");
                double next=settle(reading,now,trace);
                if(Double.isNaN(next))throw new IOException(what+" unreadable after step");
                if(same(next,target)){trace.append(" ok");return;}
                if(same(next,now)){
                    if(reversed)break;
                    direction=-direction;reversed=true;continue; // limit in the guessed direction
                }
                boolean away=Math.abs(next-target)>Math.abs(now-target),passed=(now-target)*(next-target)<0;
                double previous=now;now=next;
                if(passed||away&&reversed){
                    // Leave the closer of the last two values.
                    if(Math.abs(previous-target)<Math.abs(now-target)){
                        stepper.step(-direction);trace.append(direction>0?" -":" +");now=settle(reading,now,trace);
                    }
                    break;
                }
                if(away){direction=-direction;reversed=true;}
            }
            trace.append(" stopped");
            throw new IOException(what+" stopped at "+format(now)+" target "+format(target));
        }finally{log("exposure_walk",trace.append(" ms=").append(driver.millis()-started).toString());}
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
        Map<String,String> p=driver.read();
        String scene=p.get("scene-mode");
        Mode next=fromScene(scene);
        if(scene!=null&&!scene.equals(scene(mode))){
            dialMoved=true;dialScene=scene;
            changing(); // Sony switched the mode; its values may still lag
        }
        p=read();
        if(next==null){
            fallbackFrom=scene;
            writeScene(SCENE_P);
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
        Map<String,String> p=changedAt>=0?settled():read(); // a step just before the dial counts
        if(!scene(next).equals(p.get("scene-mode"))){
            writeScene(scene(next));p=read();
            if(!scene(next).equals(p.get("scene-mode")))throw new IOException("exposure mode readback differs: "+scene(next));
        }
        mode=next;fallbackFrom=usable?null:label;
        // Leave the camera agreeing with the dial.
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
        if(changedAt>=0)settled();
        writeScene(scene(next));
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
    /** Lets the last step show before {@link #memory} or {@link #left}. */
    void refresh()throws Exception{settled();}

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
                changing();apertureTouched=true;
                for(int i=0;i<Math.abs(steps);i++)driver.stepAperture(steps>0?1:-1);
                String after=read().get("aperture");
                return after!=null&&!after.equals(before);
            }
            case SHUTTER:{
                if(mode!=Mode.S&&mode!=Mode.M)return false;
                String before=last.get("shutter-speed-nume")+"/"+last.get("shutter-speed-deno");
                changing();shutterTouched=true;
                for(int i=0;i<Math.abs(steps);i++)driver.stepShutter(steps>0?1:-1);
                Map<String,String> p=read();
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
    /** The native settings exit restores, also after exit; null before a
     * session. A manual value this session never saw keeps what an earlier
     * session recorded, so a later one can still restore it. */
    Map<String,String> original(){
        Map<String,String> o=original!=null?original:closedOriginal;
        if(o==null)return null;
        Map<String,String> out=new LinkedHashMap<String,String>(o);
        if(pending!=null){
            if(nativeAperture==null)carry(out,pending.original,"aperture");
            if(nativeShutter==null)carry(out,pending.original,"shutter-speed-nume","shutter-speed-deno");
        }
        return out;
    }
    private static void carry(Map<String,String> to,Map<String,String> from,String... keys){
        for(String k:keys)if(from.get(k)==null)return;
        for(String k:keys)to.put(k,from.get(k));
    }
    /** What the session leaves in the camera, in the keys of
     * {@link #original}: aperture and shutter are the manual values last seen
     * in a mode that shows them, never a metered reading. */
    Map<String,String> left()throws Exception{
        Map<String,String> o=original();if(o==null)return null;
        Map<String,String> out=NativeSettings.project(settled(),o.keySet());
        out.remove("aperture");out.remove("shutter-speed-nume");out.remove("shutter-speed-deno");
        if(cameraAperture!=null)out.put("aperture",cameraAperture);
        else if(pending!=null)carry(out,pending.left,"aperture");
        if(cameraShutter!=null){String[] v=cameraShutter.split("/");out.put("shutter-speed-nume",v[0]);out.put("shutter-speed-deno",v[1]);}
        else if(pending!=null)carry(out,pending.left,"shutter-speed-nume","shutter-speed-deno");
        return out;
    }
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
        // A mode the physical dial moved to stays, so camera and dial agree.
        String targetScene=dialMoved?dialScene:saved.get("scene-mode");
        Mode target=fromScene(targetScene);
        List<String> failed=new ArrayList<String>();
        settled();
        // Manual aperture/shutter first, from a mode that shows them: the
        // target mode if it shows both that need it, otherwise M.
        boolean aperture=restoreNeeded(nativeAperture,cameraAperture,apertureTouched,"aperture");
        boolean shutter=restoreNeeded(nativeShutter,cameraShutter,shutterTouched,SHUTTER_KEY);
        if(aperture||shutter){
            String visit=(!aperture||showsAperture(target))&&(!shutter||showsShutter(target))?targetScene:SCENE_M;
            try{
                if(!visit.equals(last.get("scene-mode")))writeScene(visit);
                Map<String,String> p=settled(); // a value never seen is seen here
                if(!visit.equals(p.get("scene-mode")))throw new IOException("scene readback "+p.get("scene-mode"));
                // A lens without electronic aperture reports 0: nothing to walk.
                if(aperture&&nativeAperture!=null)try{moveAperture(nativeAperture);}catch(Exception e){log("exposure_restore_walk","aperture "+e);}
                if(shutter&&nativeShutter!=null)try{moveShutter(nativeShutter);}catch(Exception e){log("exposure_restore_walk","shutter "+e);}
                p=settled();
                if(aperture&&nativeAperture!=null&&!nativeAperture.equals(p.get("aperture")))failed.add("aperture");
                if(shutter&&nativeShutter!=null&&!nativeShutter.equals(shutterOf(p)))failed.add("shutter");
            }catch(Exception e){log("exposure_restore_walk","mode "+visit+" "+e);failed.add("aperture/shutter");}
        }
        // Mode before the rest: Sony may reset dependent fields with it.
        if(targetScene!=null&&!targetScene.equals(driver.read().get("scene-mode")))writeScene(targetScene);
        Map<String,String> restore=new LinkedHashMap<String,String>(saved);
        restore.remove("scene-mode");
        restore.remove("aperture");restore.remove("shutter-speed-nume");restore.remove("shutter-speed-deno");
        String savedIso=restore.remove("iso-sensitivity");
        if(!restore.isEmpty())driver.write(restore);
        if(savedIso!=null&&number(driver.read().get("iso-sensitivity"))!=number(savedIso))driver.setIso(number(savedIso));
        Map<String,String> readback=driver.read();
        if(savedIso!=null)restore.put("iso-sensitivity",savedIso);
        if(targetScene!=null)restore.put("scene-mode",targetScene);
        StringBuilder differs=new StringBuilder();
        for(Map.Entry<String,String> e:restore.entrySet())
            if(!e.getValue().equals(readback.get(e.getKey())))differs.append(e.getKey()).append(' ');
        for(String f:failed)differs.append(f).append(' ');
        closedOriginal=saved;original=null;
        if(differs.length()>0)throw new IOException("exposure restore differs: "+differs.toString().trim());
    }
    /** A known native value is restored when the session moved it; one never
     * seen only when an earlier session recorded leaving it changed. */
    private boolean restoreNeeded(String nativeValue,String camera,boolean touched,String key){
        if(nativeValue!=null)return touched||!nativeValue.equals(camera);
        return NativeSettings.leftChanged(pending,key);
    }
}
