package io.github.englandlobster.lutcamera;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import static io.github.englandlobster.lutcamera.SonyControlAssignments.*;

/** Native protocol fixtures and capture-boundary regressions, no photos/LUTs. */
public final class SonyControlsTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static class Source implements SonyControlAssignments.Source {
        Map<Integer,Integer> logic=new HashMap<Integer,Integer>(),custom=new HashMap<Integer,Integer>(),status=new HashMap<Integer,Integer>();
        int category;Integer dial=1,ev=0;
        public Integer logic(int key,int category){this.category=category;return logic.get(key);}
        public Integer custom(int key){return custom.get(key);}
        public Integer status(int key){return status.get(key);}
        public Integer setting(String key){return key.equals("getDialSetting")?dial:ev;}
    }
    static class Camera extends ManualExposureTest.Camera implements SonyCameraFunctions.Driver {
        boolean af,hold,failAf;int program;
        Camera(){super("aperture-priority");values.put("focus-mode","auto");values.put("focus-mode-values","auto,manual,dmf");values.put("auto-focus-mode","af-s");}
        public void programShift(int direction){program+=direction;}
        public void toggleFocus(boolean enabled)throws Exception{if(failAf)throw new Exception("AF/MF refused");af=enabled;}
        public void focusHold(boolean enabled){hold=enabled;}
        public java.util.List<String> settingValues(String key){String v=values.get(key+"-values");return v==null?Collections.<String>emptyList():Arrays.asList(v.split(","));}
        public String setting(String key){return values.get(key);}
        public void setting(String key,String value){values.put(key,value);}
    }
    static class Magnify implements FocusMagnifier.Driver {
        int starts,stops,level;boolean failStop;
        public void start(int level,int x,int y){starts++;this.level=level;}
        public void stop()throws Exception{if(failStop)throw new Exception("stop refused");stops++;}
    }
    public static void main(String[] args)throws Exception{
        assignments();exposure();holds();magnifier();buttonOnly();
        System.out.println("PASS Sony assignments, EV dial ownership, held-key cleanup, MF assist and button-only exposure");
    }
    static void assignments(){
        Source source=new Source();SonyControlAssignments resolver=new SonyControlAssignments(source);
        source.logic.put(622,1026);source.custom.put(622,31);
        check(resolver.resolve(622,ManualExposure.Mode.A)==MAGNIFY&&source.category==1,"C1 uses camera focus-magnifier assignment");
        source.custom.put(622,10);check(resolver.resolve(622,ManualExposure.Mode.S)==ISO&&source.category==2,"C1 reassigned to ISO");
        source.custom.put(622,2);check(resolver.resolve(622,ManualExposure.Mode.P)==NONE,"unassigned stays unassigned");
        source.custom.put(622,999);check(resolver.resolve(622,ManualExposure.Mode.P)==UNSUPPORTED,"unknown assignment never becomes hard-coded ISO/EV");
        source.logic.put(525,1028);source.logic.put(528,1028);source.ev=0;
        check(resolver.resolve(525,ManualExposure.Mode.P)==PROGRAM_PLUS,"P dial shifts program, not EV");
        check(resolver.resolve(528,ManualExposure.Mode.A)==AP_PLUS,"A rear dial aperture when dial EV disabled");
        source.ev=2;source.status.put(EV_DIAL,0);
        check(resolver.resolve(528,ManualExposure.Mode.A)==EV_PLUS,"native dial EV setting honoured at zero");
        source.status.put(EV_DIAL,515);
        check(resolver.resolve(528,ManualExposure.Mode.A)==AP_PLUS,"marked EV dial overrides software EV role");
        check(evPosition(515)==-3&&evPosition(6)==6&&evPosition(null)==null,"Sony EV sign-bit encoding");
        check(resolver.resolve(525,ManualExposure.Mode.M)==AP_PLUS,"native M front aperture");
        source.dial=2;check(resolver.resolve(525,ManualExposure.Mode.M)==TV_PLUS&&resolver.resolve(528,ManualExposure.Mode.M)==AP_PLUS,"native M dial swap");
        source.status.put(589,532);source.logic.put(532,1026);source.custom.put(532,20);
        check(resolver.resolve(638,ManualExposure.Mode.A)==AEL_HOLD,"AF/MF-AEL lever selects AEL binding");
        source.status.put(589,533);source.logic.put(533,1026);source.custom.put(533,25);
        check(resolver.resolve(638,ManualExposure.Mode.A)==AFMF_HOLD,"lever selects AF/MF binding");
        check(resolver.resolve(106,ManualExposure.Mode.A)==UNAVAILABLE,"unavailable API distinguished from no assignment");
        source.custom.put(106,13);check(resolver.resolve(106,ManualExposure.Mode.A)==WB,"legacy custom API fallback");
        source.logic.put(607,1024);check(resolver.resolve(607,ManualExposure.Mode.A)==MAGNIFY,"dedicated focus magnifier key");
        source.dial=null;check(resolver.resolve(525,ManualExposure.Mode.M)==UNSUPPORTED,"missing dial setting never invents a different mapping");
    }
    static void exposure()throws Exception{
        Camera camera=new Camera();ManualExposure e=new ManualExposure(camera,null);e.open(new ManualExposure.Memory(),true);
        e.followEvDial(-3,true);check(e.compensationLabel().equals("-1.0 EV"),"EV dial is displayed");
        e.resetEv();e.step(ControlLayout.Action.EV,1);check(camera.values.get("exposure-compensation").equals("-3"),"software cannot contradict marked dial");
        e.follow(ManualExposure.Mode.S,"S");check(camera.values.get("exposure-compensation").equals("-3"),"mode change preserves dial EV");
        e.followEvDial(0,true);e.step(ControlLayout.Action.EV,2);check(camera.values.get("exposure-compensation").equals("2"),"zero re-enables native software EV");
        e.follow(ManualExposure.Mode.M,"M");e.setIso(200);e.followEvDial(6,true);
        check(!e.evAvailable(),"M fixed ISO defers EV application");
        e.setIso(0);e.followEvDial(6,false);check(camera.values.get("exposure-compensation").equals("6"),"auto ISO applies deferred physical EV");
        e.close();check(camera.values.get("exposure-compensation").equals("6"),"exit respects dial moved during session");
    }
    static void holds()throws Exception{
        Camera camera=new Camera();SonyCameraFunctions f=new SonyCameraFunctions(camera);
        f.hold(622,AEL_HOLD,true);f.flush();check(camera.values.get("auto-exposure-lock").equals("true"),"AEL hold");
        f.hold(623,AEL_HOLD,true);f.hold(622,AEL_HOLD,false);f.flush();check(camera.values.get("auto-exposure-lock").equals("true"),"second AEL button still held");
        f.pressed(623,AEL_HOLD);
        check(f.released(623)==AEL_HOLD&&!f.pressed(623),"release uses original pressed binding even after a context change");
        check(camera.values.get("auto-exposure-lock").equals("true"),"release during capture does not write");
        f.flush();check(camera.values.get("auto-exposure-lock").equals("false"),"deferred release flushed at gate reopening");
        f.toggle(AFMF_TOGGLE);f.flush();check(camera.af,"AF/MF toggle uses native operation");
        f.hold(653,FOCUS_HOLD,true);f.flush();check(camera.hold,"lens focus hold");
        f.set("focus-mode","manual");f.set("metering-mode","multi");
        f.set("auto-focus-mode","af-c");
        camera.failAf=true;boolean failed=false;try{f.close();}catch(Exception expected){failed=true;}
        check(failed&&!camera.hold&&camera.values.get("focus-mode").equals("auto")&&camera.values.get("metering-mode").equals("center-spot"),"cleanup continues when AF/MF reset fails");
        check(camera.values.get("auto-focus-mode").equals("af-s"),"autofocus submode restored as well as MF/AF selection");
        camera.failAf=false;f.close();check(!camera.af,"failed cleanup can be retried");
    }
    static void magnifier()throws Exception{
        Magnify driver=new Magnify();FocusMagnifier m=new FocusMagnifier(driver,Arrays.asList(0,4,2,2));
        m.ring(true,100,-100,false,true,true,0,2000);check(!m.active(),"camera MF assist off respected");
        m.ring(false,0,0,true,true,true,0,2000);
        m.ring(true,100,-100,true,true,false,0,2000);check(!m.active(),"ring callback cannot start during capture");
        m.ring(false,0,0,true,true,true,0,2000);
        m.ring(true,100,-100,true,true,true,100,2000);check(m.active()&&driver.level==2&&m.x()==100,"automatic assist at reported focus point; opaque level IDs");
        m.changed(true,550,2,100,-100);m.move(100,-100);check(m.x()<=819&&m.y()>=-819,"movement bounded using actual magnification, not level ID");
        m.ring(false,0,0,true,true,true,200,2000);m.expire(2199);check(m.active(),"timeout not early");
        m.expire(2200);check(!m.active()&&driver.stops==1,"camera MF assist duration respected");
        m.next();m.next();check(driver.level==4,"manual key advances native levels");
        m.shutter(true);check(!m.active(),"half-press stops magnifier");
        m.changed(true,550,2,0,0);check(!m.active(),"late callback does not resurrect zoom after shutter");
        m.shutter(false);m.next();m.changed(false,0,0,0,0);
        check(m.active(),"late OFF cannot abandon a newly requested native zoom");
        driver.failStop=true;boolean failed=false;try{m.stop();}catch(Exception expected){failed=true;}
        check(failed&&m.active(),"failed native stop remains owned for teardown retry");
        driver.failStop=false;m.stop();check(!m.active(),"stop retry");
        FocusMagnifier absent=new FocusMagnifier(driver,Collections.<Integer>emptyList());check(!absent.supported(),"unsupported magnifier capability");
    }
    static void buttonOnly()throws Exception{
        Camera camera=new Camera();ManualExposure e=new ManualExposure(camera,null);e.open(new ManualExposure.Memory(),false);
        ViewfinderControlsTest.Host host=new ViewfinderControlsTest.Host();
        ViewfinderControls controls=new ViewfinderControls(e,null,new ControlLayout(Collections.<ControlLayout.Input>emptyList()),false,host);
        controls.additional(new ViewfinderControls.Additional(){public String[] labels(){return new String[0];}public void select(int index){}});
        controls.showSettings();String[] rows=controls.rowLabels();int aperture=Arrays.asList(rows).indexOf("光圈");
        check(aperture>=0,"no-dial camera exposes aperture editor in menu");
        for(int i=0;i<aperture;i++)controls.press(UiKeyActions.DOWN);
        controls.press(UiKeyActions.OK);controls.press(UiKeyActions.RIGHT);check(camera.values.get("aperture").equals("630"),"aperture adjusted without rotary events");
        controls.press(UiKeyActions.BACK);controls.showMode();controls.press(UiKeyActions.DOWN);controls.press(UiKeyActions.DOWN);controls.press(UiKeyActions.OK);
        check(e.mode()==ManualExposure.Mode.M,"mode selection without mode dial");
        controls.selectMode(ControlLayout.selectionMode(629));
        check(e.mode()==ManualExposure.Mode.A&&controls.modeSelectable(),"dedicated mode command never invents a physical dial");
        controls.selectMode(ControlLayout.selectionMode(631));
        controls.showAdjust(ControlLayout.Action.SHUTTER);controls.press(UiKeyActions.UP);check(camera.shutter().equals("1/250"),"button-only shutter");
        controls.showAdjust(ControlLayout.Action.EV);controls.press(UiKeyActions.RIGHT);check(e.compensationLabel().equals("+0.3 EV"),"button-only EV with auto ISO");
        check(host.failure==null,"no control errors: "+host.failure);e.close();
        Camera limited=new Camera();limited.values.put("scene-mode","program-auto");limited.values.put("scene-mode-values","program-auto");
        ManualExposure onlyP=new ManualExposure(limited,null);onlyP.open(new ManualExposure.Memory(),false);
        check(onlyP.supports(ManualExposure.Mode.P)&&!onlyP.supports(ManualExposure.Mode.A)&&!onlyP.supports(ManualExposure.Mode.M),"mode choices follow advertised capability");
        onlyP.close();
    }
}
