package io.github.englandlobster.lutcamera;
import java.io.IOException;

public final class CaptureJpegQualityTest {
    static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
    public static void main(String[] args)throws Exception{
        check(CaptureJpegQuality.select("50,25")==50,"reported A6000 capabilities select FINE");
        check(CaptureJpegQuality.select("25, 95,50")==95,"EXTRA FINE remains preferred when advertised");
        check(CaptureJpegQuality.select("25")==25,"STANDARD-only body keeps its highest known tier");
        // NEX-5R 1.00 field fixture: Sony platform 1.0, JPEG already 50,
        // jpeg-quality-values absent. The former select(null) blocked startup.
        int legacy=CaptureJpegQuality.select(null,"1.0");
        check(legacy==50,"NEX-5R starts with legacy FINE despite absent quality enumeration");
        CaptureJpegQuality.verify(legacy,50);
        check(CaptureJpegQuality.select(null,"1.1")==50,"Sony API 1 also predates quality enumeration");
        check(CaptureJpegQuality.select(null,"1.0.1")==50,"platform patch suffix does not change API component");
        check(CaptureJpegQuality.select("25","1.0")==25,"an explicit capability list takes precedence over legacy defaults");
        check(CaptureJpegQuality.select("95,50,25","2.4")==95,"modern highest quality selection remains intact");
        for(String platform:new String[]{null,"","null","1","1.x","1.-1","1.2","2.4","1.999999999999","1.0oops"}){
            boolean failed=false;try{CaptureJpegQuality.select(null,platform);}catch(IOException expected){failed=true;}
            check(failed,"legacy defaults do not mask missing capabilities on modern/unknown platform: "+platform);
        }
        for(String values:new String[]{"","100","195"}){
            boolean failed=false;try{CaptureJpegQuality.select(values,"1.0");}catch(IOException expected){failed=true;}
            check(failed,"explicit unsupported capabilities are not replaced by legacy defaults");
        }
        for(int rejected:new int[]{25,95}){
            boolean failed=false;try{CaptureJpegQuality.verify(legacy,rejected);}catch(IOException expected){failed=true;}
            check(failed,"legacy FINE selection still requires exact camera readback");
        }
        for(String values:new String[]{null,"","195","100"}){
            boolean failed=false;try{CaptureJpegQuality.select(values);}catch(IOException expected){failed=true;}
            check(failed,"missing/unknown quality capability must not be guessed");
        }
        for(int quality:new int[]{95,50,25}){
            CaptureJpegQuality.verify(quality,quality);
            boolean failed=false;try{CaptureJpegQuality.verify(quality,0);}catch(IOException expected){failed=true;}
            check(failed,"rejected setting or changed quality is not silently accepted");
        }
        check(CaptureJpegQuality.label(50).equals("FINE")&&CaptureJpegQuality.label(95).equals("EXTRA FINE"),"honest display label");
        System.out.println("PASS JPEG quality: NEX-5R legacy FINE, advertised tiers, modern/unknown capability failures and exact readback");
    }
}
