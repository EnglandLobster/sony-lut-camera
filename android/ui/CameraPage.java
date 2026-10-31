package org.hongen.sonylutprobe;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import java.util.*;

/** Physical-key UI with separate LCD/EVF geometry and a live LUT reference pane. */
final class CameraPage extends SonyReviewView {
    static final int BG=0xff0d1114,PANEL=0xff181e22,FOCUS=0xff282a28,LINE=0xff30383d,TEXT=0xfff2f3ef,MUTED=0xff9ba6ab,ACCENT=0xffeac28c,ERROR=0xfff0a092;
    static final class Row {
        final String title,detail,badge;final boolean good;
        Row(String title,String detail,String badge){this(title,detail,badge,true);}
        Row(String title,String detail,String badge,boolean good){this.title=title;this.detail=detail;this.badge=badge;this.good=good;}
    }
    interface Tap {void select(int row);}
    String section="LUT 库",title="选择你的色彩",subtitle="",note="",footer="",empty="",emptyDetail="右键导入 · 中央键确认";
    List<Row> rows=new ArrayList<Row>();int selected;boolean busy,previewMode,previewLoading;Bitmap preview;String previewMessage="效果参考";Tap tap;
    private final Paint paint=new Paint(3);private float pixelAspect=1,logicalWidth=640,scale=1;
    CameraPage(Context context){super(context);setBackgroundColor(BG);setFocusable(true);paint.setTypeface(UiFont.get(context));}
    @Override protected void pixelAspectChanged(float value){pixelAspect=value;invalidate();}
    private void text(Canvas c,String text,float x,float y,float size,int color){paint.setTextAlign(Paint.Align.LEFT);paint.setColor(color);paint.setTextSize(size);c.drawText(text,x,y,paint);}
    private void right(Canvas c,String text,float x,float y,float size,int color){paint.setTextAlign(Paint.Align.RIGHT);paint.setColor(color);paint.setTextSize(size);c.drawText(text,x,y,paint);}
    private String fit(String s,float width,float size){paint.setTextSize(size);if(paint.measureText(s)<=width)return s;int n=paint.breakText(s,true,Math.max(0,width-paint.measureText("…")),null);return s.substring(0,n)+"…";}
    private void round(Canvas c,float x,float y,float r,float b,int color){paint.setStyle(Paint.Style.FILL);paint.setColor(color);c.drawRoundRect(new RectF(x,y,r,b),10,10,paint);}
    private void outline(Canvas c,float x,float y,float r,float b,boolean focus){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);paint.setColor(focus?ACCENT:LINE);c.drawRoundRect(new RectF(x+.5f,y+.5f,r-.5f,b-.5f),10,10,paint);paint.setStyle(Paint.Style.FILL);}
    private void badge(Canvas c,String value,float x,float y,boolean focus){if(value.length()==0)return;right(c,fit(value,92,15),x,y,15,focus?ACCENT:MUTED);}
    @Override protected void onDraw(Canvas c){super.onDraw(c);scale=getHeight()/480f;if(scale<=0)return;logicalWidth=getWidth()*pixelAspect/scale;
        c.save();c.scale(scale/pixelAspect,scale);float w=logicalWidth;
        text(c,"LUT CAMERA",28,34,15,ACCENT);right(c,section,w-28,34,15,MUTED);
        paint.setColor(LINE);c.drawRect(28,52,w-28,53,paint);
        float listRight=w>=740?400:330;
        float headingWidth=previewMode?listRight-28:w-56;
        text(c,fit(title,headingWidth,29),28,94,29,TEXT);text(c,fit(subtitle,headingWidth,16),28,123,16,MUTED);
        int first=UiList.pageStart(selected,rows.size(),4),last=Math.min(rows.size(),first+4);
        if(previewMode){
            float left=listRight,rx=left+46;
            for(int i=first;i<last;i++)row(c,rows.get(i),i,i==selected,28,145+(i-first)*62,left,56,left-104);
            // The photograph owns the full right column, including the space
            // beside the heading. Fit both orientations without cropping.
            float top=68,bottom=398,padding=8;
            round(c,rx,top,w-28,bottom,PANEL);outline(c,rx,top,w-28,bottom,false);
            float iw=w-28-rx-2*padding,ih=bottom-top-2*padding,ix=rx+padding,iy=top+padding;
            if(preview!=null){
                float factor=Math.min(iw/preview.getWidth(),ih/preview.getHeight()),dw=preview.getWidth()*factor,dh=preview.getHeight()*factor;
                paint.setColor(0xff0a0d0f);c.drawRect(ix,iy,ix+iw,iy+ih,paint);
                c.drawBitmap(preview,null,new RectF(ix+(iw-dw)/2,iy+(ih-dh)/2,ix+(iw+dw)/2,iy+(ih+dh)/2),paint);
            }else{text(c,fit(previewLoading?"正在生成效果参考":previewMessage,iw-20,17),ix+12,iy+ih/2,17,MUTED);}
        }else{
            for(int i=first;i<last;i++)row(c,rows.get(i),i,i==selected,28,148+(i-first)*62,w-28,56,w-190);
            if(rows.isEmpty()){round(c,28,157,w-28,335,PANEL);outline(c,28,157,w-28,335,false);text(c,fit(empty,w-104,23),50,222,23,TEXT);text(c,fit(busy?"请稍候，正在处理…":emptyDetail,w-104,17),50,265,17,MUTED);}
        }
        if(note.length()>0)text(c,fit(note,w-118,15),28,422,15,MUTED);
        if(rows.size()>4)right(c,(selected+1)+" / "+rows.size(),w-28,422,15,ACCENT);
        paint.setColor(LINE);c.drawRect(28,437,w-28,438,paint);
        text(c,fit(footer,w-56,17),28,465,17,TEXT);c.restore();
    }
    private void row(Canvas c,Row row,int index,boolean focus,float x,float y,float right,float height,float textWidth){
        round(c,x,y,right,y+height,focus?FOCUS:PANEL);outline(c,x,y,right,y+height,focus);
        if(focus){paint.setColor(ACCENT);c.drawRoundRect(new RectF(x,y+12,x+4,y+height-12),2,2,paint);}
        float available=Math.min(textWidth,right-x-150);
        text(c,fit(row.title,available,20),x+17,y+25,20,TEXT);
        text(c,fit(row.detail,available,13),x+17,y+45,13,focus?0xffc2c8c7:MUTED);
        badge(c,row.badge,right-16,y+33,focus);
    }
    @Override public boolean onTouchEvent(MotionEvent event){if(event.getAction()!=MotionEvent.ACTION_UP)return true;
        float y=event.getY()/scale;int index=UiList.pageStart(selected,rows.size(),4)+(int)((y-(previewMode?145:148))/62);
        if(y>=(previewMode?145:148)&&y<398&&index>=0&&index<rows.size()&&tap!=null&&!busy)tap.select(index);return true;}
}
