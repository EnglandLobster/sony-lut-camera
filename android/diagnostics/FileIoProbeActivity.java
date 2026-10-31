package org.hongen.sonylutprobe;

import android.app.Activity;
import android.os.*;
import android.widget.TextView;
import java.io.*;

/** Explicit read-only benchmark on existing JPEGs. Never opens camera hardware. */
public final class FileIoProbeActivity extends Activity {
    private final Handler ui=new Handler();private TextView text;
    @Override public void onCreate(Bundle state){super.onCreate(state);
        text=new TextView(this);text.setTextSize(20);text.setText("正在检查已有 JPEG 的文件读取开销…");setContentView(text);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final String path=getIntent().getStringExtra("photo");
        new Thread(new Runnable(){public void run(){
            try{
                File root=new File(Environment.getExternalStorageDirectory(),"DCIM").getCanonicalFile();
                if(path==null)throw new IOException("missing photo");
                File photo=new File(path).getCanonicalFile();
                if(!photo.getPath().startsWith(root.getPath()+File.separator)||!photo.getName().matches("[A-Z0-9_]{1,8}\\.JPG"))
                    throw new IOException("requires existing DCIM JPEG");
                String exportRun=getIntent().getStringExtra("export_run");
                if(exportRun!=null){
                    if(!exportRun.matches("R[0-9A-F]{7}"))throw new IOException("invalid diagnostic run");
                    File run=new File(new File(Environment.getExternalStorageDirectory(),"SONYLUT"),exportRun);
                    if(!run.isDirectory()||!PhotoFiles.jpegHeader(photo,6000,4000))throw new IOException("missing saved diagnostic/photo");
                    File dest=RunFiles.exportAdb(run,photo);
                    android.util.Log.i("SonyFileIo","export_complete directory="+dest+" original="+photo+" camera_opened=false");
                    show("已有诊断文件已导出 · 未拍照");return;
                }
                StringBuilder result=new StringBuilder();
                // Alternate order so page-cache/first-call warmup does not
                // systematically favor the new implementation. No SD writes.
                for(int round=0;round<6;round++)for(int step=0;step<2;step++){
                    boolean legacy=((round+step)&1)==0;long start=SystemClock.elapsedRealtime();
                    boolean valid=legacy?legacyHeader(photo,6000,4000):PhotoFiles.jpegHeader(photo,6000,4000);
                    long elapsed=SystemClock.elapsedRealtime()-start;
                    String row="round="+round+" legacy="+legacy+" ms="+elapsed+" valid="+valid+" file="+photo;
                    android.util.Log.i("SonyFileIo",row);result.append(row).append('\n');
                    if(!valid)throw new IOException("header rejected: "+row);
                }
                android.util.Log.i("SonyFileIo","complete camera_opened=false source_modified=false");
                show("读取对照完成 · 未拍照、未改写照片\n结果已记录。");
            }catch(Throwable e){android.util.Log.e("SonyFileIo","failed",e);show("读取对照失败："+e);}
        }},"jpeg-io-probe").start();
    }
    private void show(final String value){ui.post(new Runnable(){public void run(){if(!isFinishing())text.setText(value);}});}
    // Exact 0.6.27 reader retained only for this diagnostic comparison.
    static boolean legacyHeader(File file,int width,int height)throws IOException{
        RandomAccessFile in=new RandomAccessFile(file,"r");try{
            long size=in.length();if(size<4||in.readUnsignedShort()!=0xffd8)return false;
            while(in.getFilePointer()<Math.min(size,1024*1024)){
                if(in.read()!=255)return false;int marker;do{marker=in.read();}while(marker==255);
                if(marker<0||marker==0||marker==216||marker==217||marker==218)return false;
                int bytes=in.readUnsignedShort();if(bytes<2||in.getFilePointer()+bytes-2>size)return false;
                if(marker==192||marker==194){
                    if(bytes!=17)return false;
                    return in.read()==8&&in.readUnsignedShort()==height&&in.readUnsignedShort()==width&&in.read()==3;
                }
                in.seek(in.getFilePointer()+bytes-2);
            }
            return false;
        }catch(EOFException e){return false;}finally{in.close();}
    }
}
