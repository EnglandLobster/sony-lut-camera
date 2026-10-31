package org.hongen.sonylutprobe;

/** Single-shot diagnostic barrier. Direct shutter stays OFF throughout colored
 * preview and restoration. Only a subsequent native preview-ready event enables
 * capture. This does not yet certify ISP snapshot/pixel equivalence. */
final class PreviewCaptureBarrier {
    interface Driver {
        void restoreAndVerify()throws Exception;
        void restartPreview()throws Exception;
        void enableCapture()throws Exception;
    }
    enum State { PREVIEW_ONLY, RESTORING, WAITING_PREVIEW, CAPTURE_READY, FAILED }
    private final Driver driver;
    private State state=State.PREVIEW_ONLY;
    PreviewCaptureBarrier(Driver driver){this.driver=driver;}
    State state(){return state;}
    void prepare()throws Exception{
        if(state!=State.PREVIEW_ONLY)return;
        state=State.RESTORING;
        try{driver.restoreAndVerify();state=State.WAITING_PREVIEW;driver.restartPreview();}
        catch(Exception e){state=State.FAILED;throw e;}
    }
    boolean previewReady()throws Exception{
        if(state!=State.WAITING_PREVIEW)return false;
        state=State.RESTORING; // Prevent reentrant/duplicate notifications.
        try{driver.enableCapture();state=State.CAPTURE_READY;return true;}
        catch(Exception e){state=State.FAILED;throw e;}
    }
    void fail(){state=State.FAILED;}
    boolean captureReady(){return state==State.CAPTURE_READY;}
}
