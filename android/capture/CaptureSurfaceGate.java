package org.hongen.sonylutprobe;

/** Sony's push-buffer Surface may report isValid=false after its lifecycle
 * callbacks. Those callbacks, not the Android buffer flag, authorize opening. */
final class CaptureSurfaceGate {
    private boolean available,claimed;
    void created(){available=true;}
    void changed(){available=true;}
    void destroyed(){available=false;}
    boolean started(){return claimed;}
    boolean waiting(boolean active,boolean prepared,boolean closed){
        return active&&prepared&&!closed&&!claimed&&!available;
    }
    boolean claim(boolean active,boolean prepared,boolean closed,boolean hasHolder){
        if(!active||!prepared||closed||!hasHolder||!available||claimed)return false;
        claimed=true;return true;
    }
}
