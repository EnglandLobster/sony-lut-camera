package org.hongen.sonylutprobe;
import android.app.Activity;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;

/** Same decoded photograph, only Sony's downstream OSD precision changes.
 * No camera, LUT, JPEG encode, pixel edit or persistent device setting. */
public final class GraphicsDepthProbeActivity extends Activity {
    private SonyReviewView image;private TextView label;private Bitmap bitmap;
    private boolean high,active;private final Handler ui=new Handler();
    private void depth(boolean value)throws Exception{
        Class<?> lib=Class.forName("com.sony.scalar.sysutil.didep.Gpelibrary");
        Class<?> type=Class.forName("com.sony.scalar.sysutil.didep.Gpelibrary$GS_FRAMEBUFFER_TYPE");
        lib.getMethod("changeFrameBufferPixel",type).invoke(null,type.getField(value?"ABGR8888":"RGBA4444").get(null));
        high=value;android.util.Log.i("SonyGraphicsDepth","mode="+(high?"ABGR8888":"RGBA4444")+" same_bitmap=true");
        label.setText((high?"B · 每通道 8 位显示":"A · 每通道 4 位显示")+"\n中央键切换 A / B · MENU 返回");image.invalidate();label.invalidate();
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(0xff000000);image=new SonyReviewView(this);
        root.addView(image,new FrameLayout.LayoutParams(-1,-1));label=new TextView(this);label.setTypeface(UiFont.get(this));label.setTextColor(-1);label.setTextSize(18);label.setBackgroundColor(0xb0000000);
        root.addView(label,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));setContentView(root);
        final String path=getIntent().getStringExtra("photo");
        new Thread(new Runnable(){public void run(){try{
            File file=new File(path).getCanonicalFile(),dcim=new File(Environment.getExternalStorageDirectory(),"DCIM").getCanonicalFile();
            if(!file.getPath().startsWith(dcim.getPath()+File.separator)||!file.getName().matches("[A-Z0-9_]{1,8}\\.JPG"))throw new IOException("existing DCIM photo required");
            final Bitmap loaded=new SonyPhotoPreview(GraphicsDepthProbeActivity.this).load(file);
            ui.post(new Runnable(){public void run(){if(!active||isFinishing()){loaded.recycle();return;}bitmap=loaded;image.setImageBitmap(bitmap);
                android.util.Log.i("SonyGraphicsDepth","loaded config="+bitmap.getConfig()+" width="+bitmap.getWidth()+" height="+bitmap.getHeight());}});
        }catch(final Exception e){android.util.Log.e("SonyGraphicsDepth","load_failed",e);ui.post(new Runnable(){public void run(){label.setText("读取失败："+e);}});}}},"depth-photo").start();
    }
    @Override protected void onResume(){super.onResume();active=true;SonyAppPolicy.register(this);
        try{image.startDisplayTracking();depth(false);}catch(Exception e){android.util.Log.e("SonyGraphicsDepth","mode_failed",e);label.setText("显示模式不可用："+e);}
    }
    @Override protected void onPause(){active=false;try{depth(false);}catch(Exception e){android.util.Log.e("SonyGraphicsDepth","restore_failed",e);}image.stopDisplayTracking();super.onPause();}
    @Override protected void onDestroy(){image.setImageBitmap(null);if(bitmap!=null)bitmap.recycle();super.onDestroy();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){int key=UiKeys.key(event);
        if(key==UiKeys.OK){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0&&bitmap!=null)try{depth(!high);}catch(Exception e){label.setText("切换失败："+e);}return true;}
        if(key==UiKeys.BACK){if(event.getAction()==KeyEvent.ACTION_UP)finish();return true;}
        if(UiKeys.shutter(event))return true;
        return super.dispatchKeyEvent(event);
    }
}
