package org.hongen.sonylutprobe;

/** Install diagnostics before any Activity, including early initialization failures. */
public final class LutApplication extends android.app.Application {
    @Override public void onCreate(){super.onCreate();
        try{SupportDiagnostics.initialize(this);}catch(Throwable error){android.util.Log.e("LutSupport","recorder initialization failed",error);}
    }
}
