package io.github.englandlobster.lutcamera;

import java.util.ArrayList;
import java.util.List;

/** Maps Sony rotary inputs to exposure actions. Pure: no Android types.
 *
 * Inputs are Sony scan codes, not per-model tables. Bodies report their dials
 * through ScalarInput key status; roles follow that order, so a top dial is
 * always the primary control and the next input is the secondary one:
 * A6300/A6000 top dial = DIAL1, control wheel = DIAL2 (device-recorded
 * 2026-11-12); A7 front/rear = DIAL1/DIAL2 with the control wheel as KURU. */
final class ControlLayout {
    enum Input { DIAL1, DIAL2, WHEEL, RING }
    enum Action { NONE, APERTURE, SHUTTER, EV, ISO }

    static final int DIAL1_STATUS=524,DIAL2_STATUS=527,KURU_STATUS=521,DIAL3_STATUS=633,RING_STATUS=647;
    static final int MODE_DIAL=572,MODE_CHANGE=624,MODE_FIRST=534,MODE_LAST=551,MODE_HFR=680,EXPOSURE_MODE_FIRST=628,EXPOSURE_MODE_LAST=631;

    private final List<Input> order=new ArrayList<Input>();

    /** @param available inputs the camera reported, in any order; empty means
     * unknown, which assumes the common two-input layout. */
    ControlLayout(List<Input> available){
        for(Input input:new Input[]{Input.RING,Input.DIAL1,Input.DIAL2,Input.WHEEL})
            if(available!=null&&available.contains(input))order.add(input);
        if(order.isEmpty()){order.add(Input.DIAL1);order.add(Input.DIAL2);}
    }

    /** Rotary scan/key code to input, or null. Clockwise is +1. */
    static Input input(int code){
        switch(code){
            case 525:case 526:return Input.DIAL1;
            case 528:case 529:return Input.DIAL2;
            case 522:case 523:case 634:case 635:return Input.WHEEL;
            case 648:case 649:return Input.RING;
            default:return null;
        }
    }
    static int direction(int code){
        switch(code){
            case 525:case 528:case 522:case 635:case 648:return 1;
            case 526:case 529:case 523:case 634:case 649:return -1;
            default:return 0;
        }
    }
    /** Physical PASM dial notifications. Sony applies the dial itself; an app
     * that leaves these keys to the framework can be closed by the launcher. */
    static boolean modeDial(int code){
        return code==MODE_DIAL||code==MODE_CHANGE||code==MODE_HFR||(code>=MODE_FIRST&&code<=MODE_LAST)
            ||(code>=EXPOSURE_MODE_FIRST&&code<=EXPOSURE_MODE_LAST);
    }

    /** Sony ISV_KEY_MODE_* dial position to an exposure mode, or null for
     * AUTO, scene, movie, panorama and memory-recall positions. */
    static ManualExposure.Mode dialMode(int code){
        switch(code){
            case 537:return ManualExposure.Mode.P;
            case 538:return ManualExposure.Mode.S;
            case 539:return ManualExposure.Mode.A;
            case 540:return ManualExposure.Mode.M;
            default:return null;
        }
    }
    static String dialName(int code){
        switch(code){
            case 535:return "AUTO";case 536:return "AUTO+";case 537:return "P";case 538:return "S";case 539:return "A";case 540:return "M";
            case 541:case 550:case 551:return "MR";case 542:return "全景";case 544:return "动画";case 545:return "SCN";
            case 547:return "连拍优先";case 680:return "HFR";case 548:return "闪光灯禁止";case 549:return "照片效果";
            default:return "?";
        }
    }
    int inputs(){return order.size();}
    /** An input the status probe missed is appended, so its role stays stable. */
    int role(Input input){
        int index=order.indexOf(input);
        if(index<0){order.add(input);index=order.size()-1;}
        return index;
    }
    /** True when one input must share two functions; centre switches them. */
    boolean shared(ManualExposure.Mode mode){return order.size()==1&&mode!=ManualExposure.Mode.P;}

    Action action(ManualExposure.Mode mode,Input input,boolean alternate){
        int role=role(input);
        if(order.size()==1){
            switch(mode){
                case A:return alternate?Action.EV:Action.APERTURE;
                case S:return alternate?Action.EV:Action.SHUTTER;
                case M:return alternate?Action.SHUTTER:Action.APERTURE;
                default:return Action.EV;
            }
        }
        if(role>=2)return Action.ISO;
        switch(mode){
            case A:return role==0?Action.APERTURE:Action.EV;
            case S:return role==0?Action.SHUTTER:Action.EV;
            case M:return role==0?Action.APERTURE:Action.SHUTTER;
            default:return Action.EV;
        }
    }

    /** Short on-screen legend for the current mode. */
    String hint(ManualExposure.Mode mode,boolean alternate){
        StringBuilder text=new StringBuilder();
        String[] names=names();
        for(int i=0;i<order.size()&&i<3;i++){
            Action action=action(mode,order.get(i),alternate);
            if(action==Action.NONE)continue;
            if(text.length()>0)text.append(" · ");
            text.append(names[i]).append(' ').append(label(action));
        }
        if(shared(mode))text.append(" · 中键切换");
        return text.toString();
    }
    /** A6300: DIAL1 top dial, DIAL2 control wheel. A7: DIAL1/DIAL2 front/rear
     * dials plus the KURU control wheel. RX100: lens control ring. */
    private String[] names(){
        String[] result=new String[order.size()];
        boolean wheel=order.contains(Input.WHEEL);
        for(int i=0;i<order.size();i++){
            Input input=order.get(i);
            if(order.size()==1)result[i]="拨轮";
            else if(input==Input.RING)result[i]="控制环";
            else if(input==Input.WHEEL)result[i]="控制轮";
            else if(input==Input.DIAL1)result[i]=wheel&&order.contains(Input.DIAL2)?"前拨轮":"拨轮";
            else result[i]=wheel?"后拨轮":"控制轮";
        }
        return result;
    }
    static String label(Action action){
        switch(action){
            case APERTURE:return "光圈";
            case SHUTTER:return "快门";
            case EV:return "EV";
            case ISO:return "ISO";
            default:return "";
        }
    }
}
