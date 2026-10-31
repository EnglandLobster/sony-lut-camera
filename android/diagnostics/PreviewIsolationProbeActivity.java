package org.hongen.sonylutprobe;
import android.os.Bundle;
import java.util.UUID;

/** Explicit fresh-process probe, not a product entry. Physical shutter only. */
public final class PreviewIsolationProbeActivity extends PreJpegProbeActivity {
    @Override protected boolean productUi(){return true;}
    @Override protected boolean previewIsolationProbe(){return true;}
    @Override public void onCreate(Bundle state){
        getIntent().putExtra("phase","lut").putExtra("init_only",false).putExtra("init_stage","configured")
            .putExtra("map_revision","reference01").putExtra("fresh_session",true).putExtra("normalize_zoom",true)
            .putExtra("continuous",false).putExtra("verify_payloads",true).putExtra("overlap_original",true)
            .putExtra("source_reference",true).putExtra("alternate_overlap",false)
            .putExtra("lut_id","a3ea0238d24b77daa43745137696aa81dffe3f1d5b44e9cb87f4e1c5d85e7f6a")
            .putExtra("probe_token",UUID.randomUUID().toString());
        super.onCreate(state);
    }
}
