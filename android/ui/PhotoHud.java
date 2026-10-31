package org.hongen.sonylutprobe;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.os.SystemClock;

/** Overlay only: never takes space away from the image or viewfinder. */
final class PhotoHud extends View {
    private static final String[] PROCESSING_LABELS={"拍摄","显影","调色","保存"};
    String mode="loading",name="LUT Camera",detail="正在准备拍摄",footer="",progress="",tag="";
    CaptureProgress.Stage processingStage=CaptureProgress.Stage.CAPTURE;
    boolean liveInfoVisible=true;
    String liveExposure="",liveCompensation="+0.0 EV",qualityLabel="";
    CaptureReadout readout=new CaptureReadout();
    private String liveHint="",settingsNotice="";
    private final Runnable clearSettingsNotice=new Runnable(){public void run(){settingsNotice="";invalidate();}};
    void showSettingsNotice(String text){settingsNotice=text;removeCallbacks(clearSettingsNotice);postDelayed(clearSettingsNotice,10000);invalidate();}
    private boolean attached;
    private final DevelopingPreview developingPreview=new DevelopingPreview();
    private boolean animationQueued,imageDirty,wasMoving,fullFramePending=true;
    private String lastMode="";
    private long lastDevelopFrameAt,developFrames,developCpuNs,developMaxNs,developShot;
    void beginDeveloping(long shot,boolean enabled){
        endDeveloping();developingPreview.begin(shot,enabled);developShot=shot;
        developFrames=developCpuNs=developMaxNs=0;
    }
    void sourceDeveloping(long shot,DevelopingPreview.Source source){
        if(developingPreview.source(shot,source,SystemClock.uptimeMillis())){imageDirty=true;update();}
    }
    void gradedDeveloping(long shot,Bitmap bitmap){if(developingPreview.graded(shot,bitmap,SystemClock.uptimeMillis())){imageDirty=true;update();}}
    void endDeveloping(){
        if(developFrames>0)android.util.Log.i("LutUI","develop_render shot="+developShot+" frames="+developFrames
                +" cpu_ms="+(developCpuNs/1000000.0)+" max_wall_ms="+(developMaxNs/1000000.0)+" bytes="+developingPreview.bytes());
        developFrames=0;developingPreview.end();stopAnimation();fullFramePending=true;
    }
    private void stopAnimation(){removeCallbacks(animateDeveloping);animationQueued=false;lastDevelopFrameAt=0;wasMoving=false;}
    private boolean processingVisible(){return attached&&getVisibility()==VISIBLE&&getWindowVisibility()==VISIBLE&&mode.equals("processing");}
    private void requestDevelopFrame(){
        if(animationQueued||!processingVisible())return;
        animationQueued=true;postDelayed(animateDeveloping,Math.max(0,DevelopTimeline.FRAME_MS-(SystemClock.uptimeMillis()-lastDevelopFrameAt)));
    }
    private final RectF frame=new RectF();
    private final Runnable clearLiveHint=new Runnable(){public void run(){hideLiveHint();}};
    private final Runnable animateDeveloping=new Runnable(){public void run(){
        animationQueued=false;if(!processingVisible())return;
        lastDevelopFrameAt=SystemClock.uptimeMillis();
        boolean moving=developingPreview.moving(lastDevelopFrameAt);
        if(fullFramePending||getHeight()==0)invalidate();
        else{
            // Keep the static negative intact while the capture worker runs
            // LUT. Clock/stage ticks only damage the controls below the photo.
            int top=(int)((imageDirty||moving||wasMoving?76:390)*getHeight()/480f);
            invalidate(0,top,getWidth(),getHeight());
        }
        fullFramePending=false;imageDirty=false;wasMoving=moving;
        if(moving)requestDevelopFrame();
    }};
    boolean original;float pixelAspect=1;private final Paint p=new Paint(3);
    PhotoHud(Context context){super(context);p.setTypeface(UiFont.get(context));}
    void update(){
        if(!mode.equals(lastMode)){fullFramePending=true;lastMode=mode;}
        if(processingVisible())requestDevelopFrame();
        else{stopAnimation();invalidate();}
    }
    void showLiveHint(String value){showPersistentLiveHint(value);postDelayed(clearLiveHint,3000);}
    void showPersistentLiveHint(String value){removeCallbacks(clearLiveHint);liveHint=value;invalidate();}
    void hideLiveHint(){removeCallbacks(clearLiveHint);liveHint="";invalidate();}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();attached=true;fullFramePending=true;update();}
    @Override protected void onVisibilityChanged(View changed,int visibility){super.onVisibilityChanged(changed,visibility);if(changed==this){fullFramePending=true;update();}}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);fullFramePending=true;update();}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);fullFramePending=true;update();}
    @Override protected void onDetachedFromWindow(){attached=false;endDeveloping();removeCallbacks(clearLiveHint);super.onDetachedFromWindow();}
    private void chip(Canvas c,String value,float x,float y,float maxWidth,int size,boolean alignRight){
        String label=fit(value,maxWidth-20,size);float width=p.measureText(label)+20;
        float left=alignRight?x-width:x;
        p.setColor(0x900d1114);c.drawRoundRect(new RectF(left,y,left+width,y+32),5,5,p);
        text(c,label,left+10,y+22,size,CameraPage.TEXT);
    }
    private void battery(Canvas c,float x,float y){
        int color=readout.battery>=0&&readout.battery<=15?CameraPage.ERROR:CameraPage.TEXT;
        p.setStyle(Paint.Style.FILL);p.setColor(0xc0000000);c.drawRect(x-2,y-2,x+29,y+15,p);
        p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f);
        c.drawRect(x,y,x+24,y+13,p);p.setStyle(Paint.Style.FILL);c.drawRect(x+24,y+4,x+27,y+9,p);
        if(readout.battery>0)c.drawRect(x+3,y+3,x+3+18*readout.battery/100f,y+10,p);
        String label=readout.battery<0?"--%":readout.battery+"%";
        text(c,label,x+35,y+13,17,color);
        if(readout.charging)text(c,"+",x-12,y+13,16,CameraPage.ACCENT);
    }
    private void label(Canvas c,String value,float x,float y,int size,int color,Paint.Align align){
        p.setTextAlign(align);p.setTextSize(size);
        if(mode.equals("live")){
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.5f);p.setStrokeJoin(Paint.Join.ROUND);p.setColor(0xe0000000);
            c.drawText(value,x,y,p);p.setStyle(Paint.Style.FILL);
        }
        p.setColor(color);c.drawText(value,x,y,p);
    }
    private void text(Canvas c,String value,float x,float y,int size,int color){label(c,value,x,y,size,color,Paint.Align.LEFT);}
    private void right(Canvas c,String value,float x,float y,int size,int color){label(c,value,x,y,size,color,Paint.Align.RIGHT);}
    private void center(Canvas c,String value,float x,float y,int size,int color){label(c,value,x,y,size,color,Paint.Align.CENTER);}
    private String fit(String s,float width,int size){p.setTextSize(size);if(p.measureText(s)<=width)return s;return s.substring(0,p.breakText(s,true,Math.max(0,width-p.measureText("…")),null))+"…";}
    private void developing(Canvas c,float w){
        float center=w/2,fw=Math.min(450,w-96),fh=fw*2/3,left=center-fw/2,top=77;
        frame.set(left,top,left+fw,top+fh);
        p.setStyle(Paint.Style.FILL);p.setColor(0xff101518);c.drawRect(frame,p);
        if(developingPreview.hasSource()&&!c.quickReject(frame,Canvas.EdgeType.BW)){
            long wall=System.nanoTime(),cpu=android.os.Debug.threadCpuTimeNanos();
            try{developingPreview.draw(c,frame,SystemClock.uptimeMillis());}
            catch(RuntimeException e){android.util.Log.w("LutUI","develop_draw_disabled",e);developingPreview.end();}
            catch(OutOfMemoryError e){android.util.Log.w("LutUI","develop_draw_disabled",e);developingPreview.end();}
            developCpuNs+=android.os.Debug.threadCpuTimeNanos()-cpu;
            developMaxNs=Math.max(developMaxNs,System.nanoTime()-wall);developFrames++;
        }
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(0xff3c3b36);c.drawRect(frame,p);p.setStyle(Paint.Style.FILL);
        center(c,fit(detail,w-96,18),center,409,18,CameraPage.TEXT);
        int active=processingStage.ordinal();float track=Math.min(330,w-128),start=center-track/2,step=track/3,y=434;
        p.setStrokeWidth(1);p.setColor(CameraPage.LINE);c.drawLine(start,y,start+track,y,p);
        if(active>0){p.setColor(CameraPage.ACCENT);c.drawLine(start,y,start+step*active,y,p);}
        for(int i=0;i<4;i++){
            float x=start+step*i;boolean passed=i<=active;
            p.setColor(passed?CameraPage.ACCENT:CameraPage.LINE);c.drawCircle(x,y,i==active?4:2.5f,p);
            center(c,PROCESSING_LABELS[i],x,460,13,passed?CameraPage.TEXT:CameraPage.MUTED);
        }
        right(c,progress,w-28,409,17,CameraPage.ACCENT);
    }
    @Override protected void onDraw(Canvas c){float scale=getHeight()/480f;if(scale<=0)return;float w=getWidth()*pixelAspect/scale;
        c.save();c.scale(scale/pixelAspect,scale);
        boolean full=mode.equals("processing")||mode.equals("loading")||mode.equals("error");
        if(full){
            p.setColor(CameraPage.BG);c.drawRect(0,0,w,480,p);
            text(c,"LUT CAMERA",28,36,15,CameraPage.ACCENT);right(c,fit(name,w/2-50,16),w-28,36,16,CameraPage.MUTED);
            p.setColor(CameraPage.LINE);c.drawRect(28,54,w-28,55,p);
            if(mode.equals("processing"))developing(c,w);
            else{
                text(c,fit(detail,w-72,29),36,194,29,mode.equals("error")?CameraPage.ERROR:CameraPage.TEXT);
                if(mode.equals("error")){
                    p.setTextSize(19);int line=0;
                    for(String paragraph:progress.split("\n",-1)){
                        if(paragraph.length()==0){line++;continue;}
                        while(paragraph.length()>0&&line<6){
                            int count=p.breakText(paragraph,true,w-72,null);if(count<1)break;
                            text(c,paragraph.substring(0,count),36,244+line*28,19,CameraPage.TEXT);
                            paragraph=paragraph.substring(count);line++;
                        }
                    }
                }else text(c,fit(progress,w-72,19),36,244,19,CameraPage.ACCENT);
                if(!mode.equals("error"))text(c,fit(name,w-72,17),36,286,17,CameraPage.MUTED);
                text(c,fit(footer,w-72,17),36,443,17,CameraPage.MUTED);
            }
        }else if(mode.equals("live")){ 
            // Camera-style edge readouts, with outlined text instead of
            // full-width bars. The native preview geometry is unchanged.
            if(liveInfoVisible){
                text(c,"P",14,34,24,CameraPage.ACCENT);
                text(c,fit(name,w-180,18),48,31,18,CameraPage.TEXT);
                text(c,fit(qualityLabel,w-180,16),48,55,16,CameraPage.MUTED);
                battery(c,w-104,17);
                String[] values={readout.shutterText(),readout.apertureText(),readout.isoText(),liveCompensation};
                float cell=w/4;
                for(int i=0;i<values.length;i++)center(c,fit(values[i],cell-16,21),cell*(i+.5f),463,21,CameraPage.TEXT);
            }
            if(settingsNotice.length()>0){
                p.setTextSize(17);String remaining=settingsNotice;int line=0;
                while(remaining.length()>0&&line<4){
                    int count=p.breakText(remaining,true,w-72,null);if(count<1)break;
                    chip(c,remaining.substring(0,count),24,78+line*30,w-48,17,false);
                    remaining=remaining.substring(count);line++;
                }
                chip(c,"仅本次拍摄生效，退出后恢复",24,78+line*30,w-48,16,false);
            }
            if(liveHint.length()>0)chip(c,liveHint,12,390,w-24,16,false);
        }else{
            boolean hasDetail=detail.length()>0,hasTag=tag.length()>0;
            p.setColor(0xd80d1114);c.drawRect(0,0,w,64,p);
            if(hasDetail||footer.length()>0)c.drawRect(0,hasDetail?392:436,w,480,p);
            text(c,"LUT",24,25,13,CameraPage.ACCENT);text(c,fit(name,w-(hasTag?230:48),21),24,51,21,CameraPage.TEXT);
            if(hasTag)right(c,fit(tag,180,18),w-24,41,18,CameraPage.ACCENT);
            if(hasDetail)text(c,fit(detail,w-48,18),24,424,18,CameraPage.TEXT);
            text(c,fit(footer,w-48,15),24,hasDetail?456:463,15,CameraPage.MUTED);
            if(mode.equals("review")){
                float x=24,y=78,bw=184,bh=35;
                p.setColor(0xe0181e22);c.drawRoundRect(new RectF(x,y,x+bw,y+bh),6,6,p);
                p.setColor(original?0xff4a5155:0x00000000);c.drawRoundRect(new RectF(x,y,x+bw/2,y+bh),6,6,p);
                p.setColor(original?0x00000000:CameraPage.ACCENT);c.drawRoundRect(new RectF(x+bw/2,y,x+bw,y+bh),6,6,p);
                center(c,"原片",x+bw/4,y+24,16,original?CameraPage.TEXT:CameraPage.MUTED);
                center(c,"LUT 成片",x+bw*3/4,y+24,16,original?CameraPage.MUTED:CameraPage.BG);
            }
        }
        c.restore();
    }
}
