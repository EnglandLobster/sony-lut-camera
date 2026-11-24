package io.github.englandlobster.lutcamera;

import java.io.IOException;

/** A full-frame body in APS-C crop develops a smaller advertised size of the same shape. */
public final class CaptureGeometryTest {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static boolean refused(CaptureGeometry requested,int w,int h,String sizes){
        try{requested.developed(w,h,sizes);return false;}catch(IOException expected){return expected.getMessage().contains("capture geometry changed");}
    }
    public static void main(String[] args)throws Exception{
        // NAS 298007, ILCE-7M2 4.01: picture-size 6000x4000, developed 3936x2624 in APS-C.
        String a7m2="6000x4000,6000x3376,3936x2624,3936x2216,3008x2000,3008x1688";
        CaptureGeometry full=new CaptureGeometry(6000,4000);
        check(full.developed(6000,4000,a7m2)==full,"full frame keeps the requested geometry");
        CaptureGeometry crop=full.developed(3936,2624,a7m2);
        check(crop.width==3936&&crop.height==2624&&!crop.same(full),"APS-C crop accepted: "+crop);
        check(crop.reviewWidth()==full.reviewWidth()&&crop.reviewHeight()==full.reviewHeight(),"review buffers fit both");
        // ILCE-7R 3.20 sizes (NAS a7r1): its APS-C crop is 4800x3200.
        String a7r="7360x4912,7360x4144,4800x3200,4800x2704,3680x2456,3680x2072";
        check(new CaptureGeometry(7360,4912).developed(4800,3200,a7r).width==4800,"A7R crop accepted");
        check(refused(full,3936,2216,a7m2),"a different shape is still a mismatch");
        check(refused(full,4000,2666,a7m2),"an unadvertised size is still a mismatch");
        check(refused(full,6016,4016,a7m2),"a larger image is still a mismatch");
        check(refused(full,3936,2624,null),"no advertised sizes, no crop");
        check(refused(full,3936,2624,"junk,3936x2624x1"),"malformed sizes are ignored");
        System.out.println("PASS capture geometry accepts an advertised APS-C crop only");
    }
}
