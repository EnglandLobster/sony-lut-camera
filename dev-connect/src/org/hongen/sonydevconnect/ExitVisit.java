package org.hongen.sonydevconnect;

/** Main-thread lifecycle state. Sony exit can retain a singleTask Activity. */
final class ExitVisit {
    enum Completion { STALE, RETURN_TO_CAMERA, RECONNECT }
    private long generation;
    private boolean exiting, cleaning, left, reopen;

    boolean exiting(){return exiting;}
    long begin(){
        if(exiting)return -1;
        exiting=true;cleaning=true;left=false;reopen=false;
        return ++generation;
    }
    void leftForeground(){if(exiting)left=true;}
    boolean enter(boolean explicitLaunch){
        if(!exiting||(!explicitLaunch&&!left))return false;
        if(cleaning){reopen=true;return false;}
        reset();return true;
    }
    Completion cleaned(long token){
        if(!current(token)||!cleaning)return Completion.STALE;
        cleaning=false;
        if(reopen){reset();return Completion.RECONNECT;}
        return Completion.RETURN_TO_CAMERA;
    }
    boolean current(long token){return exiting&&token==generation;}
    boolean fail(long token){if(!current(token))return false;reset();return true;}
    boolean waitingForNative(long token){return current(token)&&!cleaning&&!left;}
    void destroy(){reset();}
    private void reset(){++generation;exiting=false;cleaning=false;left=false;reopen=false;}
}
