package org.hongen.sonylutprobe;

/** Fit an image in physical screen coordinates, including non-square OSD pixels. */
final class ReviewGeometry {
    static float[] fit(int imageWidth,int imageHeight,int viewWidth,int viewHeight,float pixelAspect){
        if(imageWidth<=0||imageHeight<=0||viewWidth<=0||viewHeight<=0||
                !(pixelAspect>0)||Float.isInfinite(pixelAspect))throw new IllegalArgumentException("invalid review geometry");
        float sy=Math.min(viewWidth*pixelAspect/imageWidth,(float)viewHeight/imageHeight);
        float sx=sy/pixelAspect;
        return new float[]{sx,sy,(viewWidth-imageWidth*sx)/2f,(viewHeight-imageHeight*sy)/2f};
    }
}
