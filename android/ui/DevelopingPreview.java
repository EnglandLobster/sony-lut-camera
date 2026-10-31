package org.hongen.sonylutprobe;

import android.graphics.*;

/** Owns small preview copies and a UI-only blend buffer; never draws capture-owned bitmaps. */
final class DevelopingPreview {
    static final int WIDTH=300,HEIGHT=200;
    static final class Source {
        Bitmap positive,negative,composite;
        Canvas compositeCanvas;
        void release(){
            if(compositeCanvas!=null)compositeCanvas.setBitmap(null);compositeCanvas=null;
            recycle(positive);recycle(negative);recycle(composite);positive=null;negative=null;composite=null;
        }
    }
    private final DevelopTimeline timeline=new DevelopTimeline();
    private Source source;
    private int compositeAlpha=-1;
    // Small preview only: nearest sampling avoids the camera's slow software
    // bilinear path on every frame. The one-time downsample remains filtered.
    private final Paint imagePaint=new Paint();

    // Called on the existing capture worker only after its preview write finishes.
    static Bitmap thumbnail(Bitmap input){
        Bitmap copy=Bitmap.createBitmap(WIDTH,HEIGHT,Bitmap.Config.ARGB_8888);
        try{new Canvas(copy).drawBitmap(input,null,new Rect(0,0,WIDTH,HEIGHT),new Paint(Paint.FILTER_BITMAP_FLAG));return copy;}
        catch(RuntimeException e){copy.recycle();throw e;}
        catch(OutOfMemoryError e){copy.recycle();throw e;}
    }
    static Source createSource(Bitmap input){
        Source frame=new Source();
        try{
            frame.positive=thumbnail(input);
            frame.negative=Bitmap.createBitmap(WIDTH,HEIGHT,Bitmap.Config.ARGB_8888);
            Paint paint=new Paint();
            paint.setColorFilter(new ColorMatrixColorFilter(new float[]{
                -.85f,0,0,0,245, 0,-.85f,0,0,205, 0,0,-.75f,0,155, 0,0,0,1,0}));
            Canvas canvas=new Canvas(frame.negative);
            canvas.drawBitmap(frame.positive,0,0,paint);
            // Bake the broad developer pools once into the negative. They
            // dissolve with it; no shader evaluation during animation frames.
            paint.setColorFilter(null);paint.setAlpha(75);
            paint.setShader(new RadialGradient(WIDTH*.2f,HEIGHT*.8f,WIDTH*.65f,0xff080d11,0x00080d11,Shader.TileMode.CLAMP));
            canvas.drawRect(0,0,WIDTH,HEIGHT,paint);
            paint.setShader(new RadialGradient(WIDTH*.85f,HEIGHT*.15f,WIDTH*.6f,0xff080d11,0x00080d11,Shader.TileMode.CLAMP));
            canvas.drawRect(0,0,WIDTH,HEIGHT,paint);
            frame.composite=Bitmap.createBitmap(WIDTH,HEIGHT,Bitmap.Config.ARGB_8888);
            frame.compositeCanvas=new Canvas(frame.composite);
            return frame;
        }catch(RuntimeException e){frame.release();throw e;}
        catch(OutOfMemoryError e){frame.release();throw e;}
    }
    void begin(long shot,boolean enabled){end();timeline.begin(shot,enabled);}
    boolean source(long shot,Source incoming,long now){
        if(!timeline.source(shot,now)){incoming.release();return false;}
        source=incoming;compositeAlpha=-1;return true;
    }
    boolean graded(long shot,Bitmap incoming,long now){
        if(source==null||!timeline.graded(shot,now)){recycle(incoming);return false;}
        Bitmap previous=source.positive;source.positive=incoming;compositeAlpha=-1;recycle(previous);return true;
    }
    boolean moving(long now){return timeline.moving(now);}
    boolean hasSource(){return source!=null&&timeline.hasSource();}
    long bytes(){return source==null?0:(long)source.positive.getRowBytes()*source.positive.getHeight()+(long)source.negative.getRowBytes()*source.negative.getHeight()+(long)source.composite.getRowBytes()*source.composite.getHeight();}
    void draw(Canvas canvas,RectF bounds,long now){
        if(!hasSource())return;
        float reveal=timeline.reveal(now);
        int alpha=Math.round(255*reveal);
        Bitmap display=alpha==0?source.negative:source.positive;
        // Blend at thumbnail resolution, then send one opaque image to the
        // display surface. Screen-sized alpha blending is slow on this camera.
        if(alpha>0&&alpha<255){
            if(alpha!=compositeAlpha){
                imagePaint.setAlpha(255);source.compositeCanvas.drawBitmap(source.negative,0,0,imagePaint);
                imagePaint.setAlpha(alpha);source.compositeCanvas.drawBitmap(source.positive,0,0,imagePaint);
                imagePaint.setAlpha(255);compositeAlpha=alpha;
            }
            display=source.composite;
        }
        DrawFilter previous=canvas.getDrawFilter();canvas.setDrawFilter(null);
        try{canvas.drawBitmap(display,null,bounds,imagePaint);}
        finally{canvas.setDrawFilter(previous);}
    }
    void end(){imagePaint.setAlpha(255);timeline.end();if(source!=null){source.release();source=null;}}
    static void recycle(Bitmap bitmap){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
}
