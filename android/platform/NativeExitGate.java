package org.hongen.sonylutprobe;

/** One completion may request native exit; stale callbacks after timeout cannot. */
final class NativeExitGate {
    private long generation;
    private boolean pending;
    long begin(){if(pending)throw new IllegalStateException("exit already pending");pending=true;return ++generation;}
    boolean current(long token){return pending&&token==generation;}
    boolean accept(long token){if(!pending||token!=generation)return false;pending=false;return true;}
    void cancel(){pending=false;++generation;}
}
