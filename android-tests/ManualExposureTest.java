package io.github.englandlobster.lutcamera;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Host model of Sony's exposure parameters: P/A/S/M with dependent resets,
 * stepped aperture/shutter, ISO and WB readback, a physical dial and a
 * parameter that silently ignores writes. As on the A6300 (probe,
 * 2026-11-19), A and M share one manual aperture and S and M one manual
 * shutter; other modes report metered values in their place. */
public final class ManualExposureTest {
    static final List<String> APERTURES=Arrays.asList("280","320","350","400","450","500","560","630","710","800");
    static final List<String> SHUTTERS=Arrays.asList("1/4000","1/2000","1/1000","1/500","1/250","1/125","1/60","1/30");
    static class Camera implements ManualExposure.Driver,FocusControls.Driver {
        final Map<String,String> values=new LinkedHashMap<String,String>();
        boolean ignoreIso,ignoreWb;int writes;final StringBuilder scenes=new StringBuilder();
        /** The A6300 ignores absolute aperture/shutter writes (2026-11-12);
         * only the increment/decrement steps move them. */
        boolean acceptAbsolute;
        final java.util.Set<String> rejectScenes=new java.util.HashSet<String>();
        Camera(String scene){
            String[] pairs={"scene-mode-values=program-auto,aperture-priority,shutter-speed,manual-exposure,auto",
                "iso-sensitivity-values=0,100,200,400,800,1600,3200","iso-sensitivity=0",
                "whitebalance-values=auto,daylight,shade,cloudy-daylight,incandescent,custom,color-temp","whitebalance=auto",
                "exposure-compensation=0","min-exposure-compensation=-9","max-exposure-compensation=9","exposure-compensation-step=0.33333",
                "aperture=560","shutter-speed-nume=1","shutter-speed-deno=125","auto-exposure-lock=false",
                "jpeg-quality=95","picture-size=6000x4000","metering-mode=center-spot",
                "focus-area-type=multi","focus-area-type-values=multi,zone,center,flex-spot,local,spot","focus-point-x=0","focus-point-y=0",
                "face-detection-mode=off","face-detection-mode-values=off,auto"};
            for(String item:pairs){int at=item.indexOf('=');values.put(item.substring(0,at),item.substring(at+1));}
            values.put("scene-mode",scene);publish();
        }
        /** Manual values the camera keeps across modes, and what metering shows. */
        String manualAperture="560",manualShutter="1/125";
        static final String METERED_APERTURE="350",METERED_SHUTTER="1/60";
        boolean showsAperture(){String s=values.get("scene-mode");return s.equals("aperture-priority")||s.equals("manual-exposure");}
        boolean showsShutter(){String s=values.get("scene-mode");return s.equals("shutter-speed")||s.equals("manual-exposure");}
        void publish(){
            values.put("aperture",showsAperture()?manualAperture:METERED_APERTURE);
            String[] v=(showsShutter()?manualShutter:METERED_SHUTTER).split("/");
            values.put("shutter-speed-nume",v[0]);values.put("shutter-speed-deno",v[1]);
        }
        void aperture(String v){manualAperture=v;publish();}
        /** Reads after a mode change that still show the previous mode's
         * aperture and shutter (100–355 ms on the A6300). */
        int switchLagReads;
        /** Reads after a step that still return the value before it (a
         * readback that lags the step), and a fake clock for the waits. */
        int lagReads;private int staleReads;private Map<String,String> stale;long clock;
        public Map<String,String> read(){
            Map<String,String> r=new LinkedHashMap<String,String>(values);
            if(staleReads>0){staleReads--;for(String k:new String[]{"aperture","shutter-speed-nume","shutter-speed-deno"})r.put(k,stale.get(k));}
            return r;
        }
        private void stepped(Map<String,String> before){if(lagReads>0){stale=before;staleReads=lagReads;}}
        public void pause(int ms){clock+=ms;}
        public long millis(){return clock;}
        public void write(Map<String,String> p){
            writes++;
            for(Map.Entry<String,String> e:p.entrySet()){
                String k=e.getKey(),v=e.getValue();
                if(k.equals("scene-mode"))scenes.append(v).append(' ');
                if(k.equals("scene-mode")&&rejectScenes.contains(v))continue;
                if(k.equals("scene-mode")&&!v.equals(values.get("scene-mode"))){
                    // Sony may reset dependent fields while changing modes.
                    values.put("iso-sensitivity","0");values.put("exposure-compensation","0");values.put("whitebalance","auto");
                }
                if(k.equals("iso-sensitivity")&&ignoreIso)continue;
                if(k.equals("whitebalance")&&ignoreWb)continue;
                if((k.equals("aperture")||k.startsWith("shutter-speed-"))&&!acceptAbsolute)continue;
                if(k.equals("aperture")&&!APERTURES.contains(v))continue;
                if(k.equals("scene-mode")&&!v.equals(values.get("scene-mode"))&&switchLagReads>0){
                    Map<String,String> before=new LinkedHashMap<String,String>(values);values.put(k,v);publish();
                    before.put("scene-mode",v);stale=before;staleReads=switchLagReads;continue;
                }
                values.put(k,v);
                if(k.equals("scene-mode"))publish();
                if(k.equals("aperture"))manualAperture=v;
                if(k.startsWith("shutter-speed-"))manualShutter=values.get("shutter-speed-nume")+"/"+values.get("shutter-speed-deno");
            }
        }
        String shutter(){return values.get("shutter-speed-nume")+"/"+values.get("shutter-speed-deno");}
        /** Steps act on the manual values only in a mode that shows them. */
        public void stepAperture(int d){
            if(!showsAperture())return;
            stepped(new LinkedHashMap<String,String>(values));int i=APERTURES.indexOf(manualAperture);
            if(i>=0)aperture(APERTURES.get(Math.max(0,Math.min(APERTURES.size()-1,i+d))));
        }
        public void stepShutter(int d){
            if(!showsShutter())return;
            stepped(new LinkedHashMap<String,String>(values));int i=SHUTTERS.indexOf(manualShutter);
            manualShutter=SHUTTERS.get(Math.max(0,Math.min(SHUTTERS.size()-1,i-d)));publish();
        }
        public void setIso(int iso){if(!ignoreIso)values.put("iso-sensitivity",Integer.toString(iso));}
        /** Areas the camera refuses, and a body that ignores the point keys. */
        final java.util.Set<String> rejectAreas=new java.util.HashSet<String>();boolean ignorePoint,dropPointOnAreaChange;
        public void setFocusArea(String area,int x,int y){
            if(rejectAreas.contains(area))throw new RuntimeException("setParameters failed");
            boolean changed=!area.equals(values.get("focus-area-type"));
            values.put("focus-area-type",area);
            if(ignorePoint||dropPointOnAreaChange&&changed)return;
            if(FocusControls.FLEX.equals(area)){values.put("focus-point-x",Integer.toString(x/50*50));values.put("focus-point-y",Integer.toString(y/50*50));}
        }
        public void setFaceDetection(String mode){values.put("face-detection-mode",mode);}
        /** The physical dial: Sony writes the scene itself. */
        void dial(String scene){Map<String,String> p=new LinkedHashMap<String,String>();p.put("scene-mode",scene);write(p);}
    }
    static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
    static ManualExposure.Memory none(){return new ManualExposure.Memory();}

