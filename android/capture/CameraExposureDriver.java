package org.hongen.sonylutprobe;
import android.hardware.Camera;
import java.util.LinkedHashMap;
import java.util.Map;

/** Sony CameraEx extends the normal Camera.Parameters map with ISO and metering. */
final class CameraExposureDriver implements SimpleExposure.Driver {
    private final Camera camera;
    CameraExposureDriver(Camera camera){this.camera=camera;}
    public Map<String,String> read(){
        Map<String,String> result=new LinkedHashMap<String,String>();
        for(String item:camera.getParameters().flatten().split(";")){
            int i=item.indexOf('=');if(i>0)result.put(item.substring(0,i),item.substring(i+1));
        }
        return result;
    }
    public void write(Map<String,String> patch){
        Camera.Parameters p=camera.getParameters();
        for(Map.Entry<String,String> e:patch.entrySet())p.set(e.getKey(),e.getValue());
        camera.setParameters(p);
    }
}
