package org.hongen.sonylutprobe;

import java.util.Map;

/** Explain only settings actually changed and read back for this session. */
final class CaptureSettingsNotice {
    static String between(Map<String,String> before,Map<String,String> after){
        StringBuilder changes=new StringBuilder();
        if(changed(before,after,"storage-fmt")&&"jpeg".equals(after.get("storage-fmt")))
            add(changes,("rawjpeg".equals(before.get("storage-fmt"))?"RAW+JPEG":"RAW")+" → JPEG");
        if(changed(before,after,"drive-mode")&&"single".equals(after.get("drive-mode")))
            add(changes,("burst".equals(before.get("drive-mode"))?"连拍":"包围曝光")+" → 单张");
        if(changed(before,after,"picture-size"))add(changes,"尺寸 → 24M（3:2）");
        if(changed(before,after,"scene-mode")&&"program-auto".equals(after.get("scene-mode")))add(changes,"曝光 → P 自动");
        if(changed(before,after,"jpeg-quality")){
            String value=after.get("jpeg-quality");
            if("95".equals(value))add(changes,"画质 → EXTRA FINE");
            else if("50".equals(value))add(changes,"画质 → FINE");
            else if("25".equals(value))add(changes,"画质 → STANDARD");
        }
        return changes.length()==0?"":"已自动调整："+changes;
    }
    private static boolean changed(Map<String,String> before,Map<String,String> after,String key){
        return before.get(key)!=null&&after.get(key)!=null&&!before.get(key).equals(after.get(key));
    }
    private static void add(StringBuilder text,String value){if(text.length()>0)text.append(" · ");text.append(value);}
}
