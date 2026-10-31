package org.hongen.sonylutprobe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.widget.ImageView;
import java.lang.reflect.*;

/** Bitmap display that honors the active Sony panel/finder's physical aspect. */
class SonyReviewView extends ImageView {
    private Object displayManager;
    private Class<?> managerType;
    private float pixelAspect=1;
    private boolean displayKnown;
    private SonyGraphicsPrecision graphicsPrecision;
    private final Matrix fitMatrix=new Matrix();

    SonyReviewView(Context context){super(context);setScaleType(ScaleType.MATRIX);}

    void startDisplayTracking()throws Exception{
        if(displayManager!=null)return;
        managerType=Class.forName("com.sony.scalar.hardware.avio.DisplayManager");
        displayManager=managerType.newInstance();
        try{
            graphicsPrecision=new SonyGraphicsPrecision();graphicsPrecision.acquire();
            Class<?> listenerType=Class.forName("com.sony.scalar.hardware.avio.DisplayManager$DisplayEventListener");
            Object listener=Proxy.newProxyInstance(listenerType.getClassLoader(),new Class<?>[]{listenerType},new InvocationHandler(){
                public Object invoke(Object proxy,Method method,Object[] args){
                    if(method.getDeclaringClass()==Object.class){
                        if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                        if(method.getName().equals("equals"))return proxy==args[0];
                        return "ReviewDisplayListener";
                    }
                    post(new Runnable(){public void run(){
                        if(displayManager==null)return;
                        try{readDisplay();}catch(Exception e){displayKnown=false;setVisibility(INVISIBLE);displayFailed(e);}
                    }});
                    return null;
                }
            });
            managerType.getMethod("setDisplayStatusListener",listenerType).invoke(displayManager,listener);
            readDisplay();
        }catch(Exception e){stopDisplayTracking();throw e;}
    }

    private void readDisplay()throws Exception{
        String device=(String)managerType.getMethod("getActiveDevice").invoke(displayManager);
        if(device==null||device.equals(managerType.getField("DEVICE_ID_NONE").get(null)))return;
        Object info=managerType.getMethod("getDeviceInfo",String.class).invoke(displayManager,device);
        int aspect=info.getClass().getField("aspect").getInt(info);
        String[] names={"ASPECT_RATIO_3_2","ASPECT_RATIO_4_3","ASPECT_RATIO_5_3","ASPECT_RATIO_11_9","ASPECT_RATIO_16_9"};
        float[] ratios={3f/2,4f/3,5f/3,11f/9,16f/9};
        float physical=0;
        for(int i=0;i<names.length;i++)if(aspect==managerType.getField(names[i]).getInt(null))physical=ratios[i];
        Display display=((WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        int width=display.getWidth(),height=display.getHeight();
        if(!(physical>0)||width<=0||height<=0)throw new IllegalStateException("unknown Sony display geometry: "+device+"/"+aspect);
        pixelAspect=physical*height/width;displayKnown=true;
        pixelAspectChanged(pixelAspect);
        Log.i("SonyReview","display device="+device+" framebuffer="+width+"x"+height+" physical_aspect="+physical+" pixel_aspect="+pixelAspect);
        fit();
    }

    protected void displayFailed(Exception e){Log.e("SonyReview","display_changed_failed",e);}
    protected void pixelAspectChanged(float value){}

    void showGraphicsOutput()throws Exception{
        if(displayManager==null)throw new IllegalStateException("display manager is not active");
        // Sony ProgressLayout restores OSD output 300ms after making its UI
        // visible. Capture can mute this plane independently of Android views.
        managerType.getMethod("controlGraphicsOutputAll",boolean.class).invoke(displayManager,true);
    }

    void stopDisplayTracking(){
        Object manager=displayManager;displayManager=null;displayKnown=false;
        if(graphicsPrecision!=null){try{graphicsPrecision.release();graphicsPrecision=null;}
            catch(Exception e){Log.e("SonyReview","graphics_precision_restore_failed",e);}}
        if(manager==null)return;
        try{managerType.getMethod("releaseDisplayStatusListener").invoke(manager);}
        catch(Exception e){Log.w("SonyReview","listener_release_failed",e);}
        finally{
            try{managerType.getMethod("finish").invoke(manager);}
            catch(Exception e){Log.w("SonyReview","display_finish_failed",e);}
        }
    }

    @Override public void setImageBitmap(Bitmap bitmap){
        // Android 4.1 wraps even null in a BitmapDrawable whose intrinsic
        // dimensions are invalid. Clearing is an empty-view operation, not a fit.
        if(bitmap==null){super.setImageDrawable(null);fitMatrix.reset();setImageMatrix(fitMatrix);return;}
        super.setImageBitmap(bitmap);fit();
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);fit();}

    private void fit(){
        Drawable image=getDrawable();
        if(!displayKnown||image==null||getWidth()==0||getHeight()==0)return;
        int w=image.getIntrinsicWidth(),h=image.getIntrinsicHeight();
        float[] fit=ReviewGeometry.fit(w,h,getWidth(),getHeight(),pixelAspect);
        fitMatrix.setScale(fit[0],fit[1]);fitMatrix.postTranslate(fit[2],fit[3]);setImageMatrix(fitMatrix);
        Log.i("SonyReview","fit image="+w+"x"+h+" view="+getWidth()+"x"+getHeight()+" scale="+fit[0]+","+fit[1]+" offset="+fit[2]+","+fit[3]);
    }
}
