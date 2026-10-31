package org.hongen.sonylutprobe;

/** Runs only on the library/import IO queue, before camera ownership is acquired. */
final class PreviewFitting {
    static NativePreviewProfile ensure(final LutLibrary.Entry entry)throws Exception{
        LutBinaryCache.verifySource(entry);
        long begin=android.os.SystemClock.elapsedRealtime();
        NativePreviewProfile profile=PreviewProfileStore.ensure(entry,new PreviewProfileStore.Fitter(){
            public String fit(LutLibrary.Entry e){return Native.fitPreview(e.file.getPath(),LutBinaryCache.binary(e.file).getPath(),e.id);}
        });
        android.util.Log.i("SonyPreviewFit","ready sha="+entry.id+" elapsed_ms="+(android.os.SystemClock.elapsedRealtime()-begin)
            +" mae="+profile.mae+" p95="+profile.p95+" warning="+profile.largeError());return profile;
    }
}
