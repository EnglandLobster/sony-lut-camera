package org.hongen.sonylutprobe;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;

/** Runs against Android graphics, not host stubs; no camera or user images. */
public final class DevelopingPreviewTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void released(DevelopingPreview.Source source){
        check(source.compositeCanvas==null&&source.positive==null&&source.negative==null&&source.composite==null,
                "released source must retain no drawing resources");
    }
    public static void main(String[] args){
        Bitmap input=Bitmap.createBitmap(12,8,Bitmap.Config.ARGB_8888);
        Bitmap target=Bitmap.createBitmap(300,200,Bitmap.Config.ARGB_8888);
        DevelopingPreview render=new DevelopingPreview();
        try{
            for(int y=0;y<8;y++)for(int x=0;x<12;x++)
                input.setPixel(x,y,0xff000000|((x*255/11)<<16)|((y*255/7)<<8)|((x+y)*255/18));
            Canvas canvas=new Canvas(target);RectF bounds=new RectF(0,0,300,200);
            for(int shot=1;shot<=32;shot++){
                render.begin(shot,true);
                DevelopingPreview.Source source=DevelopingPreview.createSource(input);
                Bitmap positive=source.positive,negative=source.negative,composite=source.composite;
                check(render.source(shot,source,1000),"current source accepted");
                check(render.bytes()==720000,"three bounded owned thumbnails");
                render.draw(canvas,bounds,1000);
                Bitmap grade=DevelopingPreview.thumbnail(input);
                check(render.graded(shot,grade,1000),"grade accepted");
                check(positive.isRecycled()&&!negative.isRecycled()&&!composite.isRecycled(),
                        "replacement releases only the previous positive");
                render.draw(canvas,bounds,1500);render.draw(canvas,bounds,2000);
                Bitmap duplicate=DevelopingPreview.thumbnail(input);
                check(!render.graded(shot,duplicate,2100)&&duplicate.isRecycled(),"duplicate grade released");
                DevelopingPreview.Source stale=DevelopingPreview.createSource(input);
                Bitmap stalePositive=stale.positive;
                check(!render.source(shot-1,stale,2100)&&stalePositive.isRecycled(),"stale source released");
                released(stale);
                render.end();render.end();source.release();
                check(grade.isRecycled()&&negative.isRecycled()&&composite.isRecycled(),"end releases owned buffers");
                released(source);
                check(!render.hasSource()&&!render.moving(2100)&&render.bytes()==0,"end invalidates drawing state");
                target.eraseColor(0xff123456);render.draw(canvas,bounds,2100);
                check(target.getPixel(150,100)==0xff123456,"draw after end does not access released buffers");
                DevelopingPreview.Source late=DevelopingPreview.createSource(input);
                check(!render.source(shot,late,2200),"ended shot rejects late source");released(late);
                Bitmap lateGrade=DevelopingPreview.thumbnail(input);
                check(!render.graded(shot,lateGrade,2200)&&lateGrade.isRecycled(),"ended shot rejects late grade");
                check(!input.isRecycled()&&!target.isRecycled(),"capture input and drawing target remain owned by caller");
            }
            DevelopingPreview.Source partial=new DevelopingPreview.Source();
            partial.positive=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);
            partial.composite=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);
            partial.compositeCanvas=new Canvas(partial.composite);
            Bitmap partialPositive=partial.positive,partialComposite=partial.composite;
            partial.release();partial.release();released(partial);
            check(partialPositive.isRecycled()&&partialComposite.isRecycled(),"partial construction cleanup");
            DevelopingPreview.Source interrupted=DevelopingPreview.createSource(input);
            render.begin(100,true);check(render.source(100,interrupted,1000),"interrupted source accepted");
            render.begin(101,true);released(interrupted);
            check(!render.hasSource()&&!render.moving(1000),"new shot clears old source before callbacks");
            render.end();
            new DevelopingPreview.Source().release();
            System.out.println("PASS Android Canvas cleanup: repeat end/release, blend, stale/late callbacks, partial source, next shot; camera_opened=false");
        }finally{render.end();DevelopingPreview.recycle(target);DevelopingPreview.recycle(input);}
    }
}
