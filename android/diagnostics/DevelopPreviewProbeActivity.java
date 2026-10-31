package org.hongen.sonylutprobe;

import android.app.Activity;
import android.graphics.*;
import android.os.*;
import android.view.*;
import java.io.*;

/** Explicit renderer-only diagnostic. Fixture image; no camera API or shutter. */
public final class DevelopPreviewProbeActivity extends Activity {
    private final Handler ui=new Handler();
    private PhotoHud hud;
    private Bitmap fixture;
    private boolean stopped;
    private long displayStart;
    private final Runnable tick=new Runnable(){public void run(){
        if(stopped)return;hud.progress=((SystemClock.uptimeMillis()-displayStart)/100)/10f+" 秒";hud.update();ui.postDelayed(this,250);
    }};
    @Override public void onCreate(Bundle state){super.onCreate(state);
        hud=new PhotoHud(this);hud.pixelAspect=4f/3;hud.mode="processing";hud.name="显影绘制测试";
        hud.detail="正在显影";hud.processingStage=CaptureProgress.Stage.DEVELOP;hud.progress="测试";
        setContentView(hud);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui.post(new Runnable(){public void run(){test();}});
    }
    @Override protected void onResume(){super.onResume();SonyAppPolicy.register(this);}
    private static void check(boolean value,String detail){if(!value)throw new AssertionError(detail);}
    private void test(){
        DevelopingPreview render=new DevelopingPreview();Bitmap target=null;
        try{
            InputStream in=getAssets().open("PREVIEW.JPG");try{fixture=BitmapFactory.decodeStream(in);}finally{in.close();}
            long begin=SystemClock.elapsedRealtime();
            DevelopingPreview.Source source=DevelopingPreview.createSource(fixture);
            long copyMs=SystemClock.elapsedRealtime()-begin;
            Bitmap positive=source.positive,negative=source.negative,composite=source.composite;
            check(positive!=fixture,"source copy must own its bitmap");
            render.begin(1,true);check(render.source(1,source,1000),"source accepted");
            check(render.bytes()==720000,"bounded owned bitmap bytes");
            check(!render.moving(9999),"negative stays still before grade");
            Bitmap graded=DevelopingPreview.thumbnail(fixture);
            check(render.graded(1,graded,1000),"graded replacement");
            check(positive.isRecycled()&&!negative.isRecycled(),"only replaced bitmap released");
            target=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(target);
            RectF bounds=new RectF(201.25f,77,651.25f,377);
            File dir=new File(getCacheDir(),"develop");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("diagnostic directory");
            for(int elapsed:new int[]{0,250,500,1000}){
                canvas.drawColor(0xff101518);canvas.save();canvas.scale(.75f,1);
                render.draw(canvas,bounds,1000+elapsed);canvas.restore();
                FileOutputStream out=new FileOutputStream(new File(dir,"FRAME"+elapsed+".PNG"));
                try{target.compress(Bitmap.CompressFormat.PNG,100,out);}finally{out.close();}
            }
            long cpu=Debug.threadCpuTimeNanos(),wall=System.nanoTime(),max=0;
            for(int i=0;i<32;i++){
                long frameAt=System.nanoTime();canvas.drawColor(0xff101518);canvas.save();canvas.scale(.75f,1);
                render.draw(canvas,bounds,1000+(i%8)*125);canvas.restore();max=Math.max(max,System.nanoTime()-frameAt);
            }
            long cpuNs=Debug.threadCpuTimeNanos()-cpu,wallNs=System.nanoTime()-wall;
            Bitmap duplicate=DevelopingPreview.thumbnail(fixture);
            check(!render.graded(1,duplicate,1500)&&duplicate.isRecycled(),"duplicate cannot restart reveal");
            DevelopingPreview.Source stale=DevelopingPreview.createSource(fixture);Bitmap staleBitmap=stale.positive;
            check(!render.source(0,stale,3000)&&staleBitmap.isRecycled(),"stale source released");
            render.end();check(graded.isRecycled()&&negative.isRecycled()&&composite.isRecycled()&&!fixture.isRecycled(),"close releases only owned buffers");
            Bitmap late=DevelopingPreview.thumbnail(fixture);check(!render.graded(1,late,3000)&&late.isRecycled(),"late graded rejected");
            android.util.Log.i("DevelopProbe","PASS camera_opened=false ownership=true copy_ms="+copyMs+" frames=32 cpu_ms="+(cpuNs/1000000.0)
                    +" wall_ms="+(wallNs/1000000.0)+" max_frame_ms="+(max/1000000.0)+" bytes=720000 files="+dir);
            if(!stopped){
                hud.beginDeveloping(1,true);hud.sourceDeveloping(1,DevelopingPreview.createSource(fixture));hud.update();
                displayStart=SystemClock.uptimeMillis();tick.run();
                ui.postDelayed(new Runnable(){public void run(){if(!stopped){
                    hud.detail="正在保存成片";hud.processingStage=CaptureProgress.Stage.SAVE;
                    hud.gradedDeveloping(1,DevelopingPreview.thumbnail(fixture));
                }}},2000);
                ui.postDelayed(new Runnable(){public void run(){if(!stopped){
                    ui.removeCallbacks(tick);hud.endDeveloping();hud.mode="loading";hud.detail="显影绘制测试完成";hud.update();
                }}},4000);
            }
        }catch(Throwable e){android.util.Log.e("DevelopProbe","FAIL",e);if(!stopped){hud.mode="error";hud.detail="显影绘制测试失败";hud.update();}}
        finally{render.end();DevelopingPreview.recycle(target);}
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(event.getKeyCode()==KeyEvent.KEYCODE_MENU){if(event.getAction()==KeyEvent.ACTION_UP)finish();return true;}return super.dispatchKeyEvent(event);}
    @Override protected void onPause(){stopped=true;ui.removeCallbacksAndMessages(null);hud.endDeveloping();finish();super.onPause();}
    @Override protected void onDestroy(){hud.endDeveloping();DevelopingPreview.recycle(fixture);super.onDestroy();}
}
