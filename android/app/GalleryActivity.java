package io.github.englandlobster.lutcamera;
import android.app.Activity;
import android.os.*;
import android.view.*;

/** Browses recorded photo pairs from the LUT library, without opening the
 * camera interfaces. From capture, the same browser runs over the open camera
 * (see {@link GalleryBrowser}). */
public final class GalleryActivity extends Activity {
    private GalleryBrowser browser;
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        browser=new GalleryBrowser(this,new GalleryBrowser.Host(){public void left(boolean capture){
            if(capture)setResult(RESULT_OK,new android.content.Intent().putExtra("return_capture",true));finish();
        }
        public void listed(java.util.List<PhotoCatalog.Pair> photos){}},8L*1024*1024,true,"MENU 返回");
        setContentView(browser.view());getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        browser.open(getIntent().getStringExtra("pair_id"),getIntent().getIntExtra("page_offset",0));
    }
    @Override public void onBackPressed(){browser.leave(false);}
    @Override public boolean dispatchKeyEvent(KeyEvent e){return browser.dispatchKey(e)||super.dispatchKeyEvent(e);}
    @Override protected void onResume(){super.onResume();SonyAppPolicy.register(this);browser.resume(true);}
    @Override protected void onPause(){SupportDiagnostics.lifecycle(this,"pause");browser.pause();super.onPause();}
    @Override protected void onDestroy(){SupportDiagnostics.lifecycle(this,"destroy");browser.destroy();super.onDestroy();}
}
