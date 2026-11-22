package io.github.englandlobster.lutcamera;
import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.FrameLayout;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Browses recorded photo pairs: one view plus its keys. GalleryActivity hosts
 * it from the LUT library; the capture screen hosts it over its open camera, so
 * a half-press returns to shooting without reopening the camera (owner,
 * 2026-11-22: the reopen took about 6 s on the A6300). Never opens the camera
 * itself; screen-nails come from Sony's index, one read at a time. */
final class GalleryBrowser {
    /** A photo already in memory, shown while the history is listed: the
     * capture screen's review of the shot it just saved. The browser owns it. */
    static final class Seed {
        final File file;final Bitmap bitmap;final String lut;
        Seed(File file,Bitmap bitmap,String lut){this.file=file;this.bitmap=bitmap;this.lut=lut;}
    }
    interface Host {
        /** Browsing ended: capture=true for half-press/PLAY, false for MENU.
         * Called once. Unless the host chose not to drain for capture, no
         * screen-nail read is in flight. */
        void left(boolean capture);
        /** UI thread: a fresh listing finished, even if browsing already
         * ended, so a host can keep it rather than read the index again. */
        void listed(List<PhotoCatalog.Pair> photos);
    }
    private final Context context;private final Host host;private final boolean drainBeforeCapture;private final String exitHint;
    private final Handler ui=new Handler();private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final FrameLayout frame;private final SonyReviewView image;private final PhotoHud hud;private List<PhotoCatalog.Pair> photos;
    private SonyPhotoPreview loader;private int index,direction=1;private boolean original,destroyed,reading,active,leaving,leaveCapture,leftNotified,tracking,infoVisible=true;
    private PhotoCatalog catalog;private PhotoDelete remover;private boolean confirming,deleting;
    private String desiredKey,displayedKey,drawKey;private long requestAt,drawAt;private boolean drawHit;
    private final Set<String> failedPrefetch=new HashSet<String>();
    private final Set<String> attempted=new HashSet<String>();
    private final PreviewCache<Bitmap> cache;
    /** drainBeforeCapture=false: the host's camera is open; no read may use
     * Sony's screen-nail decoder, and returning to capture does not wait for
     * a read. exitHint names where MENU goes, e.g. "MENU 返回". */
    GalleryBrowser(Context context,Host host,long cacheBytes,boolean drainBeforeCapture,String exitHint){
        this.context=context;this.host=host;this.drainBeforeCapture=drainBeforeCapture;this.exitHint=exitHint;
        cache=new PreviewCache<Bitmap>(cacheBytes,new PreviewCache.Disposal<Bitmap>(){public void release(Bitmap bitmap){bitmap.recycle();}});
        frame=new FrameLayout(context);frame.setBackgroundColor(Color.BLACK);
        hud=new PhotoHud(context);hud.reviewPanel=true;image=new SonyReviewView(context){@Override protected void pixelAspectChanged(float value){hud.pixelAspect=value;hud.update();}
            @Override protected void onDraw(Canvas c){super.onDraw(c);if(drawKey!=null&&drawKey.equals(displayedKey)){android.util.Log.i("LutUI","gallery_frame file="+new File(drawKey).getName()+" request_to_draw_ms="+(SystemClock.elapsedRealtime()-drawAt)+" cache_hit="+drawHit+" cache_bytes="+cache.bytes());drawKey=null;}}};
        frame.addView(image,new FrameLayout.LayoutParams(-1,-1));frame.addView(hud,new FrameLayout.LayoutParams(-1,-1));
        hud.detail="正在打开照片";
    }
    View view(){return frame;}
    boolean reading(){return reading;}
    /** The history as listed and edited here, or null before it is listed. */
    List<PhotoCatalog.Pair> photos(){return photos==null?null:new ArrayList<PhotoCatalog.Pair>(photos);}
    /** A delete is running; the host must not move on to capture meanwhile. */
    boolean deleting(){return deleting;}
    /** For a host whose own view already tracks the Sony display. */
    void pixelAspect(float value){image.assumePixelAspect(value);}
    /** Lists the history, then shows the pair (null: newest) moved by offset. */
    void open(String pairId,int offset){open(pairId,offset,null,null);}
    /** known: the history this host already listed and kept current, or null
     * to list it. seed: shown at once while listing, or null. */
    void open(final String pairId,final int offset,final List<PhotoCatalog.Pair> known,final Seed seed){
        io.execute(new Runnable(){public void run(){try{long begin=SystemClock.elapsedRealtime();
            if(seed!=null)showSeed(seed);
            loader=new SonyPhotoPreview(context,drainBeforeCapture);long ready=SystemClock.elapsedRealtime();
            File photosDir=ProductFiles.photos();
            catalog=new PhotoCatalog(photosDir,Environment.getExternalStorageDirectory());
            final List<PhotoCatalog.Pair> loaded;
            if(known!=null){
                loaded=new ArrayList<PhotoCatalog.Pair>(known);
                SupportDiagnostics.event("gallery_list","photos="+loaded.size()+" kept=true total_ms="+(SystemClock.elapsedRealtime()-begin));
            }else{
                // First open after an update: every receipt is read once (~45 ms each).
                if(!new File(photosDir,PhotoCatalog.CACHE).exists()&&!new File(photosDir,PhotoCatalog.JOURNAL).exists())
                    ui.post(new Runnable(){public void run(){if(destroyed||photos!=null)return;hud.detail="正在建立相册索引";hud.progress="只在第一次需要，照片多时要十几秒";hud.update();}});
                loaded=catalog.list();
                String timing="photos="+loaded.size()+" loader_ms="+(ready-begin)+" total_ms="+(SystemClock.elapsedRealtime()-begin)+" "+catalog.timing;
                android.util.Log.i("LutUI","gallery_list "+timing);SupportDiagnostics.event("gallery_list",timing);
                if(catalog.pruned>0)SupportDiagnostics.event("gallery_pruned","count="+catalog.pruned);
                ui.post(new Runnable(){public void run(){host.listed(new ArrayList<PhotoCatalog.Pair>(loaded));}});
            }
            ui.post(new Runnable(){public void run(){if(destroyed||leaving)return;photos=loaded;for(int i=0;i<photos.size();i++)if(photos.get(i).id.equals(pairId)){index=i;break;}
                index=Math.max(0,Math.min(Math.max(0,photos.size()-1),index+offset));load();}});
        }catch(final Throwable e){ui.post(new Runnable(){public void run(){error(failure(e));}});}}});
    }
    /** io thread: orient the seed like its saved photo, then show it unless
     * the list arrived first or the browser is gone. */
    private void showSeed(final Seed seed){
        if(!seed.file.isFile()){seed.bitmap.recycle();return;} // deleted meanwhile
        long begin=SystemClock.elapsedRealtime();Bitmap oriented=seed.bitmap;int orientation=1;
        try{orientation=JpegPreview.orientation(seed.file);oriented=ReviewLoader.rotate(seed.bitmap,orientation);}
        catch(Throwable e){android.util.Log.w("LutUI","gallery_seed_orientation file="+seed.file.getName(),e);}
        final Bitmap bitmap=oriented;final long ms=SystemClock.elapsedRealtime()-begin;final int turned=orientation;
        ui.post(new Runnable(){public void run(){
            String key=seed.file.getPath();
            if(destroyed||leaving||photos!=null){bitmap.recycle();return;}
            if(!cache.put(key,bitmap,(long)bitmap.getRowBytes()*bitmap.getHeight()))return; // put released it
            desiredKey=key;cache.pin(key);displayedKey=key;drawKey=key;drawAt=requestAt=SystemClock.elapsedRealtime();drawHit=true;
            image.setImageBitmap(bitmap);image.invalidate();
            hud.setVisibility(View.VISIBLE);hud.mode="review";hud.name=seed.lut;hud.tag="";hud.original=false;hud.hasOriginal=false;
            hud.detail=seed.file.getName();hud.footer="正在读取照片列表  半按拍摄";hud.update();
            android.util.Log.i("LutUI","gallery_seeded file="+seed.file.getName()+" orientation="+turned+" prepare_ms="+ms);
        }});
    }
    /** The capture process must survive a failed read: an OutOfMemoryError
     * here would otherwise end it with the camera open. */
    private static Exception failure(Throwable e){return e instanceof Exception?(Exception)e:new IOException("内存不足，照片暂时打不开",e);}
    private void error(Exception e){SupportDiagnostics.failure("gallery",e);if(destroyed)return;hud.mode="error";hud.detail=e.getMessage()==null?"照片读取失败":e.getMessage();hud.progress="";hud.footer=exitHint+"  半按拍摄";hud.update();}
    private File selectedFile(){PhotoCatalog.Pair p=photos.get(index);return original&&p.original!=null?p.original:p.graded;}
    private void load(){
        if(photos==null||photos.isEmpty()){hud.mode="error";hud.name="照片";hud.detail="还没有照片";hud.progress="拍下第一张照片后，在这里回看";hud.footer=exitHint+"  半按拍摄";hud.update();return;}
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
        hud.hasOriginal=pair.original!=null;
        hud.detail=selectedFile().getName();hud.footer=pair.original!=null?"←→ / 拨轮翻图  ● 对照  ↑ 信息  ↓ 删除  半按拍摄":"←→ / 拨轮翻图  ↑ 信息  ↓ 删除  半按拍摄 · 未保存原片";hud.update();
        android.util.Log.i("LutUI","gallery_selected file="+selectedFile().getName()+" ordinal="+ordinal+" total="+photos.size()+" cache_hit="+hit+" request_ms="+(SystemClock.elapsedRealtime()-requestAt));
    }
    private void addCandidate(List<File> files,int position,boolean source){
        if(position>=0&&position<photos.size()){PhotoCatalog.Pair p=photos.get(position);File f=source?p.original:p.graded;if(f!=null)files.add(f);}
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
            try{result=loader.load(file);}catch(Throwable e){failure=failure(e);}
            final Bitmap loaded=result;final Exception error=failure;
            ui.post(new Runnable(){public void run(){reading=false;attempted.add(key);
                if(destroyed){if(loaded!=null)loaded.recycle();return;}
                if(leaving){if(loaded!=null)loaded.recycle();notifyLeft();return;}
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
    /** Leaves for capture (true) or the LUT library (false). */
    void leave(boolean capture){
        if(leaving)return;leaving=true;active=false;leaveCapture=capture;
        // Drain the one native preview read before the host changes the camera
        // pipeline. No abandoned prefetch work crosses that boundary.
        if(!reading||(capture&&!drainBeforeCapture))notifyLeft();
        else{hud.footer=capture?"正在返回拍摄…":"正在返回 LUT 库…";hud.update();}
    }
    private void notifyLeft(){if(leftNotified||destroyed)return;leftNotified=true;host.left(leaveCapture);}
    private void turnPage(int delta){
        if(photos==null||photos.isEmpty())return;
        int next=Math.max(0,Math.min(photos.size()-1,index+delta));
        if(next!=index){direction=delta>0?1:-1;index=next;original=false;load();}
    }
    /** False for keys the browser does not use; the host may pass them on. */
    boolean dispatchKey(KeyEvent e){if(UiKeys.modeDial(e))return true; // Sony owns the PASM dial; never let it close the app.
        int k=UiKeys.key(e),dial=UiKeys.dial(e);
        if(leaving||deleting)return true;
        if(confirming){
            if(k==UiKeys.PLAY||UiKeys.shutter(e))closeConfirm();
            else{
                if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0){
                    if(k==UiKeys.UP||k==UiKeys.DOWN||dial!=0){hud.panelSelected=1-hud.panelSelected;hud.update();}
                    else if(k==UiKeys.OK){if(hud.panelSelected==1)deleteSelected();else closeConfirm();}
                    else if(k==UiKeys.BACK)closeConfirm();
                }
                return k!=UiKeys.NONE||dial!=0;
            }
        }
        if(k==UiKeys.PLAY||UiKeys.shutter(e)){
            if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0)leave(true);
            return true;
        }
        // Catalogue is newest-first: left means the previous (older) shot.
        if(dial!=0){if(e.getAction()==KeyEvent.ACTION_DOWN)turnPage(-dial);return true;}
        if(k==UiKeys.NONE)return false;if(e.getAction()!=KeyEvent.ACTION_DOWN||e.getRepeatCount()!=0)return true;
        if(k==UiKeys.BACK){leave(false);return true;}if(photos==null||photos.isEmpty())return true;
        if(k==UiKeys.OK){if(photos.get(index).original==null)return true;original=!original;infoVisible=true;load();}
        else if(k==UiKeys.UP){infoVisible=!infoVisible;if(hud.mode.equals("review"))hud.setVisibility(infoVisible?View.VISIBLE:View.INVISIBLE);
            android.util.Log.i("LutUI","gallery_info visible="+infoVisible);}
        else if(k==UiKeys.LEFT||k==UiKeys.RIGHT)turnPage(k==UiKeys.LEFT?1:-1);
        else if(k==UiKeys.DOWN)confirmDelete();
        return true;
    }
    // Sony playback style: the confirmation defaults to 取消, so a stray
    // centre press never deletes. RAW and original go with the graded photo.
    private void confirmDelete(){
        PhotoCatalog.Pair p=photos.get(index);
        hud.panelTitle="删除这张照片？";hud.panelRows=new String[]{"取消","删除"};hud.panelValues=null;hud.panelSelected=0;
        hud.panelHint=p.original!=null?"成片与原片一起删除，不能恢复":PhotoDelete.raw(p.graded)!=null?"成片与同编号 RAW 一起删除，不能恢复":"删除后不能恢复";
        confirming=true;hud.setVisibility(View.VISIBLE);hud.update();
        android.util.Log.i("LutUI","gallery_delete_confirm file="+p.graded.getName());
    }
    private void closeConfirm(){confirming=false;hud.panelRows=null;hud.panelHint="";
        if(hud.mode.equals("review"))hud.setVisibility(infoVisible?View.VISIBLE:View.INVISIBLE);hud.update();}
    private void deleteSelected(){
        final PhotoCatalog.Pair p=photos.get(index);closeConfirm();deleting=true;
        hud.setVisibility(View.VISIBLE);hud.detail="正在删除 "+p.graded.getName();hud.footer="请稍候，不要关机";hud.update();
        io.execute(new Runnable(){public void run(){
            Exception failure=null;List<PhotoCatalog.Pair> reloaded=null;
            try{if(remover==null)remover=new PhotoDelete(context);
                if(p.original!=null)remover.delete(p.original);
                remover.delete(p.graded);catalog.remove(p);}
            catch(Throwable e){failure=failure(e);}
            // Only a failure re-reads the history (a pair may be half deleted);
            // listing reads every receipt, about 45 ms each on the A6300.
            if(failure!=null)try{reloaded=catalog.list();}catch(Throwable ignored){}
            final Exception error=failure;final List<PhotoCatalog.Pair> loaded=reloaded;
            if(error==null)SupportDiagnostics.event("photo_delete","graded="+p.graded.getName()+" original="+(p.original!=null));
            else SupportDiagnostics.failure("photo_delete",error);
            ui.post(new Runnable(){public void run(){deleting=false;
                if(destroyed)return;if(leaving){notifyLeft();return;}
                if(error==null){List<PhotoCatalog.Pair> rest=new ArrayList<PhotoCatalog.Pair>(photos);rest.remove(p);photos=rest;}
                else if(loaded!=null)photos=loaded;
                index=Math.max(0,Math.min(photos.size()-1,index));original=false;
                // Never leave the deleted photograph on screen while the next one loads.
                displayedKey=null;image.setImageBitmap(null);load();
                if(error!=null){hud.setVisibility(View.VISIBLE);hud.detail="删除失败："+(error.getMessage()==null?"请重试":error.getMessage());hud.update();}
            }});
        }});
    }
    /** trackDisplay: this view follows the Sony panel itself (standalone screen). */
    void resume(boolean trackDisplay){
        active=!leaving;
        try{if(trackDisplay){image.startDisplayTracking();tracking=true;}schedule();}catch(Exception e){error(e);}
    }
    void pause(){active=false;if(tracking){image.stopDisplayTracking();tracking=false;}}
    void destroy(){if(destroyed)return;pause();destroyed=true;drawKey=null;io.shutdown();image.setImageBitmap(null);cache.clear();}
}
