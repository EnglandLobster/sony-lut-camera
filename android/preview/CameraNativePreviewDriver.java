package org.hongen.sonylutprobe;

import android.hardware.Camera;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Session-local documented CameraEx entry points, no Backup/MMIO writes.
 * Keep our GammaTable alive until detached; never release a borrowed table. */
final class CameraNativePreviewDriver implements NativePreviewSession.Driver {
    private final Object cameraEx;
    private final Camera camera;
    private final Class<?> gammaType,modifierType;
    private final Map<String,String> baseline=new LinkedHashMap<String,String>();
    private Object originalGamma,ownedGamma;
    private boolean attached;
    private static final String[] KEYS={"rgb-matrix-mode","rgb-matrix","jpeg-quality","picture-size",
        "storage-fmt","drive-mode","whitebalance","light-balance-for-white-balance","color-compensation-for-white-balance",
        "color-mode","picture-effect","d-range-optimizer","noise-reduction"};
    CameraNativePreviewDriver(Object ex,Camera camera)throws Exception{
        cameraEx=ex;this.camera=camera;
        gammaType=Class.forName("com.sony.scalar.hardware.CameraEx$GammaTable");
        modifierType=Class.forName("com.sony.scalar.hardware.CameraEx$ParametersModifier");
    }
    private Object gamma()throws Exception{return cameraEx.getClass().getMethod("getExtendedGammaTable").invoke(cameraEx);}
    private void setGamma(Object value)throws Exception{
        cameraEx.getClass().getMethod("setExtendedGammaTable",gammaType).invoke(cameraEx,value);
    }
    private static boolean equal(Object a,Object b){return a==null?b==null:a.equals(b);}
    public void snapshot()throws Exception{
        Camera.Parameters p=camera.getParameters();
        if(!"true".equals(p.get("rgb-matrix-supported"))||!"true".equals(p.get("extended-gamma-table-supported")))
            throw new IOException("matrix/gamma not advertised by this camera");
        String mode=p.get("rgb-matrix-mode");
        if(!"true".equals(mode)&&!"false".equals(mode))throw new IOException("unknown original matrix mode");
        for(String key:KEYS)baseline.put(key,p.get(key));
        originalGamma=gamma();
        // Getter returns this CameraEx object's cached table, not a hardware dump.
        android.util.Log.i("NativePreview","snapshot matrix_mode="+mode+" matrix="+p.get("rgb-matrix")
            +" gamma_object="+(originalGamma!=null)+" quality="+p.get("jpeg-quality")+" size="+p.get("picture-size"));
    }
    public void applyMatrix(int[] matrix)throws Exception{
        Camera.Parameters p=camera.getParameters();
        Object modifier=cameraEx.getClass().getMethod("createParametersModifier",Camera.Parameters.class).invoke(cameraEx,p);
        modifierType.getMethod("setRGBMatrix",int[].class).invoke(modifier,(Object)matrix);
        camera.setParameters(p);
    }
    public void applyGamma(byte[] bytes)throws Exception{
        if(ownedGamma!=null)throw new IllegalStateException("previous gamma retained");
        ownedGamma=cameraEx.getClass().getMethod("createGammaTable").invoke(cameraEx);
        if(ownedGamma==null)throw new IOException("gamma allocation returned null");
        int size=((Integer)gammaType.getMethod("getSize").invoke(ownedGamma)).intValue();
        if(size!=bytes.length)throw new IOException("gamma buffer size "+size+" expected "+bytes.length);
        // Do not bypass the user's native Picture Effect gamma.
        gammaType.getMethod("setPictureEffectGammaForceOff",boolean.class).invoke(ownedGamma,false);
        int written=((Integer)gammaType.getMethod("write",InputStream.class)
            .invoke(ownedGamma,new ByteArrayInputStream(bytes))).intValue();
        if(written!=bytes.length)throw new IOException("short gamma write "+written);
        attached=true; // Conservatively mark before the native call can fail.
        setGamma(ownedGamma);
    }
    public void verifyApplied(int[] matrix,byte[] bytes)throws Exception{
        Camera.Parameters p=camera.getParameters();
        StringBuilder expected=new StringBuilder();for(int v:matrix){if(expected.length()>0)expected.append(',');expected.append(v);}
        if(!"true".equals(p.get("rgb-matrix-mode"))||!expected.toString().equals(p.get("rgb-matrix")))
            throw new IOException("matrix parameter readback mismatch");
        if(gamma()!=ownedGamma)throw new IOException("gamma object not attached");
        byte[] readback=new byte[bytes.length];
        int read=((Integer)gammaType.getMethod("read",byte[].class).invoke(ownedGamma,(Object)readback)).intValue();
        if(read!=bytes.length||!Arrays.equals(bytes,readback))throw new IOException("gamma buffer readback mismatch");
        verifyProtected(p);
    }
    public void restoreGamma()throws Exception{
        setGamma(originalGamma);
        if(gamma()!=originalGamma)throw new IOException("gamma object restore mismatch");
        attached=false;
    }
    public void restoreMatrix()throws Exception{
        Camera.Parameters p=camera.getParameters();
        for(String key:new String[]{"rgb-matrix-mode","rgb-matrix"}){
            String value=baseline.get(key);if(value==null)p.remove(key);else p.set(key,value);
        }
        camera.setParameters(p);
    }
    private void verifyProtected(Camera.Parameters p)throws IOException{
        for(String key:KEYS)if(!key.startsWith("rgb-matrix")&&!equal(baseline.get(key),p.get(key)))
            throw new IOException("preview changed protected field "+key);
    }
    public void verifyBaseline()throws Exception{
        Camera.Parameters p=camera.getParameters();
        for(String key:KEYS)if(!equal(baseline.get(key),p.get(key)))throw new IOException("baseline mismatch "+key);
        if(gamma()!=originalGamma)throw new IOException("baseline gamma object mismatch");
    }
    public void releaseOwned()throws Exception{
        if(attached)throw new IllegalStateException("cannot release attached gamma");
        if(ownedGamma!=null){gammaType.getMethod("release").invoke(ownedGamma);ownedGamma=null;}
    }
    /** Only after successful CameraEx.release: consumer is gone even on rollback failure. */
    void cameraReleased()throws Exception{attached=false;releaseOwned();}
}
