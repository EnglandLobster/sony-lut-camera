package org.hongen.sonylutprobe;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Own only capture-format settings, restoring even a partially rejected write. */
final class CaptureSetup {
    private final SimpleExposure.Driver driver;
    private final CaptureGeometry geometry;
    private Map<String,String> original;
    CaptureSetup(SimpleExposure.Driver driver){this(driver,new CaptureGeometry(6000,4000));}
    CaptureSetup(SimpleExposure.Driver driver,CaptureGeometry geometry){this.driver=driver;this.geometry=geometry;}
    void open()throws Exception {
        if(original!=null)throw new IllegalStateException("capture settings already owned");
        Map<String,String> current=driver.read(),target=new LinkedHashMap<String,String>();
        String hdr=current.get("hdr-mode"),effect=current.get("picture-effect");
        if((hdr!=null&&!"off".equals(hdr))||"hdr-art".equals(effect))
            throw new IOException("capture HDR enabled: hdr-mode="+hdr+" picture-effect="+effect);
        target.put("drive-mode","single");target.put("storage-fmt","jpeg");target.put("picture-size",geometry.toString());
        if(current.get("jpeg-quality")!=null)target.put("jpeg-quality",current.get("jpeg-quality"));
        Map<String,String> saved=new LinkedHashMap<String,String>();
        for(Map.Entry<String,String> entry:target.entrySet()){
            String key=entry.getKey(),old=current.get(key);
            if(old==null)throw new IOException("missing capture parameter: "+key);
            // Sony does not consistently advertise storage-fmt-values. JPEG is
            // verified by readback; standard size/drive lists must advertise changes.
            if(!entry.getValue().equals(old)&&!key.equals("storage-fmt")){
                String values=current.get(key+"-values");
                if(values==null||!(","+values.replace(" ","")+",").contains(","+entry.getValue()+","))
                    throw new IOException("unsupported capture setting: "+key+"="+entry.getValue());
            }
            saved.put(key,old);
        }
        if(saved.equals(target))return;
        original=saved;
        try{driver.write(target);verify(driver.read(),target,"capture setting rejected: ");}
        catch(Exception error){throw new IOException("capture settings single/JPEG/"+geometry+" failed",error);}
    }
    void close()throws Exception {
        if(original==null)return;
        driver.write(original);verify(driver.read(),original,"capture restore differs: ");original=null;
    }
    private static void verify(Map<String,String> actual,Map<String,String> expected,String message)throws IOException {
        for(Map.Entry<String,String> entry:expected.entrySet())if(!entry.getValue().equals(actual.get(entry.getKey())))
            throw new IOException(message+entry.getKey());
    }
}