    public static void main(String[] args)throws Exception{
        // Dial at A: keep A, keep the camera's own settings, nothing written.
        Camera c=new Camera("aperture-priority");c.values.put("iso-sensitivity","400");Map<String,String> before=c.read();
        ManualExposure e=new ManualExposure(c,null);e.open(none(),true);
        check(e.mode()==ManualExposure.Mode.A&&c.writes==0,"follow dial without writes");
        check(e.step(ControlLayout.Action.APERTURE,2)&&c.values.get("aperture").equals("710"),"aperture steps");
        check(!e.step(ControlLayout.Action.SHUTTER,1),"shutter is metered in A");
        check(e.step(ControlLayout.Action.EV,3)&&e.compensationLabel().equals("+1.0 EV"),"EV in A");
        check(e.step(ControlLayout.Action.ISO,1)&&c.values.get("iso-sensitivity").equals("800"),"ISO steps");
        e.setWhiteBalance("daylight");check(c.values.get("whitebalance").equals("daylight"),"WB set");
        check(e.whiteBalanceValues().contains("custom")==false&&e.whiteBalanceValues().contains("auto"),"only direct WB presets");
        // Physical dial to M: Sony resets ISO/WB/EV; session values return.
        c.dial("manual-exposure");check(e.sync(),"dial change detected");
        check(e.mode()==ManualExposure.Mode.M&&c.values.get("iso-sensitivity").equals("800")&&c.values.get("whitebalance").equals("daylight"),"session values kept across dial");
        check(e.step(ControlLayout.Action.SHUTTER,1)&&c.shutter().equals("1/250"),"shutter in M");
        check(!e.evAvailable()&&!e.step(ControlLayout.Action.EV,1),"no EV in M with fixed ISO");
        e.refresh();ManualExposure.Memory m=e.memory(); // as the activity does before saving
        e.close();
        check(c.values.get("scene-mode").equals("manual-exposure"),"dial position kept on exit");
        check(c.manualAperture.equals("560")&&c.manualShutter.equals("1/125"),"manual aperture/shutter restored in the dial's mode "+c.manualAperture+" "+c.manualShutter);
        check(c.values.get("iso-sensitivity").equals("400")&&c.values.get("whitebalance").equals("auto")&&c.values.get("exposure-compensation").equals("0"),"own settings restored");
        check(c.values.get("jpeg-quality").equals(before.get("jpeg-quality"))&&c.values.get("metering-mode").equals("center-spot"),"unrelated settings untouched");

        // Memory round trip and next session on the same dial position.
        ManualExposure.Memory decoded=ManualExposure.Memory.decode(m.encode());
        check(decoded.mode==ManualExposure.Mode.M&&decoded.iso==800&&"daylight".equals(decoded.whiteBalance)&&"1/250".equals(decoded.shutter)&&"710".equals(decoded.aperture),"memory encodes "+m.encode());
        check(ManualExposure.Memory.decode("mode=X;iso=-5;wb=Bad Value;aperture=f2;shutter=fast;ev=x").encode().length()==0,"malformed memory dropped");
        Camera next=new Camera("manual-exposure");before=next.read();ManualExposure n=new ManualExposure(next,null);n.open(decoded,true);
        check(next.values.get("aperture").equals("710")&&next.shutter().equals("1/250")&&next.values.get("iso-sensitivity").equals("800")
            &&next.values.get("whitebalance").equals("daylight"),"remembered M values applied");
        n.close();check(before.equals(next.values),"restore after remembered session "+next.values);

        // No physical dial: remembered mode applies and is restored on exit.
        Camera body=new Camera("program-auto");before=body.read();ManualExposure b=new ManualExposure(body,null);
        ManualExposure.Memory s=new ManualExposure.Memory();s.mode=ManualExposure.Mode.S;b.open(s,false);
        check(b.mode()==ManualExposure.Mode.S&&body.values.get("scene-mode").equals("shutter-speed"),"remembered S uses advertised scene name");
        b.selectMode(ManualExposure.Mode.M);check(body.values.get("scene-mode").equals("manual-exposure"),"app mode selection");
        b.close();check(before.equals(body.values),"app-selected mode restored "+body.values);

        // Dial on AUTO: run as P, return the camera to AUTO on exit.
        Camera auto=new Camera("auto");before=auto.read();ManualExposure a=new ManualExposure(auto,null);a.open(none(),true);
        check(a.mode()==ManualExposure.Mode.P&&"auto".equals(a.fallbackFrom())&&auto.values.get("scene-mode").equals("program-auto"),"AUTO runs as P");
        a.close();check(before.equals(auto.values),"AUTO restored");
        // Dial moved to AUTO mid-session.
        Camera moved=new Camera("program-auto");ManualExposure mv=new ManualExposure(moved,null);mv.open(none(),true);
        moved.dial("auto");mv.sync();check(mv.mode()==ManualExposure.Mode.P&&"auto".equals(mv.fallbackFrom()),"dial to AUTO falls back to P");
        mv.close();check(moved.values.get("scene-mode").equals("auto"),"exit agrees with dial at AUTO");

        // Dial position followed by the app (Sony does not apply it while an app owns the camera).
        Camera owned=new Camera("program-auto");owned.values.put("iso-sensitivity","400");before=owned.read();
        ManualExposure ow=new ManualExposure(owned,null);ow.open(none(),true);
        check(ow.follow(ManualExposure.Mode.M,"M")&&owned.values.get("scene-mode").equals("manual-exposure")
            &&owned.values.get("iso-sensitivity").equals("400"),"follow writes M and keeps session ISO");
        ow.follow(null,"SCN");
        check(ow.mode()==ManualExposure.Mode.P&&"SCN".equals(ow.fallbackFrom()),"SCN runs as P");
        ow.close();check(before.equals(owned.values),"non-PASM dial restores original P "+owned.values);
        Camera kept=new Camera("program-auto");ManualExposure kp=new ManualExposure(kept,null);kp.open(none(),true);
        kp.follow(ManualExposure.Mode.A,"A");kp.close();
        check(kept.values.get("scene-mode").equals("aperture-priority"),"exit leaves dial mode A");

        // Readback failures are reported, values unchanged.
        Camera stubborn=new Camera("program-auto");ManualExposure st=new ManualExposure(stubborn,null);st.open(none(),true);
        stubborn.ignoreWb=true;boolean rejected=false;try{st.setWhiteBalance("shade");}catch(Exception expected){rejected=true;}
        check(rejected&&"auto".equals(st.whiteBalance()),"WB readback mismatch rejected");
        rejected=false;try{st.setIso(123);}catch(Exception expected){rejected=true;}check(rejected,"unsupported ISO rejected");
        for(int i=0;i<40;i++)st.step(ControlLayout.Action.EV,1);check(stubborn.values.get("exposure-compensation").equals("9"),"EV upper bound");
        st.resetEv();check(stubborn.values.get("exposure-compensation").equals("0"),"EV reset");
        st.close();

        // Remembered value the camera refuses is skipped, not fatal.
        Camera refuse=new Camera("aperture-priority");ManualExposure.Memory bad=new ManualExposure.Memory();bad.aperture="9999";bad.iso=400;
        refuse.ignoreIso=true;ManualExposure r=new ManualExposure(refuse,null);r.open(bad,true);
        check(r.ready()&&refuse.values.get("aperture").equals("800")&&r.iso()==0,"unreachable aperture stops at the closest step: "+refuse.values.get("aperture"));
        r.close();

        // Remembered values are walked with Sony's steps from either side,
        // reversing when the body's step direction is the opposite guess.
        Camera walkA=new Camera("aperture-priority");ManualExposure.Memory wa=new ManualExposure.Memory();wa.aperture="320";
        ManualExposure w=new ManualExposure(walkA,null);w.open(wa,true);
        check(walkA.values.get("aperture").equals("320"),"aperture walked down "+walkA.values.get("aperture"));
        w.close();check(walkA.values.get("aperture").equals("560"),"original A aperture walked back");
        Camera walkS=new Camera("shutter-speed");ManualExposure.Memory ws=new ManualExposure.Memory();ws.shutter="1/30";
        ManualExposure w2=new ManualExposure(walkS,null);w2.open(ws,true);
        check(walkS.shutter().equals("1/30"),"shutter walked slower "+walkS.shutter());
        w2.close();check(walkS.shutter().equals("1/125"),"original S shutter walked back "+walkS.shutter());
        // A readback that lags each step: the exit walk waits instead of
        // reporting a stuck aperture (A7 II / A7 field logs, 2026-11).
        Camera lag=new Camera("aperture-priority");ManualExposure lg=new ManualExposure(lag,null);lg.open(none(),true);
        lg.step(ControlLayout.Action.APERTURE,3);check(lag.values.get("aperture").equals("800"),"stepped to f/8");
        lag.lagReads=4;lg.close();
        check(lag.values.get("aperture").equals("560")&&lag.clock>0,"lagging readback walked back "+lag.values.get("aperture"));
        // Readback that never moves within the settle time still fails, bounded.
        Camera stuck=new Camera("aperture-priority");ManualExposure sk=new ManualExposure(stuck,null);sk.open(none(),true);
        sk.step(ControlLayout.Action.APERTURE,3);stuck.lagReads=1000;rejected=false;
        try{sk.close();}catch(java.io.IOException expected){rejected=expected.getMessage().contains("aperture");}
        check(rejected&&stuck.clock<=3*ManualExposure.SETTLE_MS,"stuck readback reported, waits bounded: "+stuck.clock);
        // A lens without electronic aperture reports 0: no walk, restore matches.
        Camera manual=new Camera("aperture-priority");manual.aperture("0");final StringBuilder events=new StringBuilder();
        ManualExposure mn=new ManualExposure(manual,new ManualExposure.Log(){public void log(String n,String d){events.append(n).append(' ');}});
        mn.open(none(),true);mn.close();
        check(!events.toString().contains("exposure_restore_walk"),"manual lens restores without a walk error: "+events);

        // A session that did not restore (killed, or aperture stuck): the next
        // session restores the recorded native values for what is still the
        // app's, and keeps what was changed on the native camera since.
        Camera killed=new Camera("aperture-priority");killed.values.put("iso-sensitivity","400");
        ManualExposure k1=new ManualExposure(killed,null);k1.open(none(),true);
        Map<String,String> nativeValues=k1.original();
        k1.step(ControlLayout.Action.APERTURE,2);k1.setIso(1600);k1.setWhiteBalance("shade");k1.step(ControlLayout.Action.EV,3);
        Map<String,String> left=k1.left();
        check("710".equals(left.get("aperture")),"left aperture "+left);
        NativeSettings.Pending pending=NativeSettings.Pending.decode(NativeSettings.encode(nativeValues),NativeSettings.encode(left));
        check(pending!=null&&pending.original.equals(nativeValues),"native settings round trip "+NativeSettings.encode(nativeValues));
        killed.values.put("whitebalance","daylight"); // changed on the native camera in between
        ManualExposure k2=new ManualExposure(killed,null);k2.open(none(),true,pending);
        check(k2.original().get("iso-sensitivity").equals("400")&&k2.original().get("aperture").equals("560")
            &&k2.original().get("exposure-compensation").equals("0")&&k2.original().get("whitebalance").equals("daylight"),"leftovers adopted "+k2.original());
        k2.close();
        check(killed.values.get("iso-sensitivity").equals("400")&&killed.values.get("aperture").equals("560")
            &&killed.values.get("exposure-compensation").equals("0")&&killed.values.get("whitebalance").equals("daylight"),"native values restored "+killed.values);
        // A and M share the aperture: a leftover from A is recognised in M.
        Camera otherMode=new Camera("manual-exposure");otherMode.aperture(left.get("aperture"));
        ManualExposure k3=new ManualExposure(otherMode,null);k3.open(none(),true,pending);
        check(k3.original().get("aperture").equals("560"),"aperture from A adopted in M "+k3.original());k3.close();
        check(otherMode.manualAperture.equals("560"),"M aperture restored");
        // A session in P never sees the aperture: exit checks it from M and
        // returns to P; a session killed in P carries the record forward.
        Camera inP=new Camera("program-auto");inP.aperture(left.get("aperture"));
        ManualExposure k4=new ManualExposure(inP,null);k4.open(none(),true,pending);
        check(k4.original().get("aperture").equals("560")&&k4.left().get("aperture").equals("710"),"unseen aperture carried "+k4.original());
        k4.close();
        check(inP.manualAperture.equals("560")&&inP.values.get("scene-mode").equals("program-auto"),"leftover fixed from P "+inP.manualAperture+" "+inP.values.get("scene-mode"));
        // A value changed on the native camera since is kept.
        Camera changedNatively=new Camera("aperture-priority");changedNatively.aperture("400");
        ManualExposure k5=new ManualExposure(changedNatively,null);k5.open(none(),true,pending);k5.close();
        check(changedNatively.manualAperture.equals("400"),"native change kept");
        check(NativeSettings.Pending.decode("iso-sensitivity=400;",null)==null&&NativeSettings.decode("a=b c;x=1;=2;y=;").toString().equals("{x=1}"),"malformed native settings dropped");
        // Shutter numerator and denominator are adopted together.
        Map<String,String> saved=new LinkedHashMap<String,String>();saved.put("scene-mode","shutter-speed");saved.put("shutter-speed-nume","1");saved.put("shutter-speed-deno","250");
        Map<String,String> o=new LinkedHashMap<String,String>(saved),l=new LinkedHashMap<String,String>(saved);o.put("shutter-speed-deno","60");
        NativeSettings.adopt(saved,new NativeSettings.Pending(o,l),"shutter-speed-nume+shutter-speed-deno");
        check(saved.get("shutter-speed-deno").equals("60"),"shutter adopted "+saved);
        // Focus leftovers.
        Camera fc=new Camera("program-auto");FocusControls f1=new FocusControls(fc,null);f1.open(new FocusControls.Memory());
        Map<String,String> nativeFocus=f1.original();f1.setArea("center");
        NativeSettings.Pending pf=new NativeSettings.Pending(nativeFocus,NativeSettings.project(fc.read(),nativeFocus.keySet()));
        FocusControls f2=new FocusControls(fc,null);f2.open(new FocusControls.Memory(),pf);f2.close();
        check(fc.values.get("focus-area-type").equals("multi"),"focus leftover restored "+fc.values.get("focus-area-type"));

        // S -> M -> S within a session keeps each mode's own shutter.
        Camera modes=new Camera("shutter-speed");ManualExposure ms=new ManualExposure(modes,null);ms.open(none(),true);
        ms.step(ControlLayout.Action.SHUTTER,2);String sShutter=modes.shutter();
        ms.follow(ManualExposure.Mode.A,"A");ms.step(ControlLayout.Action.APERTURE,-2);String aAperture=modes.values.get("aperture");
        ms.follow(ManualExposure.Mode.S,"S");check(modes.shutter().equals(sShutter),"S shutter returns "+modes.shutter()+" expected "+sShutter);
        ms.follow(ManualExposure.Mode.A,"A");check(modes.values.get("aperture").equals(aAperture),"A aperture returns");
        ms.close();
        check(modes.manualAperture.equals("560")&&modes.manualShutter.equals("1/125"),"both manual values restored "+modes.manualAperture+" "+modes.manualShutter);

        // The reported case: dial P -> A in the app, aperture changed, exit.
        // The native camera's A must show its own aperture again.
        Camera report=new Camera("program-auto");report.switchLagReads=3;before=report.read();
        ManualExposure rp=new ManualExposure(report,null);rp.open(none(),true);
        rp.follow(ManualExposure.Mode.A,"A");rp.step(ControlLayout.Action.APERTURE,2);
        check(report.manualAperture.equals("710"),"stepped in A");
        rp.follow(ManualExposure.Mode.S,"S");rp.step(ControlLayout.Action.SHUTTER,1);
        rp.close();
        check(report.values.get("scene-mode").equals("shutter-speed")&&report.manualAperture.equals("560")&&report.manualShutter.equals("1/125"),
            "app's aperture and shutter not left for the native camera: "+report.manualAperture+" "+report.manualShutter);
        // Remembered values applied on entering A from P: the native value is
        // the one after the switch shows, not P's metered reading.
        Camera fromP=new Camera("program-auto");fromP.switchLagReads=3;
        ManualExposure.Memory wantA=new ManualExposure.Memory();wantA.aperture="400";
        ManualExposure fp=new ManualExposure(fromP,null);fp.open(wantA,true);
        fp.follow(ManualExposure.Mode.A,"A");
        check(fromP.manualAperture.equals("400")&&fp.original().get("aperture").equals("560"),"native seen after switch "+fp.original()+" camera "+fromP.manualAperture);
        check("400".equals(fp.memory().aperture),"memory keeps the session aperture");
        fp.follow(ManualExposure.Mode.P,"P");fp.close();
        check(fromP.manualAperture.equals("560")&&fromP.values.get("scene-mode").equals("program-auto"),"restored via M, back in P "+fromP.manualAperture);
        // Nothing changed: no visit to M.
        Camera idle=new Camera("program-auto");idle.switchLagReads=3;ManualExposure id=new ManualExposure(idle,null);id.open(none(),true);
        id.follow(ManualExposure.Mode.A,"A");id.follow(ManualExposure.Mode.P,"P");idle.scenes.setLength(0);id.close();
        check(idle.scenes.length()==0&&idle.manualAperture.equals("560"),"untouched session changes no mode on exit: "+idle.scenes);

        // A remembered mode the camera refuses is skipped; startup continues.
        Camera noS=new Camera("program-auto");noS.rejectScenes.add("shutter-speed");
        ManualExposure.Memory wantS=new ManualExposure.Memory();wantS.mode=ManualExposure.Mode.S;
        ManualExposure ns=new ManualExposure(noS,null);ns.open(wantS,false);
        check(ns.ready()&&ns.mode()==ManualExposure.Mode.P&&noS.values.get("scene-mode").equals("program-auto"),"refused remembered mode skipped");
        check(ns.memory().mode==ManualExposure.Mode.P,"memory overwritten with the working mode");
        ns.close();

        Camera unsupported=new Camera("program-auto");unsupported.values.put("iso-sensitivity-values","100,200");before=unsupported.read();
        rejected=false;try{new ManualExposure(unsupported,null).open(none(),true);}catch(Exception expected){rejected=true;}
        check(rejected&&before.equals(unsupported.values),"missing auto ISO rejected before writes");
        System.out.println("ManualExposureTest passed: dial follow, P/A/S/M steps, session values, memory, AUTO fallback, restore, readback");
    }
}
