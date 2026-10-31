package org.hongen.sonylutprobe;

public final class ReviewGeometryTest {
    private static void near(float a,float b){if(Math.abs(a-b)>.001f)throw new AssertionError(a+" != "+b);}
    private static void fits(int iw,int ih,int vw,int vh,float pixelAspect){
        float[] f=ReviewGeometry.fit(iw,ih,vw,vh,pixelAspect);
        near((iw*f[0]*pixelAspect)/(ih*f[1]),(float)iw/ih);
        if(f[2]<-.001||f[3]<-.001||iw*f[0]>vw+.001||ih*f[1]>vh+.001)throw new AssertionError("crop");
    }
    public static void main(String[] args){
        float panel=16f/9*480/640;
        float[] p=ReviewGeometry.fit(750,500,640,480,panel);
        near(p[0],.72f);near(p[1],.96f);near(p[2],50);near(p[3],0);
        float[] finder=ReviewGeometry.fit(750,500,640,480,1);
        near(finder[0],finder[1]);near(finder[2],0);near(finder[3],26.666666f);
        for(int[] size:new int[][]{{750,500},{500,750},{800,800},{854,480}}){
            fits(size[0],size[1],640,480,panel);
            fits(size[0],size[1],640,480,1);
            fits(size[0],size[1],640,393,panel);
        }
        System.out.println("PASS physical aspect and no crop: wide panel, square-pixel finder, portrait and reduced viewport");
    }
}
