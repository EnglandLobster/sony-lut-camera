package io.github.englandlobster.lutcamera;

import java.io.IOException;

/** One negotiated geometry for settings, YC, JPEG validation and review. */
final class CaptureGeometry {
    final int width,height;
    CaptureGeometry(int width,int height){
        if(width<2||width%2!=0||height<1||width>10000||height>10000)
            throw new IllegalArgumentException("invalid capture geometry");
        this.width=width;this.height=height;
    }
    static CaptureGeometry parse(String value)throws IOException{
        if(value==null||!value.matches("[0-9]{2,5}x[0-9]{2,5}"))throw new IOException("invalid picture-size: "+value);
        try{String[] p=value.split("x");return new CaptureGeometry(Integer.parseInt(p[0]),Integer.parseInt(p[1]));}
        catch(IllegalArgumentException e){throw new IOException("invalid picture-size: "+value,e);}
    }
    static CaptureGeometry select(String values,String current,String memory,int pixels)throws IOException{
        // The advertised largest full-size geometry determines native aspect,
        // not an incomplete list of platform labels (notably HX/WX 18MP).
        CaptureGeometry best=null;
        String candidates=values==null||values.trim().length()==0?current:values;
        if(candidates==null)throw new IOException("picture-size capabilities unavailable");
        for(String value:candidates.split(",")){
            CaptureGeometry candidate=parse(value.trim());long area=(long)candidate.width*candidate.height;
            // Full-size only. Do not silently use a cropped ratio or a small JPEG.
            boolean nativeRatio=nearRatio(candidate,3,2)||nearRatio(candidate,4,3);
            if(!nativeRatio||area<pixels*900000L||area>pixels*1100000L)continue;
            if(best==null||area>(long)best.width*best.height)best=candidate;
        }
        if(best==null)throw new IOException("picture-size: full sensor geometry unavailable for "+pixels+"MP");
        return best;
    }
    private static boolean nearRatio(CaptureGeometry value,int numerator,int denominator){
        return Math.abs((long)value.width*denominator-(long)value.height*numerator)*100<=(long)value.height*numerator*2;
    }
    void verify(int width,int height)throws IOException{
        if(this.width!=width||this.height!=height)throw new IOException("capture geometry changed: expected="+this+" actual="+width+"x"+height);
    }
    int reviewWidth(){return Math.max(1,Math.min(750,500*width/height));}
    int reviewHeight(){return Math.max(1,reviewWidth()*height/width);}
    public String toString(){return width+"x"+height;}
}
