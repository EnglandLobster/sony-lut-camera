package io.github.englandlobster.lutcamera;

public final class ShutterConfirmationTest {
    static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
    public static void main(String[] args){
        check(ShutterConfirmation.waitMs(1,250)==1008,"1/250 waits the margin: "+ShutterConfirmation.waitMs(1,250));
        check(ShutterConfirmation.waitMs(1,2)==2000,"0.5 s counts twice");
        check(ShutterConfirmation.waitMs(30,1)==61000,"30 s with noise reduction");
        check(ShutterConfirmation.waitMs(0,1)==-1&&ShutterConfirmation.waitMs(1,0)==-1&&ShutterConfirmation.waitMs(-1,-1)==-1,"no readout: left alone");
        check(ShutterConfirmation.waitMs(65535,1)==-1&&ShutterConfirmation.waitMs(31,1)==-1,"bulb or longer than the camera's range: left alone");
        System.out.println("PASS shutter confirmation: wait covers the exposure twice plus a margin; unknown or bulb exposures are never withdrawn");
    }
}
