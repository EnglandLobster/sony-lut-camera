package io.github.englandlobster.lutcamera;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Picks the one Sony index record a delete may act on. The index can list a
 * fresh photo twice for a moment, or not yet: ILCE-7M2 4.01 refused a delete
 * with two records for one DCF number while the camera was open, and the next
 * attempt found one (NAS 298007, beta.3, twice). Ask again for a few seconds
 * before refusing; never pick between two records. */
final class PhotoDeleteTarget {
    interface Lookup { List<Long> ids(int folder,int file)throws IOException; }
    static final int ATTEMPTS=7;static final long WAIT_MS=500;
    private PhotoDeleteTarget(){}
    /** The record id, or -1 when the photo is already gone from both index and card. */
    static long settle(Lookup lookup,int[] dcf,File jpeg,boolean rawPresent,int attempts,long waitMs)throws Exception{
        List<Long> ids;
        for(int attempt=1;;attempt++){
            ids=lookup.ids(dcf[0],dcf[1]);
            if(ids.size()==1)return ids.get(0);
            if(ids.isEmpty()&&!jpeg.exists()&&!rawPresent)return -1;
            if(attempt>=attempts)break;
            Thread.sleep(waitMs);
        }
        if(ids.size()>1)throw new IOException("相机照片索引里 "+jpeg.getName()+" 有 "+ids.size()+" 条记录，请稍后再删");
        throw new IOException("相机照片索引里找不到 "+jpeg.getName()+"，请稍后再删");
    }
}
