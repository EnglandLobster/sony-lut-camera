package org.hongen.sonylutprobe;

import java.util.Locale;

/** Measured exposure values, independent of camera control and Android widgets. */
final class CaptureReadout {
    int shutterNumerator,shutterDenominator,aperture,autoIso;
    int battery=-1;
    boolean charging;

    String shutterText(){
        if(shutterNumerator<=0||shutterDenominator<=0)return "--";
        int a=shutterNumerator,b=shutterDenominator;
        while(b!=0){int remainder=a%b;a=b;b=remainder;}
        int n=shutterNumerator/a,d=shutterDenominator/a;
        if(n==1&&d>1)return "1/"+d;
        return trim(String.format(Locale.US,"%.3f",(double)n/d))+"s";
    }
    String apertureText(){
        return aperture<=0?"F--":"F"+trim(String.format(Locale.US,"%.1f",aperture/100.0));
    }
    String isoText(){return autoIso>0?"ISO "+autoIso:"ISO AUTO";}
    static int batteryPercent(int level,int scale){
        if(level<0||scale<=0||level>scale)return -1;
        return (int)((long)level*100/scale);
    }
    private static String trim(String value){
        while(value.endsWith("0")&&value.indexOf('.')>=0)value=value.substring(0,value.length()-1);
        if(value.endsWith("."))value=value.substring(0,value.length()-1);
        return value;
    }
    String state(){return "shutter="+shutterText()+" aperture="+apertureText()+" iso="+autoIso+" battery="+battery+" charging="+charging;}
}
