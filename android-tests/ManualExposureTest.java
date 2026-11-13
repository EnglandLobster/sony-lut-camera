package io.github.englandlobster.lutcamera;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Host model of Sony's exposure parameters: P/A/S/M with dependent resets,
 * stepped aperture/shutter, ISO and WB readback, a physical dial and a
 * parameter that silently ignores writes. */
public final class ManualExposureTest {
    static final List<String> APERTURES=Arrays.asList("280","320","350","400","450","500","560","630","710","800");
    static final List<String> SHUTTERS=Arrays.asList("1/4000","1/2000","1/1000","1/500","1/250","1/125","1/60","1/30");
    static class Camera implements ManualExposure.Driver,FocusControls.Driver {
        final Map<String,String> values=new LinkedHashMap<String,String>();
        boolean ignoreIso,ignoreWb;int writes;
        /** The A6300 ignores absolute aperture/shutter writes (2026-11-12);
         * only the increment/decrement steps move them. */
        boolean acceptAbsolute;
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
            values.put("scene-mode",scene);
        }
        public Map<String,String> read(){return new LinkedHashMap<String,String>(values);}
        public void write(Map<String,String> p){
            writes++;
            for(Map.Entry<String,String> e:p.entrySet()){
                String k=e.getKey(),v=e.getValue();
                if(k.equals("scene-mode")&&!v.equals(values.get("scene-mode"))){
                    // Sony may reset dependent fields while changing modes.
                    values.put("iso-sensitivity","0");values.put("exposure-compensation","0");values.put("whitebalance","auto");
                }
                if(k.equals("iso-sensitivity")&&ignoreIso)continue;
                if(k.equals("whitebalance")&&ignoreWb)continue;
                if((k.equals("aperture")||k.startsWith("shutter-speed-"))&&!acceptAbsolute)continue;
                if(k.equals("aperture")&&!APERTURES.contains(v))continue;
                values.put(k,v);
            }
        }
        String shutter(){return values.get("shutter-speed-nume")+"/"+values.get("shutter-speed-deno");}
        public void stepAperture(int d){
            int i=APERTURES.indexOf(values.get("aperture"));
            values.put("aperture",APERTURES.get(Math.max(0,Math.min(APERTURES.size()-1,i+d))));
        }
        public void stepShutter(int d){
            int i=SHUTTERS.indexOf(shutter());String[] v=SHUTTERS.get(Math.max(0,Math.min(SHUTTERS.size()-1,i-d))).split("/");
            values.put("shutter-speed-nume",v[0]);values.put("shutter-speed-deno",v[1]);
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
        ManualExposure.Memory m=e.memory();
        e.close();
        check(c.values.get("scene-mode").equals("manual-exposure"),"dial position kept on exit");
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
        // S -> M -> S within a session keeps each mode's own shutter.
        Camera modes=new Camera("shutter-speed");ManualExposure ms=new ManualExposure(modes,null);ms.open(none(),true);
        ms.step(ControlLayout.Action.SHUTTER,2);String sShutter=modes.shutter();
        ms.follow(ManualExposure.Mode.A,"A");ms.step(ControlLayout.Action.APERTURE,-2);String aAperture=modes.values.get("aperture");
        ms.follow(ManualExposure.Mode.S,"S");check(modes.shutter().equals(sShutter),"S shutter returns "+modes.shutter()+" expected "+sShutter);
        ms.follow(ManualExposure.Mode.A,"A");check(modes.values.get("aperture").equals(aAperture),"A aperture returns");
        ms.close();

        Camera unsupported=new Camera("program-auto");unsupported.values.put("iso-sensitivity-values","100,200");before=unsupported.read();
        rejected=false;try{new ManualExposure(unsupported,null).open(none(),true);}catch(Exception expected){rejected=true;}
        check(rejected&&before.equals(unsupported.values),"missing auto ISO rejected before writes");
        System.out.println("ManualExposureTest passed: dial follow, P/A/S/M steps, session values, memory, AUTO fallback, restore, readback");
    }
}
