package org.hongen.sonylutprobe;

/** All transitions belong to one UI thread. Failed restoration blocks handoff.
 * Baseline readback is a parameter check, not proof of capture-snapshot isolation.
 * AutoPreviewGate owns production shutter handoff; PreviewCaptureBarrier
 * keeps the explicit manual isolation probe separate. */
final class NativePreviewSession {
    interface Driver {
        void snapshot()throws Exception;
        void applyMatrix(int[] matrix)throws Exception;
        void applyGamma(byte[] gamma)throws Exception;
        void verifyApplied(int[] matrix,byte[] gamma)throws Exception;
        void restoreGamma()throws Exception;
        void restoreMatrix()throws Exception;
        void verifyBaseline()throws Exception;
        void releaseOwned()throws Exception;
    }
    enum State { NEW, BASELINE, PREVIEW, DIRTY, CLOSED }
    private final Driver driver;
    private State state=State.NEW;
    NativePreviewSession(Driver driver){this.driver=driver;}
    State state(){return state;}
    void open()throws Exception{
        if(state!=State.NEW)throw new IllegalStateException("preview session already opened");
        driver.snapshot();driver.verifyBaseline();state=State.BASELINE;
    }
    void apply(NativePreviewProfile p)throws Exception{
        if(state!=State.BASELINE&&state!=State.PREVIEW)throw new IllegalStateException("preview unavailable: "+state);
        if(state==State.PREVIEW)restore();
        requireBaseline();
        state=State.DIRTY; // A throwing vendor setter may already have changed hardware.
        try{
            driver.applyMatrix(p.matrix);driver.applyGamma(p.gamma);driver.verifyApplied(p.matrix,p.gamma);
            state=State.PREVIEW;
        }catch(Exception e){
            try{restore();}catch(Exception rollback){throw new Exception("preview write and rollback failed",rollback);}
            throw e;
        }
    }
    void restore()throws Exception{
        if(state==State.NEW||state==State.CLOSED)return;
        if(state==State.BASELINE){requireBaseline();return;}
        state=State.DIRTY;
        Exception error=null;
        // Try both even if one fails; never silently call a partial rollback success.
        try{driver.restoreGamma();}catch(Exception e){error=e;}
        try{driver.restoreMatrix();}catch(Exception e){if(error==null)error=e;}
        try{driver.verifyBaseline();}catch(Exception e){if(error==null)error=e;}
        if(error!=null)throw error;
        driver.releaseOwned();state=State.BASELINE;
    }
    void requireBaseline()throws Exception{
        if(state!=State.BASELINE)throw new IllegalStateException("color baseline not restored: "+state);
        try{driver.verifyBaseline();}catch(Exception e){state=State.DIRTY;throw e;}
    }
    void close()throws Exception{
        if(state==State.CLOSED)return;
        restore();driver.releaseOwned();state=State.CLOSED;
    }
}
