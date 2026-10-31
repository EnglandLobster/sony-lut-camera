package org.hongen.sonylutprobe;

/** UI-thread ownership of approximate preview versus native physical capture.
 * Never synthesizes a shutter press or queues a photograph. */
final class AutoPreviewGate {
    interface Driver {
        void lock() throws Exception;
        void stopDirect(Runnable stopped) throws Exception;
        void apply() throws Exception;
        void restore() throws Exception;
        void restartPreview() throws Exception;
        void enable() throws Exception;
    }
    interface Listener {
        void colored();
        void ready();
        void failed(Exception error);
    }
    enum State { NEW, PREVIEW, RESTORING, WAITING, READY, CAPTURING, STOPPING, FAILED, CLOSED }
    private final Driver driver;
    private final Listener listener;
    private State state=State.NEW;
    private boolean held;
    private long generation;
    AutoPreviewGate(Driver driver,Listener listener){this.driver=driver;this.listener=listener;}
    State state(){return state;}
    long generation(){return generation;}
    boolean ready(){return state==State.READY;}
    void start(){if(state!=State.NEW)return;try{driver.lock();colored();}catch(Exception e){fail(e);}}
    private void colored() throws Exception {
        driver.apply();state=State.PREVIEW;generation++;listener.colored();
        if(held)prepare();
    }
    void press(){held=true;if(state==State.PREVIEW)prepare();}
    void release(){held=false;if(state==State.READY)returnToPreview();}
    private void prepare(){
        if(state!=State.PREVIEW)return;
        state=State.RESTORING;generation++;
        try{driver.restore();state=State.WAITING;driver.restartPreview();}
        catch(Exception e){fail(e);}
    }
    void previewReady(){
        if(state!=State.WAITING)return;
        try{
            // A released half-press must not enable a late capture path.
            if(!held){colored();return;}
            state=State.READY;generation++;driver.enable();listener.ready();
        }catch(Exception e){fail(e);}
    }
    boolean shotStarted(){if(state!=State.READY)return false;state=State.CAPTURING;held=false;generation++;return true;}
    void shotFinished(){if(state==State.CAPTURING)returnToPreview();}
    void shotCanceled(){if(state==State.CAPTURING)returnToPreview();}
    private void returnToPreview(){
        state=State.STOPPING;final long token=++generation;
        try{
            driver.lock();
            driver.stopDirect(new Runnable(){public void run(){
                if(state!=State.STOPPING||generation!=token)return;
                // Reject duplicate stop acknowledgements before applying color.
                state=State.RESTORING;
                try{colored();}catch(Exception e){fail(e);}
            }});
        }catch(Exception e){fail(e);}
    }
    void timeout(long token){
        if(generation==token&&(state==State.WAITING||state==State.STOPPING))
            fail(new Exception("native preview/shutter transition timed out: "+state));
    }
    void fail(Exception error){
        if(state==State.CLOSED||state==State.FAILED)return;
        state=State.FAILED;generation++;held=false;
        try{driver.lock();}catch(Exception lockError){error=new Exception("preview failure; release lock failed",lockError);}
        listener.failed(error);
    }
    void close(){state=State.CLOSED;generation++;held=false;}
}
