package org.hongen.sonylutprobe;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.widget.TextView;

/** Exercises the actual cross-APK callback without disabling Wi-Fi or exiting. */
public final class ExitIpcProbeActivity extends Activity {
    private final Handler ui=new Handler();
    private SonyAppPolicy.ExitRequest request;
    private TextView status;
    private final Runnable timeout=new Runnable(){public void run(){
        if(request!=null)request.cancel();
        android.util.Log.e("ExitIpcProbe","FAIL timeout");status.setText("退出回执检查超时 · 网络未主动关闭");
    }};
    @Override public void onCreate(Bundle state){super.onCreate(state);
        status=new TextView(this);status.setTextSize(22);status.setText("正在检查退出回执 · 不关闭网络");setContentView(status);
        request=new SonyAppPolicy.ExitRequest(this,new SonyAppPolicy.ExitListener(){public void failed(Exception e){
            ui.removeCallbacks(timeout);android.util.Log.e("ExitIpcProbe","FAIL",e);status.setText("回执检查失败："+e);
        }},new Runnable(){public void run(){
            ui.removeCallbacks(timeout);android.util.Log.i("ExitIpcProbe","PASS real_cross_package_callback=true network_unchanged=true");
            status.setText("退出回执检查通过 · 网络保持连接\nMENU 返回");
        }});
        ui.postDelayed(timeout,8000);request.start();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(UiKeys.shutter(event))return true;
        if(UiKeys.key(event)==UiKeys.BACK){if(event.getAction()==KeyEvent.ACTION_UP)finish();return true;}
        return super.dispatchKeyEvent(event);
    }
    @Override protected void onPause(){if(request!=null)request.cancel();ui.removeCallbacks(timeout);super.onPause();}
}
