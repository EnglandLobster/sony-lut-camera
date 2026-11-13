package io.github.englandlobster.lutcamera;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class ViewfinderControlsTest {
    static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
    static final ControlLayout.Input D1=ControlLayout.Input.DIAL1,D2=ControlLayout.Input.DIAL2,WHEEL=ControlLayout.Input.WHEEL,RING=ControlLayout.Input.RING;
    static final ControlLayout.Action AP=ControlLayout.Action.APERTURE,SH=ControlLayout.Action.SHUTTER,EV=ControlLayout.Action.EV,ISO=ControlLayout.Action.ISO;
    static final ManualExposure.Mode P=ManualExposure.Mode.P,A=ManualExposure.Mode.A,S=ManualExposure.Mode.S,M=ManualExposure.Mode.M;

    static class Host implements ViewfinderControls.Host {
        int changed,wb;String hint="";String failure;
        public void changed(){changed++;}
        public void hint(String text){hint=text;}
        public void failed(String where,Exception error){failure=where+" "+error;}
        public void whiteBalanceChanged(){wb++;}
    }

    public static void main(String[] args)throws Exception{
        // Codes recorded on the A6300 (2026-11-12): top dial 525/526, control wheel 528/529.
        check(ControlLayout.input(525)==D1&&ControlLayout.direction(525)==1&&ControlLayout.direction(526)==-1,"DIAL1");
        check(ControlLayout.input(528)==D2&&ControlLayout.direction(528)==1&&ControlLayout.direction(529)==-1,"DIAL2");
        check(ControlLayout.input(522)==WHEEL&&ControlLayout.input(635)==WHEEL&&ControlLayout.input(648)==RING,"KURU/DIAL3/ring");
        check(ControlLayout.input(530)==null&&!ControlLayout.modeDial(530),"lens attach is not a control");
        check(ControlLayout.modeDial(572)&&ControlLayout.modeDial(624)&&ControlLayout.modeDial(537)&&!ControlLayout.modeDial(552),"PASM dial codes");

        // A6300/A6000: the chosen Sony-style table.
        ControlLayout two=new ControlLayout(Arrays.asList(D1,D2));
        check(two.action(P,D1,false)==EV&&two.action(P,D2,false)==EV,"P");
        check(two.action(A,D1,false)==AP&&two.action(A,D2,false)==EV,"A");
        check(two.action(S,D1,false)==SH&&two.action(S,D2,false)==EV,"S");
        check(two.action(M,D1,false)==AP&&two.action(M,D2,false)==SH,"M");
        check(two.hint(M,false).equals("拨轮 光圈 · 控制轮 快门"),two.hint(M,false));
        check(new ControlLayout(Collections.<ControlLayout.Input>emptyList()).action(A,D1,false)==AP,"unknown probe assumes two inputs");
        // A7: front/rear plus control wheel for ISO.
        ControlLayout three=new ControlLayout(Arrays.asList(WHEEL,D2,D1));
        check(three.action(M,D1,false)==AP&&three.action(M,D2,false)==SH&&three.action(M,WHEEL,false)==ISO,"A7 M");
        check(three.action(A,D2,false)==EV&&three.action(P,WHEEL,false)==ISO,"A7 A/P");
        check(three.hint(S,false).equals("前拨轮 快门 · 后拨轮 EV · 控制轮 ISO"),three.hint(S,false));
        // One control: centre switches the shared function.
        ControlLayout one=new ControlLayout(Arrays.asList(D2));
        check(one.action(A,D2,false)==AP&&one.action(A,D2,true)==EV&&one.action(M,D2,true)==SH&&one.action(P,D2,false)==EV,"single input");
        check(one.shared(M)&&!one.shared(P)&&!two.shared(M),"shared only with one input");
        // RX100: ring first, wheel second.
        ControlLayout ring=new ControlLayout(Arrays.asList(D2,RING));
        check(ring.action(A,RING,false)==AP&&ring.action(A,D2,false)==EV,"ring is primary");

        // Key routing over the host camera model.
        ManualExposureTest.Camera cam=new ManualExposureTest.Camera("manual-exposure");cam.values.put("iso-sensitivity","200");
        Map<String,String> before=cam.read();
        ManualExposure exposure=new ManualExposure(cam,null);exposure.open(new ManualExposure.Memory(),true);
        FocusControls focus=new FocusControls(cam,null);focus.open(new FocusControls.Memory());
        check(focus.areas().equals(Arrays.asList("multi","center","flex-spot","local")),"internal AF areas hidden, zone alias merged: "+focus.areas());
        check(FocusControls.areaLabel("local").equals("区"),"local is the zone area");
        Host host=new Host();
        ViewfinderControls v=new ViewfinderControls(exposure,focus,two,true,host);
        v.rotate(D1,1);check(cam.values.get("aperture").equals("630"),"top dial aperture in M");
        v.rotate(D2,-1);check(cam.shutter().equals("1/60"),"wheel shutter in M");
        check(v.modeLabel().equals("M")&&v.isoText(0).equals("ISO 200"),"HUD mode/ISO");
        // ISO list: ▶ opens at the current value, ▼ moves, centre applies.
        check(v.press(UiKeyActions.RIGHT)&&v.panel()==ViewfinderControls.Panel.ISO&&v.rowLabels()[v.selected()].equals("200"),"ISO list");
        v.press(UiKeyActions.DOWN);v.press(UiKeyActions.DOWN);v.press(UiKeyActions.OK);
        check(cam.values.get("iso-sensitivity").equals("800")&&!v.panelOpen(),"ISO applied");
        v.press(UiKeyActions.RIGHT);v.rotate(D2,-10);check(v.rowLabels()[v.selected()].equals("AUTO"),"dial moves list to AUTO");
        v.press(UiKeyActions.BACK);check(cam.values.get("iso-sensitivity").equals("800")&&!v.panelOpen(),"MENU cancels");
        // WB list rebases the preview baseline.
        v.press(UiKeyActions.LEFT);v.press(UiKeyActions.DOWN);v.press(UiKeyActions.OK);
        check(cam.values.get("whitebalance").equals("daylight")&&host.wb==1,"WB applied and baseline rebased");
        check(v.whiteBalanceText().equals("日光"),"WB label");
        // Settings: AF point, area, face; no mode row with a physical dial.
        v.press(UiKeyActions.DOWN);
        List<String> rows=Arrays.asList(v.rowLabels());
        check(rows.equals(Arrays.asList("移动对焦点","对焦区域","人脸优先")),"settings rows "+rows);
        v.press(UiKeyActions.OK);check(v.panel()==ViewfinderControls.Panel.POINT&&v.afEditing(),"enter point editing");
        v.press(UiKeyActions.RIGHT);v.press(UiKeyActions.RIGHT);v.press(UiKeyActions.UP);v.rotate(D2,1);
        check(v.afX()==200&&v.afY()==0&&cam.values.get("focus-area-type").equals("multi"),"local movement only");
        v.press(UiKeyActions.OK);
        check(cam.values.get("focus-area-type").equals("flex-spot")&&cam.values.get("focus-point-x").equals("200")&&!v.panelOpen()&&v.afVisible(),"point committed");
        // A point the camera did not keep: stay in the editor at the request.
        cam.ignorePoint=true;
        v.press(UiKeyActions.DOWN);v.press(UiKeyActions.OK);v.press(UiKeyActions.DOWN);v.press(UiKeyActions.OK);
        check(host.failure!=null&&host.failure.contains("对焦点未保存")&&v.panel()==ViewfinderControls.Panel.POINT
            &&v.afEditing()&&v.afY()==100&&cam.values.get("focus-point-y").equals("0"),"refused point keeps editor: "+host.failure);
        cam.ignorePoint=false;host.failure=null;v.press(UiKeyActions.OK);
        check(cam.values.get("focus-point-y").equals("100")&&!v.panelOpen()&&host.failure==null,"retry commits point");
        // Sony's frame edge inside -1000..1000: one axis pinned, the other moved.
        Map<String,String> b=new java.util.HashMap<String,String>(),a=new java.util.HashMap<String,String>();
        b.put("focus-area-type","flex-spot");b.put("focus-point-x","602");b.put("focus-point-y","402");
        a.putAll(b);a.put("focus-point-x","702");
        check(FocusControls.verify("flex-spot",702,502,b,a)==null,"edge-pinned axis accepted");
        check(FocusControls.verify("flex-spot",702,402,b,b)!=null,"unmoved point refused");
        check(FocusControls.verify("flex-spot",1000,402,b,a)==null,"edge rounding accepted");
        // An area the camera refuses leaves the list for this session.
        cam.rejectAreas.add("local");
        v.press(UiKeyActions.DOWN);v.press(UiKeyActions.DOWN);v.press(UiKeyActions.RIGHT);
        check(host.failure!=null&&host.failure.contains("已从列表移除")&&!focus.areas().contains("local")
            &&cam.values.get("focus-area-type").equals("flex-spot")&&v.panel()==ViewfinderControls.Panel.SETTINGS,"refused area removed: "+host.failure);
        host.failure=null;v.press(UiKeyActions.BACK);
        v.press(UiKeyActions.DOWN);v.press(UiKeyActions.DOWN);v.press(UiKeyActions.DOWN);v.press(UiKeyActions.RIGHT);
        check(cam.values.get("face-detection-mode").equals("auto")&&v.rowValues()[2].equals("开"),"face priority on");
        v.press(UiKeyActions.UP);v.press(UiKeyActions.LEFT);check(cam.values.get("focus-area-type").equals("center"),"area cycles back");
        v.press(UiKeyActions.BACK);
        // Centre resets EV only where it applies; shutter closes lists.
        v.press(UiKeyActions.RIGHT);v.shutter();check(!v.panelOpen(),"shutter closes list");
        check(host.failure==null,"no failures: "+host.failure);
        // Dial move followed through the controls.
        cam.dial("aperture-priority");v.dialChanged(-1);check(v.modeLabel().equals("A")&&host.hint.startsWith("A 光圈优先"),"dial hint "+host.hint);
        // A6300: Sony leaves scene-mode alone; the dial position (539=A, 538=S) drives it.
        v.dialChanged(538);check(v.modeLabel().equals("S")&&cam.values.get("scene-mode").equals("shutter-speed"),"dial position S written");
        v.dialChanged(535);check(v.modeLabel().equals("P")&&host.hint.contains("AUTO"),"AUTO position runs as P: "+host.hint);
        v.dialChanged(539);check(v.modeLabel().equals("A")&&cam.values.get("scene-mode").equals("aperture-priority"),"back to A");
        v.rotate(D2,3);check(v.compensationText().equals("+1.0 EV"),"wheel EV in A");
        v.press(UiKeyActions.OK);check(v.compensationText().equals("+0.0 EV"),"centre resets EV");
        FocusControls.Memory fm=FocusControls.Memory.decode(focus.memory().encode());
        check("center".equals(fm.area)&&"auto".equals(fm.face),"focus memory "+focus.memory().encode());
        focus.close();exposure.close();
        check(cam.values.get("focus-area-type").equals("multi")&&cam.values.get("focus-point-x").equals("0")&&cam.values.get("focus-point-y").equals("0")
            &&cam.values.get("face-detection-mode").equals("off")
            &&cam.values.get("iso-sensitivity").equals("200")&&cam.values.get("whitebalance").equals("auto"),"focus and exposure restored");

        // Sony switching to flex-spot drops the point of the same write: the
        // area is kept and stays in the list; the next point commit works.
        ManualExposureTest.Camera lag=new ManualExposureTest.Camera("program-auto");lag.dropPointOnAreaChange=true;
        FocusControls.Memory remembered=new FocusControls.Memory();remembered.area="flex-spot";remembered.x=400;remembered.y=200;
        FocusControls lf=new FocusControls(lag,null);lf.open(remembered);
        check(lf.area().equals("flex-spot")&&lf.areas().contains("flex-spot")&&lag.values.get("focus-area-type").equals("flex-spot"),"lagging point keeps area: "+lf.state());
        lf.beginPoint();lf.movePoint(1,0);lf.confirmPoint();
        check(lag.values.get("focus-point-x").equals("100"),"point commits inside flex-spot "+lag.values.get("focus-point-x"));
        lf.close();check(lag.values.get("focus-area-type").equals("multi")&&lag.values.get("focus-point-x").equals("0"),"lag restore");

        // No dial: mode row present and selects P/A/S/M.
        ManualExposureTest.Camera nodial=new ManualExposureTest.Camera("program-auto");
        ManualExposure ne=new ManualExposure(nodial,null);ne.open(new ManualExposure.Memory(),false);
        ViewfinderControls nv=new ViewfinderControls(ne,null,one,false,new Host());
        nv.press(UiKeyActions.DOWN);check(Arrays.asList(nv.rowLabels()).equals(Arrays.asList("曝光模式")),"mode row only");
        nv.press(UiKeyActions.RIGHT);check(nodial.values.get("scene-mode").equals("aperture-priority"),"mode row selects A");
        nv.press(UiKeyActions.BACK);nv.rotate(D2,2);check(nodial.values.get("aperture").equals("710"),"single input aperture");
        nv.press(UiKeyActions.OK);nv.rotate(D2,1);check(ne.compensationLabel().equals("+0.3 EV"),"centre switches to EV");
        ne.close();check(nodial.values.get("scene-mode").equals("program-auto"),"app mode restored");
        System.out.println("ViewfinderControlsTest passed: Sony dial codes, layouts, lists, AF point, face priority, dial follow, memory");
    }
}
