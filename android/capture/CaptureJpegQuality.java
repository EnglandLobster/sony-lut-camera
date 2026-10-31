package org.hongen.sonylutprobe;
import java.io.IOException;

/** Sony quality codes are discrete camera capabilities, not JPEG percentages. */
final class CaptureJpegQuality {
    static int select(String supported)throws IOException{
        if(supported==null)throw new IOException("JPEG quality capabilities unavailable");
        String values=","+supported.replace(" ","")+",";
        for(int quality:new int[]{95,50,25})if(values.contains(","+quality+","))return quality;
        throw new IOException("JPEG quality has no supported tier: "+supported);
    }
    static String label(int quality){
        switch(quality){case 95:return "EXTRA FINE";case 50:return "FINE";case 25:return "STANDARD";}
        throw new IllegalArgumentException("unknown Sony JPEG quality: "+quality);
    }
    static void verify(int expected,int actual)throws IOException{
        if(actual!=expected)throw new IOException("JPEG quality changed: expected="+expected+" actual="+actual);
    }
}
