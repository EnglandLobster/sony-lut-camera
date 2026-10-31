package org.hongen.sonylutprobe;
import android.app.Activity;
import android.os.*;
import android.widget.TextView;
import java.io.*;

/** Explicit developer staging/check entry. Never opens camera hardware.
 * Only fixed scratch inputs; copies to LUTS for the ordinary product importer. */
public final class PreviewFitProbeActivity extends Activity {
    private final Handler ui=new Handler();private TextView text;
    @Override public void onCreate(Bundle state){super.onCreate(state);text=new TextView(this);text.setText("正在准备 LUT 导入测试…");setContentView(text);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(new Runnable(){public void run(){try{
            File source=new File("/mnt/wv_cache/AUTOFIT"),dest=new File(Environment.getExternalStorageDirectory(),"LUTS");
            LutLibrary.ensureDirectory(dest);File[] files=source.listFiles();if(files==null)throw new IOException("missing fixed staging directory");int count=0;
            for(File input:files){if(!input.getName().matches("F[0-9]{2}\\.CUB")||!input.isFile())continue;
                if(!source.getCanonicalFile().equals(input.getCanonicalFile().getParentFile())||input.length()>LutLibrary.MAX_BYTES)throw new IOException("invalid staged input");
                File output=new File(dest,input.getName());
                if(output.exists()){if(!LutBinaryCache.digest(input).equals(LutBinaryCache.digest(output)))throw new IOException("destination exists: "+output);}
                else{RunFiles.copy(input,output);if(!LutBinaryCache.digest(input).equals(LutBinaryCache.digest(output)))throw new IOException("staged copy differs");}
                count++;android.util.Log.i("SonyPreviewFit","staged "+output+" bytes="+output.length());
            }
            android.util.Log.i("SonyPreviewFit","staging_complete count="+count+" camera_opened=false");show("已准备 "+count+" 个测试 LUT · 请从 LUT 库正常导入");
        }catch(final Exception e){android.util.Log.e("SonyPreviewFit","staging_failed",e);show("准备失败："+e);}}},"preview-stage").start();
    }
    private void show(final String message){ui.post(new Runnable(){public void run(){if(!isFinishing())text.setText(message);}});}
}
