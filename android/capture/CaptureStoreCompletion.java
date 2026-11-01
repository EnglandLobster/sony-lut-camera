package org.hongen.sonylutprobe;

import java.io.File;
import java.io.IOException;

/** One store transaction, completed once by a named receipt or new indexed JPEG.
 * A deadline changes the UI, never transfers an unconfirmed buffer's ownership. */
final class CaptureStoreCompletion {
    interface Registration {
        void begin()throws Exception;
        boolean registered(File file)throws Exception;
    }
    static final long RECOVERY_DELAY_MS=1000,NOTICE_MS=30000;
    static final class ConfirmedInvalid extends IOException {
        final File file;
        ConfirmedInvalid(File file){super("saved picture-size differs from capture geometry: "+file.getName());this.file=file;}
    }
    static final class Receipt {
        final File file;final boolean recovered,stopRequested;
        Receipt(File file,boolean recovered,boolean stopRequested){this.file=file;this.recovered=recovered;this.stopRequested=stopRequested;}
    }
    private final PhotoFiles files;private final Registration registration;private final boolean namedProtocol;
    private long generation,started;private boolean pending,notified,stopRequested,rpcActive,confirmed;
    private File confirmedFile;private boolean confirmedRecovered,confirmedValid;
    CaptureStoreCompletion(PhotoFiles files,Registration registration,boolean namedProtocol){
        this.files=files;this.registration=registration;this.namedProtocol=namedProtocol;
    }
    long begin(long now)throws Exception{
        final long token;
        synchronized(this){
            if(pending)throw new IllegalStateException("previous store still pending");
            if(stopRequested)throw new IllegalStateException("capture stop requested; no further store allowed");
            token=++generation;pending=true;started=now;notified=false;rpcActive=false;confirmed=false;confirmedFile=null;
        }
        try{files.resetCandidate();registration.begin();return token;}
        catch(Exception error){cancel();throw error;}
    }
    synchronized void rpcStarted(long token){if(!active(token))throw new IllegalStateException("inactive store");rpcActive=true;}
    synchronized void rpcReturned(long token){if(token==generation)rpcActive=false;}
    synchronized boolean rpcActive(){return rpcActive;}
    synchronized boolean confirmed(){return confirmed;}
    synchronized long token(){return generation;}
    synchronized boolean pending(){return pending;}
    synchronized void requestStop(){stopRequested=true;}
    synchronized boolean active(long token){return pending&&token==generation;}
    synchronized boolean overdue(long token,long now){return active(token)&&now-started>=NOTICE_MS;}
    synchronized void cancel(){if(rpcActive)throw new IllegalStateException("native store still owns its arguments");pending=false;++generation;}
    void notified(long token,String directory,String name)throws Exception{
        if(!active(token)||files.knownStore(directory,name))return;
        files.confirmStored(directory,name);
        synchronized(this){if(active(token))notified=true;}
    }
    Receipt poll(long token,long now)throws Exception{
        boolean hasNotice;long age;
        synchronized(this){if(!active(token))return null;if(confirmed)return finishConfirmed();hasNotice=notified;age=now-started;}
        File ready=null;boolean recovered=false;
        if(hasNotice){
            ready=files.pollReady(true);
            // The original must enter the index before the graded store takes
            // its watermark. Otherwise a late original entry appears as a
            // second "new" record during recovery of the graded photograph.
            if(ready!=null&&(!files.completeStructure(ready)||!registration.registered(ready)))ready=null;
        }
        if(ready==null&&(!namedProtocol||age>=RECOVERY_DELAY_MS)){
            // Even a partial named receipt must not bypass full structure and
            // new-index confirmation on the recovery path.
            ready=files.pollRecovery();
            if(ready!=null&&!registration.registered(ready))ready=null;
            recovered=ready!=null;
        }
        boolean valid=ready==null||files.expectedHeader(ready);
        synchronized(this){
            if(!active(token)||ready==null)return null;
            confirmed=true;confirmedFile=ready;confirmedRecovered=recovered;confirmedValid=valid;
            return finishConfirmed();
        }
    }
    private Receipt finishConfirmed()throws ConfirmedInvalid{
        // Retain disk evidence once: do not scan a 42MP JPEG every second while
        // waiting for the native call. This method runs under this monitor.
        if(rpcActive)return null;
        pending=false;
        if(!confirmedValid)throw new ConfirmedInvalid(confirmedFile);
        return new Receipt(confirmedFile,confirmedRecovered,stopRequested);
    }
}
