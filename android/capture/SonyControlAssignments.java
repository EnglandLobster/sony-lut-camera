package io.github.englandlobster.lutcamera;

/** Sony physical keys -> shooting functions. Numbers are Scalar API protocol
 * values, not model-specific guesses. See docs/SONY_CONTROLS.md for sources. */
final class SonyControlAssignments {
    static final int UNAVAILABLE=-1,UNSUPPORTED=-2,NONE=1025;
    static final int ISO=1042,WB=1045,EV=1035,FOCUS_MODE=1038,FOCUS_AREA=1039,
        FACE=1040,METERING=1043,FLASH=1037,FLASH_EV=1044,MAGNIFY=1063,
        AEL_HOLD=1052,AEL_TOGGLE=1053,AFMF_HOLD=1057,AFMF_TOGGLE=1058,
        FOCUS_HOLD=1189,DISP=1081,ENTER=1156,MODE=2001,SETTINGS=2002;
    static final int AP_PLUS=1032,AP_MINUS=1184,TV_PLUS=1154,TV_MINUS=1185,
        EV_PLUS=1031,EV_MINUS=1182,PROGRAM_PLUS=1152,PROGRAM_MINUS=1183,
        ISO_PLUS=2003,ISO_MINUS=2004,TV_AV=1176,TV_AV_PLUS=1186,TV_AV_MINUS=1187;
    static final int EV_DIAL=620;
    interface Source {
        Integer logic(int key,int category);
        Integer custom(int key);
        Integer setting(String name);
        Integer status(int key);
    }
    private final Source source;
    SonyControlAssignments(Source source){this.source=source;}

    int resolve(int key,ManualExposure.Mode mode){
        // The AF/MF-AEL lever selects which button assignment is in effect.
        if(key==638){Integer lever=source.status(589);if(lever!=null&&(lever==532||lever==533))key=lever;}
        if(key==520)return SETTINGS;
        Integer logical=source.logic(key,mode.ordinal()); // P=0, A=1, S=2, M=3
        if(logical==null){
            // Older platforms have no logical-key API, but may expose custom keys.
            Integer custom=source.custom(key);
            return custom==null||custom==0?UNAVAILABLE:custom(custom);
        }
        if(logical==1026||logical==1027){
            Integer assigned=source.custom(key);
            return assigned==null?UNSUPPORTED:custom(assigned);
        }
        if(logical==1028)return exposureDial(key,mode);
        if(logical<0)return UNSUPPORTED;
        if(logical==1024||logical==1214){
            // Unchanged means a fixed hardware key, rather than a custom
            // assignment. These dedicated keys have unambiguous functions.
            switch(key){
                case 602:return EV;case 603:return ISO;case 604:return WB;
                case 605:return 1036;case 607:return MAGNIFY;case 608:return DISP;
                case 653:return FOCUS_HOLD;default:return NONE;
            }
        }
        if(logical==1196)return FOCUS_HOLD;
        return logical;
    }

    private int exposureDial(int key,ManualExposure.Mode mode){
        ControlLayout.Input input=ControlLayout.input(key);
        if(input!=ControlLayout.Input.DIAL1&&input!=ControlLayout.Input.DIAL2)return NONE;
        int direction=ControlLayout.direction(key);
        boolean first=input==ControlLayout.Input.DIAL1;
        if(mode==ManualExposure.Mode.M){
            Integer setting=source.setting("getDialSetting");
            if(setting==null)return UNSUPPORTED;
            boolean aperture=first==(setting==1);
            return aperture?(direction>0?AP_PLUS:AP_MINUS):(direction>0?TV_PLUS:TV_MINUS);
        }
        Integer ev=source.status(EV_DIAL),assigned=source.setting("getDialExposureCompensation");
        if(assigned==null)return UNSUPPORTED;
        // Sony gives the marked EV dial priority away from its zero position.
        if((ev==null||ev==0)&&assigned==(first?1:2))return direction>0?EV_PLUS:EV_MINUS;
        if(mode==ManualExposure.Mode.A)return direction>0?AP_PLUS:AP_MINUS;
        if(mode==ManualExposure.Mode.S)return direction>0?TV_PLUS:TV_MINUS;
        return direction>0?PROGRAM_PLUS:PROGRAM_MINUS;
    }

    static int custom(int value){
        switch(value){
            case 1:return DISP;case 2:return NONE;case 3:return EV;
            case 4:return 1036;case 5:return FLASH;case 6:case 33:return FOCUS_MODE;
            case 7:return FOCUS_AREA;case 8:case 9:return FACE;case 10:return ISO;
            case 11:return METERING;case 12:return FLASH_EV;case 13:return WB;
            case 14:return 1046;case 15:return 1047;case 16:return 1048;
            case 17:return 1049;case 18:return 1050;
            case 20:case 22:return AEL_HOLD;case 21:case 23:return AEL_TOGGLE;
            case 25:return AFMF_HOLD;case 26:return AFMF_TOGGLE;
            case 31:return MAGNIFY;case 32:return MODE;case 35:return 1064;
            case 47:return 1106;case 51:return SETTINGS;case 60:return FOCUS_HOLD;
            default:return UNSUPPORTED;
        }
    }
    static Integer evPosition(Integer raw){
        if(raw==null)return null;
        return (raw&512)!=0?-(raw-512):raw;
    }
    static boolean repeatable(int function){
        return function==AP_PLUS||function==AP_MINUS||function==TV_PLUS||function==TV_MINUS
            ||function==EV_PLUS||function==EV_MINUS||function==PROGRAM_PLUS||function==PROGRAM_MINUS
            ||function==ISO_PLUS||function==ISO_MINUS||function==TV_AV_PLUS||function==TV_AV_MINUS;
    }
}
