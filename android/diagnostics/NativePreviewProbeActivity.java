package org.hongen.sonylutprobe;

import android.app.Activity;
import android.hardware.Camera;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import org.json.*;

/** Isolated live-only diagnostic: no DirectShutter, CameraSequence or file save.
 * The production PhotoActivity remains unchanged until capture isolation passes. */
public final class NativePreviewProbeActivity extends Activity implements SurfaceHolder.Callback {
    private final Handler ui=new Handler();
    private Camera camera;
    private Object cameraEx;
    private NativePreviewSession session;
    private CameraNativePreviewDriver driver;
    private SurfaceHolder holder;
    private TextView status;
    private boolean active,opening,closed;
    private JSONArray catalog;
    private int selected;
    private final Runnable expire=new Runnable(){public void run(){restore();}};
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout page=new FrameLayout(this);SurfaceView live=new SurfaceView(this);
        page.addView(live,new FrameLayout.LayoutParams(-1,-1));holder=live.getHolder();holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);holder.addCallback(this);
        status=new TextView(this);status.setTypeface(UiFont.get(this));status.setTextSize(17);status.setTextColor(0xffffffff);
        status.setBackgroundColor(0xa0000000);status.setPadding(12,5,12,5);
        page.addView(status,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));setContentView(page);
        try{
            InputStream in=getAssets().open("native-preview/CATALOG.json");ByteArrayOutputStream data=new ByteArrayOutputStream();
            try{byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)data.write(b,0,n);}finally{in.close();}
            catalog=new JSONArray(new String(data.toByteArray(),"UTF-8"));
            if(catalog.length()==0)throw new IOException("missing generated preview fixtures");
            status.setText("正在打开原生取景诊断 · 本页不拍照");
        }catch(Exception e){error("catalog",e);}
    }
    private void log(String text){android.util.Log.i("NativePreview",text);}
    private void error(String where,Exception e){
        android.util.Log.e("NativePreview",where,e);status.setText("诊断停止："+where+"\n"+e.toString()+"\n下键重试恢复 · MENU 退出");
    }
    private String label()throws Exception{return catalog.getJSONObject(selected).getString("label");}
    private void ready(String state){try{status.setText(state+" · "+label()+"\n左右选候选 · 中央应用 · 下键原色 · MENU 退出（本页不拍照）");}catch(Exception e){error("label",e);}}
    private void open(){
        if(!active||opening||closed||catalog==null||holder==null||!holder.getSurface().isValid())return;
        opening=true;
        try{
            Class<?> type=Class.forName("com.sony.scalar.hardware.CameraEx");
            Class<?> optionsType=Class.forName("com.sony.scalar.hardware.CameraEx$OpenOptions");Object options=optionsType.newInstance();
            optionsType.getMethod("setPreview",boolean.class).invoke(options,false);
            cameraEx=type.getMethod("open",int.class,optionsType).invoke(null,0,options);
            camera=(Camera)type.getMethod("getNormalCamera").invoke(cameraEx);
            driver=new CameraNativePreviewDriver(cameraEx,camera);session=new NativePreviewSession(driver);session.open();
            camera.setPreviewDisplay(holder);camera.startPreview();
            log("ready pid="+android.os.Process.myPid()+" shutter_enabled=false baseline_parameters=true");ready("原色");
        }catch(Exception e){error("open",e);}
    }
    private void apply(){
        if(session==null||!active||closed)return;
        ui.removeCallbacks(expire);long start=SystemClock.elapsedRealtime();
        try{
            JSONObject item=catalog.getJSONObject(selected);
            InputStream in=getAssets().open("native-preview/"+item.getString("asset"));NativePreviewProfile profile;
            try{profile=NativePreviewProfile.read(in,item.getString("sha256"));}finally{in.close();}
            session.apply(profile);log("applied candidate="+selected+" order_hypothesis="+profile.order+" cube="+profile.cubeSha256
                +" ms="+(SystemClock.elapsedRealtime()-start)+" parameter_readback=true hardware_pixels_verified=false");
            ready("近似预览 · 60秒后恢复");ui.postDelayed(expire,60000);
        }catch(Exception e){error("apply",e);}
    }
    private void restore(){
        ui.removeCallbacks(expire);if(session==null||closed)return;
        try{long start=SystemClock.elapsedRealtime();session.restore();session.requireBaseline();
            log("baseline_restored ms="+(SystemClock.elapsedRealtime()-start)+" capture_enabled=false");ready("原色已恢复");
        }catch(Exception e){error("restore",e);}
    }
    private boolean close(){
        if(closed)return true;ui.removeCallbacks(expire);
        try{if(session!=null)session.close();}catch(Exception e){error("close_restore",e);return false;}
        try{
            if(cameraEx!=null){cameraEx.getClass().getMethod("release").invoke(cameraEx);cameraEx=null;camera=null;}
            if(driver!=null)driver.cameraReleased();closed=true;log("closed baseline_restored=true");return true;
        }catch(Exception e){error("release",e);return false;}
    }
    @Override protected void onResume(){super.onResume();active=true;SonyAppPolicy.register(this);if(closed){finish();return;}open();}
    @Override protected void onPause(){active=false;close();super.onPause();}
    @Override protected void onDestroy(){close();super.onDestroy();
        if(closed)android.os.Process.killProcess(android.os.Process.myPid()); // isolated :previewprobe only
    }
    @Override public void onBackPressed(){if(close())finish();}
    @Override public boolean dispatchKeyEvent(KeyEvent e){
        if(UiKeys.shutter(e))return true;
        int key=UiKeys.key(e);
        if(key==UiKeys.NONE)return super.dispatchKeyEvent(e);
        if(e.getAction()!=KeyEvent.ACTION_DOWN||e.getRepeatCount()!=0)return true;
        if(key==UiKeys.BACK)onBackPressed();
        else if(key==UiKeys.DOWN)restore();
        else if(key==UiKeys.OK)apply();
        else if((key==UiKeys.LEFT||key==UiKeys.RIGHT)&&catalog!=null){
            restore();if(session!=null&&session.state()==NativePreviewSession.State.BASELINE){
                selected=(selected+catalog.length()+(key==UiKeys.LEFT?-1:1))%catalog.length();ready("原色 · 候选尚未应用");
            }
        }
        return true;
    }
    public void surfaceCreated(SurfaceHolder h){open();}
    public void surfaceChanged(SurfaceHolder h,int f,int w,int height){open();}
    public void surfaceDestroyed(SurfaceHolder h){close();}
}
