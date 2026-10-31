package org.hongen.sonylutprobe;

import java.io.File;
import java.io.IOException;

/** Require a newly registered Sony index entry and a unique, stable, complete
 * JPEG. No full-resolution byte[] callback enters the old camera's Java heap. */
final class LegacyStoreCompletion {
    interface Registration {
        void begin()throws Exception;
        boolean registered(File file)throws Exception;
    }
    static final long TIMEOUT_MS=30000;
    private final PhotoFiles files;
    private final Registration registration;
    private long generation,deadline;
    private boolean active;
    LegacyStoreCompletion(PhotoFiles files,Registration registration){this.files=files;this.registration=registration;}
    long begin(long now)throws Exception{
        final long token;
        synchronized(this){
            if(active)throw new IllegalStateException("previous legacy store still pending");
            active=true;deadline=now+TIMEOUT_MS;token=++generation;
        }
        try{files.resetCandidate();registration.begin();return token;}
        catch(Exception error){cancel();throw error;}
    }
    synchronized boolean active(long token){return active&&token==generation;}
    synchronized void cancel(){active=false;++generation;}
    File poll(long token,long now)throws Exception{
        synchronized(this){
            if(!active(token))return null;
            if(now>=deadline)throw new IOException("legacy indexed JPEG save confirmation timed out");
        }
        // Directory and complete-JPEG checks run on the capture worker, never
        // while holding the monitor needed by UI-thread cancellation.
        File ready=files.pollReady();
        if(ready!=null&&!registration.registered(ready))ready=null;
        synchronized(this){
            if(!active(token))return null;
            if(ready!=null)active=false;
            return ready;
        }
    }
}
