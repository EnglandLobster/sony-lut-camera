package org.hongen.sonylutprobe;
import java.io.IOException;

public final class CaptureJpegQualityTest {
    static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
    public static void main(String[] args)throws Exception{
        check(CaptureJpegQuality.select("50,25")==50,"reported A6000 capabilities select FINE");
        check(CaptureJpegQuality.select("25, 95,50")==95,"EXTRA FINE remains preferred when advertised");
        check(CaptureJpegQuality.select("25")==25,"STANDARD-only body keeps its highest known tier");
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
        System.out.println("PASS JPEG capability tiers: A6000 FINE, newer EXTRA FINE, STANDARD, missing capability and per-stage readback");
    }
}
