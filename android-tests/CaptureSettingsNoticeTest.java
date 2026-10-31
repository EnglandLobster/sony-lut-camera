package org.hongen.sonylutprobe;
import java.util.*;
public final class CaptureSettingsNoticeTest {
    public static void main(String[] args){
        Map<String,String> before=new HashMap<String,String>(),after=new HashMap<String,String>();
        before.put("storage-fmt","rawjpeg");before.put("drive-mode","burst");before.put("jpeg-quality","25");
        after.put("storage-fmt","jpeg");after.put("drive-mode","single");after.put("jpeg-quality","50");
        String message=CaptureSettingsNotice.between(before,after);
        if(!message.contains("RAW+JPEG → JPEG")||!message.contains("连拍 → 单张")||!message.contains("画质 → FINE")||message.contains("EXTRA FINE"))throw new AssertionError(message);
        if(!CaptureSettingsNotice.between(after,after).equals(""))throw new AssertionError("unchanged settings must not prompt");
        after.put("storage-fmt","rawjpeg");
        if(CaptureSettingsNotice.between(before,after).contains("RAW"))throw new AssertionError("failed switch must not claim success");
        if(!CaptureSettingsNotice.between(Collections.<String,String>emptyMap(),after).equals(""))throw new AssertionError("missing originals must not claim changes");
        System.out.println("CaptureSettingsNoticeTest passed");
    }
}
