package org.hongen.sonylutprobe;
import android.app.Activity;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.FrameLayout;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Browses recorded photo pairs without opening the camera interfaces. */
public final class GalleryActivity extends Activity {
    private final Handler ui=new Handler();private final ExecutorService io=Executors.newSingleThreadExecutor();
    private SonyReviewView image;private PhotoHud hud;private List<PhotoCatalog.Pair> photos;
    private SonyPhotoPreview loader;private int index,direction=1;private boolean original,destroyed,reading,active,leaving,infoVisible=true;
    private String desiredKey,displayedKey,drawKey;private long requestAt,drawAt;private boolean drawHit;
    private final Set<String> failedPrefetch=new HashSet<String>();
    private final Set<String> attempted=new HashSet<String>();
    private final PreviewCache<Bitmap> cache=new PreviewCache<Bitmap>(8L*1024*1024,new PreviewCache.Disposal<Bitmap>(){public void release(Bitmap bitmap){bitmap.recycle();}});
    @Override public void onCreate(Bundle saved){super.onCreate(saved);FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(Color.BLACK);
        hud=new PhotoHud(this);image=new SonyReviewView(this){@Override protected void pixelAspectChanged(float value){hud.pixelAspect=value;hud.update();}
            @Override protected void onDraw(Canvas c){super.onDraw(c);if(drawKey!=null&&drawKey.equals(displayedKey)){android.util.Log.i("LutUI","gallery_frame file="+new File(drawKey).getName()+" request_to_draw_ms="+(SystemClock.elapsedRealtime()-drawAt)+" cache_hit="+drawHit+" cache_bytes="+cache.bytes());drawKey=null;}}};
        frame.addView(image,new FrameLayout.LayoutParams(-1,-1));frame.addView(hud,new FrameLayout.LayoutParams(-1,-1));setContentView(frame);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);hud.detail="正在打开照片";
        io.execute(new Runnable(){public void run(){try{loader=new SonyPhotoPreview(GalleryActivity.this);final List<PhotoCatalog.Pair> loaded=new PhotoCatalog(ProductFiles.photos(),Environment.getExternalStorageDirectory()).list();
            ui.post(new Runnable(){public void run(){if(destroyed)return;photos=loaded;String id=getIntent().getStringExtra("pair_id");for(int i=0;i<photos.size();i++)if(photos.get(i).id.equals(id)){index=i;break;}
                index=Math.max(0,Math.min(Math.max(0,photos.size()-1),index+getIntent().getIntExtra("page_offset",0)));load();}});
        }catch(final Exception e){ui.post(new Runnable(){public void run(){error(e);}});}}});
    }
    private void error(Exception e){if(destroyed)return;hud.mode="error";hud.detail=e.getMessage()==null?"照片读取失败":e.getMessage();hud.progress="";hud.footer="MENU 返回照片列表";hud.update();}
    private File selectedFile(){PhotoCatalog.Pair p=photos.get(index);return original?p.original:p.graded;}
    private void load(){
        if(photos==null||photos.isEmpty()){hud.mode="error";hud.name="照片";hud.detail="还没有照片";hud.progress="拍下第一张照片后，在这里回看";hud.footer="MENU 返回";hud.update();return;}
        desiredKey=selectedFile().getPath();requestAt=SystemClock.elapsedRealtime();drawKey=null;failedPrefetch.remove(desiredKey);attempted.clear();
        Bitmap hit=cache.get(desiredKey);
        if(hit!=null)show(hit,true);
        else{hud.setVisibility(View.VISIBLE);hud.mode=displayedKey==null?"loading":"review";hud.name=photos.get(index).lut;
            hud.detail="正在打开"+(original?"原片":"LUT 成片");hud.tag="加载中";hud.progress="";hud.update();}
        schedule();
    }
    private void show(Bitmap bitmap,boolean hit){
        PhotoCatalog.Pair pair=photos.get(index);cache.pin(desiredKey);displayedKey=desiredKey;
        drawKey=desiredKey;drawAt=requestAt;drawHit=hit;image.setImageBitmap(bitmap);image.invalidate();
        // Storage is newest-first; display positions follow capture order, oldest = 1.
        int ordinal=photos.size()-index;
        hud.setVisibility(infoVisible?View.VISIBLE:View.INVISIBLE);hud.mode="review";hud.name=pair.lut;hud.tag=ordinal+" / "+photos.size();hud.original=original;
        hud.detail=selectedFile().getName()+" · 24MP · EXTRA FINE";hud.footer="←→ / 拨轮翻图  ● 对照  ↑ 信息  半按拍摄";hud.update();
        android.util.Log.i("LutUI","gallery_selected file="+selectedFile().getName()+" ordinal="+ordinal+" total="+photos.size()+" cache_hit="+hit+" request_ms="+(SystemClock.elapsedRealtime()-requestAt));
    }
    private void addCandidate(List<File> files,int position,boolean source){
        if(position>=0&&position<photos.size()){PhotoCatalog.Pair p=photos.get(position);files.add(source?p.original:p.graded);}
    }
    private List<File> plan(){
        List<File> files=new ArrayList<File>();addCandidate(files,index,original);
        // Navigation always opens the graded photo, even when comparing an original.
        addCandidate(files,index+direction,false);addCandidate(files,index-direction,false);
        addCandidate(files,index,!original);
        if(index==0||index==photos.size()-1)addCandidate(files,index+2*direction,false);
        return files;
    }
    private void schedule(){
        if(!active||destroyed||reading||photos==null||photos.isEmpty())return;
        List<File> window=plan();
        // Refresh existing members in priority order before inserting another
        // member. Otherwise eager prefetch evicts its own next/previous page.
        for(int i=window.size()-1;i>=0;i--)cache.get(window.get(i).getPath());
        File candidate=null;
        for(File f:window)if(!cache.contains(f.getPath())&&!failedPrefetch.contains(f.getPath())
                &&(f.getPath().equals(desiredKey)||!attempted.contains(f.getPath()))){candidate=f;break;}
        if(candidate==null)return;
        final File file=candidate;final String key=file.getPath();reading=true;
        // Exactly one native read in flight. On completion choose from the latest
        // desired page, never enqueue a history of obsolete key presses.
        io.execute(new Runnable(){public void run(){Bitmap result=null;Exception failure=null;
            try{result=loader.load(file);}catch(Exception e){failure=e;}
            final Bitmap loaded=result;final Exception error=failure;
            ui.post(new Runnable(){public void run(){reading=false;attempted.add(key);
                if(destroyed){if(loaded!=null)loaded.recycle();return;}
                if(leaving){if(loaded!=null)loaded.recycle();finish();return;}
                if(error!=null){failedPrefetch.add(key);android.util.Log.w("LutUI","gallery_load_failed file="+file.getName(),error);
                    if(key.equals(desiredKey))error(error);
                }else{
                    boolean wanted=false;for(File f:plan())if(f.getPath().equals(key)){wanted=true;break;}
                    List<File> current=plan();for(int i=current.size()-1;i>=0;i--)cache.get(current.get(i).getPath());
                    if(!wanted){loaded.recycle();android.util.Log.i("LutUI","gallery_obsolete file="+file.getName());}
                    else if(cache.put(key,loaded,(long)loaded.getRowBytes()*loaded.getHeight())){
                        android.util.Log.i("LutUI","gallery_cached file="+file.getName()+" cache_bytes="+cache.bytes());
                        if(key.equals(desiredKey))show(loaded,false);
                    }else{failedPrefetch.add(key);if(key.equals(desiredKey))error(new IOException("照片预览超过缓存容量"));}
                }
                schedule();
            }});
        }});
    }
    private void leave(boolean capture){
        if(leaving)return;leaving=true;active=false;
        if(capture)setResult(RESULT_OK,new android.content.Intent().putExtra("return_capture",true));
        // Drain the one native preview read before another Activity opens the
        // capture pipeline. No abandoned prefetch work crosses that boundary.
        if(!reading)finish();else{hud.footer=capture?"正在返回拍摄…":"正在返回 LUT 库…";hud.update();}
    }
    @Override public void onBackPressed(){leave(false);}
    private void turnPage(int delta){
        if(photos==null||photos.isEmpty())return;
        int next=Math.max(0,Math.min(photos.size()-1,index+delta));
        if(next!=index){direction=delta>0?1:-1;index=next;original=false;load();}
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e){int k=UiKeys.key(e),dial=UiKeys.dial(e);
        if(leaving)return true;
        if(k==UiKeys.PLAY||UiKeys.shutter(e)){
            if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0)leave(true);
            return true;
        }
        // Catalogue is newest-first: left means the previous (older) shot.
        if(dial!=0){if(e.getAction()==KeyEvent.ACTION_DOWN)turnPage(-dial);return true;}
        if(k==UiKeys.NONE)return super.dispatchKeyEvent(e);if(e.getAction()!=KeyEvent.ACTION_DOWN||e.getRepeatCount()!=0)return true;
        if(k==UiKeys.BACK){leave(false);return true;}if(photos==null||photos.isEmpty())return true;
        if(k==UiKeys.OK){original=!original;infoVisible=true;load();}
        else if(k==UiKeys.UP){infoVisible=!infoVisible;if(hud.mode.equals("review"))hud.setVisibility(infoVisible?View.VISIBLE:View.INVISIBLE);
            android.util.Log.i("LutUI","gallery_info visible="+infoVisible);}
        else if(k==UiKeys.LEFT||k==UiKeys.RIGHT)turnPage(k==UiKeys.LEFT?1:-1);
        return true;
    }
    @Override protected void onResume(){super.onResume();active=true;SonyAppPolicy.register(this);try{image.startDisplayTracking();schedule();}catch(Exception e){error(e);}}
    @Override protected void onPause(){active=false;image.stopDisplayTracking();super.onPause();}
    @Override protected void onDestroy(){destroyed=true;drawKey=null;io.shutdown();image.setImageBitmap(null);cache.clear();super.onDestroy();}
}
