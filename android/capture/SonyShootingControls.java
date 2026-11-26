package io.github.englandlobster.lutcamera;

import android.view.KeyEvent;
import java.util.List;
import static io.github.englandlobster.lutcamera.SonyControlAssignments.*;

/** Connects native assignments to the app's capture-owned camera session.
 * Menus and magnification have contextual navigation; releases always go back
 * to the function that received the press, even if that context has changed. */
final class SonyShootingControls {
    interface Host {
        boolean allowed();void changed();void display();void exit();void hint(String message);
        void failed(String operation,Exception error);
        void record(String event,String detail);
    }
    private final SonyControlAssignments assignments=new SonyControlAssignments(CameraInputs.ASSIGNMENTS);
    private final SonyCameraFunctions functions;
    private final CameraControlDriver driver;
    private final ManualExposure exposure;
    private final ViewfinderControls controls;
    private final CameraFocusMagnifier magnifier;
    private final Host host;
    private Integer lastEv;
    private boolean evPending;
    SonyShootingControls(CameraControlDriver driver,ManualExposure exposure,ViewfinderControls controls,
            CameraFocusMagnifier magnifier,Host host){
        functions=new SonyCameraFunctions(driver);this.driver=driver;this.exposure=exposure;this.controls=controls;
        this.magnifier=magnifier;this.host=host;
        controls.nativeBindings(assignments.resolve(103,exposure.mode())!=UNAVAILABLE);
        controls.additional(new ViewfinderControls.Additional(){
            public String[] labels(){return new String[]{"对焦模式","对焦放大","测光模式","闪光模式","返回 LUT 库"};}
            public void select(int index)throws Exception{
                switch(index){
                    case 0:parameter("对焦模式","focus-mode");break;
                    case 1:SonyShootingControls.this.controls.closePanel();SonyShootingControls.this.magnifier.next();break;
                    case 2:parameter("测光模式","metering-mode");break;
                    case 3:parameter("闪光模式","flash-mode");break;
                    default:SonyShootingControls.this.host.exit();
                }
            }
        });
    }
    void open()throws Exception{
        lastEv=evPosition(CameraInputs.status(EV_DIAL));
        host.record("native_control_assignments","dial="+CameraInputs.ASSIGNMENTS.setting("getDialSetting")
            +" dial_ev="+CameraInputs.ASSIGNMENTS.setting("getDialExposureCompensation")+" ev_position="+lastEv);
        if(lastEv!=null)exposure.followEvDial(lastEv,false);
    }
    /** Called after mode/ISO changes and whenever the capture gate reopens. */
    void flush()throws Exception{
        if(!host.allowed())return;
        functions.flush();
        Integer position=evPosition(CameraInputs.status(EV_DIAL));
        if(position!=null){
            boolean moved=lastEv==null||!lastEv.equals(position)||evPending;
            exposure.followEvDial(position,moved);lastEv=position;evPending=false;
        }
    }
    boolean release(KeyEvent event){
        if(event.getAction()!=KeyEvent.ACTION_UP)return false;
        int key=UiKeys.sonyCode(event);Integer function=functions.released(key);
        if(function==null)return false;
        if(SonyCameraFunctions.momentary(function)){
            try{if(host.allowed())functions.flush();}catch(Exception e){host.failed("native_release",e);}
        }
        return true;
    }
    boolean evDial(KeyEvent event){
        if(UiKeys.sonyCode(event)!=EV_DIAL)return false;
        if(event.getAction()==KeyEvent.ACTION_DOWN){
            evPending=true;
            try{flush();host.changed();}catch(Exception e){host.failed("ev_dial",e);}
        }
        return true;
    }
    boolean magnifierKey(KeyEvent event){
        if(!magnifier.active())return false;
        int action=UiKeys.key(event);ControlLayout.Input rotary=UiKeys.rotary(event);
        if(action!=UiKeys.LEFT&&action!=UiKeys.RIGHT&&action!=UiKeys.UP&&action!=UiKeys.DOWN
                &&action!=UiKeys.OK&&action!=UiKeys.BACK&&rotary==null)return false;
        if(event.getAction()!=KeyEvent.ACTION_DOWN||!host.allowed())return true;
        try{
            if(action==UiKeys.BACK)magnifier.stop();
            else if(action==UiKeys.OK){if(event.getRepeatCount()==0)magnifier.next();}
            else if(rotary!=null){int direction=UiKeys.rotaryDirection(event);
                magnifier.move(rotary==ControlLayout.Input.DIAL2?direction:0,rotary==ControlLayout.Input.DIAL2?0:direction);
            }else magnifier.move(action==UiKeys.LEFT?-1:action==UiKeys.RIGHT?1:0,action==UiKeys.UP?-1:action==UiKeys.DOWN?1:0);
        }catch(Exception e){host.failed("mf_key",e);}
        return true;
    }
    /** Call only outside panels/review, after handling shutter and mode events. */
    boolean key(KeyEvent event){
        int key=UiKeys.sonyCode(event);
        if(!candidate(key))return false;
        if(event.getAction()!=KeyEvent.ACTION_DOWN)return functions.pressed(key);
        int function=assignments.resolve(key,exposure.mode());
        if(function==UNAVAILABLE)return false; // A genuinely unavailable API, not an unassigned key.
        if(!host.allowed())return true;
        if(event.getRepeatCount()>0&&!repeatable(function))return true;
        if(event.getRepeatCount()==0){
            functions.pressed(key,function);
            host.record("native_control_key","scan="+key+" function="+function+" mode="+exposure.mode());
        }
        try{
            if(SonyCameraFunctions.momentary(function)){
                functions.hold(key,function,true);functions.flush();return true;
            }
            switch(function){
                case NONE:break;
                case ISO:controls.showIso();break;
                case WB:controls.showWhiteBalance();break;
                case EV:controls.showAdjust(ControlLayout.Action.EV);break;
                case MODE:
                    if(!controls.modeSelectable())host.hint("请使用机身模式转盘");else controls.showMode();break;
                case FOCUS_MODE:parameter("对焦模式","focus-mode");break;
                case METERING:parameter("测光模式","metering-mode");break;
                case FLASH:parameter("闪光模式","flash-mode");break;
                case FLASH_EV:parameter("闪光补偿","flash-compensation");break;
                case FOCUS_AREA:controls.showFocusArea();break;
                case FACE:controls.showFace();break;
                case MAGNIFY:magnifier.next();break;
                case AEL_TOGGLE:case AFMF_TOGGLE:functions.toggle(function);functions.flush();break;
                case DISP:host.display();break;
                case SETTINGS:controls.showSettings();break;
                case ENTER:case 1029:controls.focusStandard();break;
                case AP_PLUS:case AP_MINUS:controls.step(ControlLayout.Action.APERTURE,function==AP_PLUS?1:-1);break;
                case TV_PLUS:case TV_MINUS:controls.step(ControlLayout.Action.SHUTTER,function==TV_PLUS?1:-1);break;
                case EV_PLUS:case EV_MINUS:controls.step(ControlLayout.Action.EV,function==EV_PLUS?1:-1);break;
                case ISO_PLUS:case ISO_MINUS:controls.step(ControlLayout.Action.ISO,function==ISO_PLUS?1:-1);break;
                case PROGRAM_PLUS:case PROGRAM_MINUS:functions.programShift(function==PROGRAM_PLUS?1:-1);break;
                case TV_AV:controls.toggleShared();break;
                case TV_AV_PLUS:case TV_AV_MINUS:controls.stepShared(function==TV_AV_PLUS?1:-1);break;
                default:host.hint("此相机按键功能尚未支持 ("+function+")");
            }
            if(controls.panelOpen()&&magnifier.active())magnifier.stop();
            flush();host.changed();
        }catch(Exception e){host.failed("native_key_"+key+"_"+function,e);}
        return true;
    }
    private static boolean candidate(int key){
        return ControlLayout.input(key)!=null||key==103||key==105||key==106||key==108||key==232
            ||key==520||key==532||key==533||key==588||key==595||key==602||key==603
            ||key==604||key==605||key==607||key==608||key==622||key==623||key==632||key==638||key==653;
    }
    private void parameter(String title,final String key)throws Exception{
        List<String> values=functions.values(key);
        if(values.isEmpty()){host.hint("本机不支持此设置");return;}
        String[] labels=new String[values.size()];
        for(int i=0;i<labels.length;i++)labels[i]=driver.settingLabel(key,values.get(i));
        controls.showNative(title,values,functions.value(key),new ViewfinderControls.Selection(){
            public void apply(String value)throws Exception{functions.set(key,value);}
        });
        controls.nativeLabels(labels);
    }
    void close()throws Exception{functions.close();}
}
