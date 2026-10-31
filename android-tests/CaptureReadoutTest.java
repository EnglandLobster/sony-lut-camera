package org.hongen.sonylutprobe;

public final class CaptureReadoutTest {
    private static void equal(String actual,String expected){if(!actual.equals(expected))throw new AssertionError(actual+" != "+expected);}
    public static void main(String[] args){
        CaptureReadout v=new CaptureReadout();equal(v.shutterText(),"--");equal(v.apertureText(),"F--");equal(v.isoText(),"ISO AUTO");
        v.shutterNumerator=1;v.shutterDenominator=60;equal(v.shutterText(),"1/60");
        v.shutterNumerator=10;v.shutterDenominator=16000;equal(v.shutterText(),"1/1600");
        v.shutterNumerator=4;v.shutterDenominator=5;equal(v.shutterText(),"0.8s");
        v.shutterNumerator=13;v.shutterDenominator=10;equal(v.shutterText(),"1.3s");
        v.shutterNumerator=30;v.shutterDenominator=1;equal(v.shutterText(),"30s");
        v.shutterDenominator=0;equal(v.shutterText(),"--");
        v.aperture=350;equal(v.apertureText(),"F3.5");v.aperture=800;equal(v.apertureText(),"F8");
        v.autoIso=800;equal(v.isoText(),"ISO 800");v.autoIso=-1;equal(v.isoText(),"ISO AUTO");
        if(CaptureReadout.batteryPercent(42,100)!=42||CaptureReadout.batteryPercent(3,4)!=75||
            CaptureReadout.batteryPercent(-1,100)!=-1||CaptureReadout.batteryPercent(101,100)!=-1||CaptureReadout.batteryPercent(1,0)!=-1)
            throw new AssertionError("battery validation");
        System.out.println("PASS measured exposure units, shutter fractions/seconds, AUTO versus measured ISO, battery and missing values");
    }
}
