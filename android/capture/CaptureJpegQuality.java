package io.github.englandlobster.lutcamera;
import java.io.IOException;

/** Sony quality codes are discrete camera capabilities, not JPEG percentages. */
final class CaptureJpegQuality {
    static int select(String supported)throws IOException{
        return select(supported,null);
    }
    static int select(String supported,String platform)throws IOException{
        // Sony's platform version is hardware.API, not an Android SDK level.
        // API 0/1 has JPEG FINE (50) and STANDARD (25), but no quality list.
        // PictureQualityController/Environment in Sony's app uses that legacy
        // path; EXTRA FINE and quality enumeration require API >= 2.
        // See docs/compatibility/NEX5R_CONFIRMED_FIXES.md for source and field data.
        if(supported==null&&legacy(platform))return 50;
        if(supported==null)throw new IOException("JPEG quality capabilities unavailable");
        String values=","+supported.replace(" ","")+",";
        for(int quality:new int[]{95,50,25})if(values.contains(","+quality+","))return quality;
        throw new IOException("JPEG quality has no supported tier: "+supported);
    }
    private static boolean legacy(String platform){
        if(platform==null||!platform.matches("[0-9]+\\.[0-9]+(\\.[0-9]+)*"))return false;
        try{return Integer.parseInt(platform.split("\\.")[1])<2;}
        catch(NumberFormatException invalid){return false;}
    }
    static String label(int quality){
        switch(quality){case 95:return "EXTRA FINE";case 50:return "FINE";case 25:return "STANDARD";}
        throw new IllegalArgumentException("unknown Sony JPEG quality: "+quality);
    }
    static void verify(int expected,int actual)throws IOException{
        if(actual!=expected)throw new IOException("JPEG quality changed: expected="+expected+" actual="+actual);
    }
}
