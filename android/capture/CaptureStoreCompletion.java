package io.github.englandlobster.lutcamera;

import java.io.File;
import java.io.IOException;

/** One store transaction, completed once by a named receipt, a new indexed JPEG,
 * or (legacy bodies only) the exact predicted DCF JPEG that was absent before the store.
 * A deadline changes the UI, never transfers an unconfirmed buffer's ownership.
 * Legacy bodies may instead hand a returned store off to the camera unconfirmed
 * ({@link #handOff}); the caller decides when its source buffer is no longer ours. */
final class CaptureStoreCompletion {
    interface Registration {
        void begin()throws Exception;
        PhotoIndex.Entry saved()throws Exception;
        void accepted(File file)throws Exception;
    }
    /** Optional: newest DCF identity {folder,file} known before this store, or null. */
    interface DcfBase {
        int[] dcfBase();
        /** A store was handed off unconfirmed; its file is somewhere after the last confirmed one. */
        void handedOff();
    }
    static final long RECOVERY_DELAY_MS=1000,NOTICE_MS=30000;
    /** Legacy hand-off: how long after storeImage returned the file is looked for
     * before the store is left to the camera, and the limit while a found file is
     * still being written. Owner decision 2026-11-27: a store the app cannot
     * identify must never hold the user; ILCE-7R 3.20 field logs confirm in
     * 1.5-2.0 s after the return when the prediction is right (NAS 志威). */
    static final long HANDOFF_MS=10000,HANDOFF_LIMIT_MS=25000;
    static final class ConfirmedInvalid extends IOException {
        final File file;
        ConfirmedInvalid(File file){super("saved picture-size differs from capture geometry: "+file.getName());this.file=file;}
    }
    static final class Receipt {
        final File file;final boolean recovered,predicted,stopRequested;
        Receipt(File file,boolean recovered,boolean predicted,boolean stopRequested){
            this.file=file;this.recovered=recovered;this.predicted=predicted;this.stopRequested=stopRequested;
        }
    }
    private final PhotoFiles files;private final Registration registration;
    private final boolean namedProtocol;
    private long generation,started;private boolean pending,notified,stopRequested,rpcActive,rpcComplete,confirmed;
    private File confirmedFile;private boolean confirmedRecovered,confirmedPredicted,confirmedValid;
    private boolean recoveryAvailable,continuationAllowed,continuation;
    /** Legacy only: first poll after storeImage returned, or -1. */
    private long returnedAt=-1;
    /** Legacy only: cleared for the session by the first hand-off. A handed-off
     * photo's index record can appear during a later store's wait and would be
     * taken for that store's file; prediction excludes it (it existed before). */
    private boolean indexRecovery=true;
    private int handOffs;
    private volatile String stage="idle";
    private volatile String lastError="";
    private volatile long indexNanos,fileNanos;
    private volatile int indexQueries,fileChecks;
    String stage(){return stage;}
    String diagnostics(){return "confirmation_stage="+stage+" index_polls="+indexQueries+" index_poll_ms="+(indexNanos/1000000)
        +" file_checks="+fileChecks+" file_ms="+(fileNanos/1000000)+" complete_by="+files.completeBy()+" prediction="+files.prediction()+" last_error="+lastError
        +(namedProtocol?"":" hand_offs="+handOffs+" index_recovery="+indexRecovery);}
    void readFailed(Throwable error){
        StringBuilder causes=new StringBuilder();Throwable item=error;
        for(int n=0;item!=null&&n<5;n++){
            if(n>0)causes.append(" <- ");causes.append(item.toString());
            if(item.getCause()==item)break;item=item.getCause();
        }
        String detail=causes.toString().replace('\n',' ').replace('\r',' ');
        lastError=detail.length()>400?detail.substring(0,400):detail;
        stage=stage.contains("index")?"index_error":"file_error";
    }
    CaptureStoreCompletion(PhotoFiles files,Registration registration,boolean namedProtocol){
        this.files=files;this.registration=registration;this.namedProtocol=namedProtocol;
    }
    long begin(long now)throws Exception{return begin(now,false);}
    long beginFinalStore(long now)throws Exception{return begin(now,true);}
    private long begin(long now,boolean finishDeclaredPair)throws Exception{
        final long token;
        synchronized(this){
            if(pending)throw new IllegalStateException("previous store still pending");
            if(finishDeclaredPair&&!continuationAllowed)throw new IllegalStateException("no confirmed original to finish");
            if(stopRequested&&!finishDeclaredPair)throw new IllegalStateException("capture stop requested; no new capture allowed");
            continuation=finishDeclaredPair;continuationAllowed=false;
            token=++generation;pending=true;started=now;notified=false;rpcActive=false;rpcComplete=false;confirmed=false;confirmedFile=null;returnedAt=-1;
        }
        try{
            stage="index_snapshot";lastError="";indexNanos=0;fileNanos=0;indexQueries=0;fileChecks=0;files.resetCandidate();recoveryAvailable=true;
            try{registration.begin();}catch(Exception error){
                if(!namedProtocol)throw error;
                // A query failure before submission cannot disable the native
                // completion protocol. Recovery lacks a safe watermark.
                recoveryAvailable=false;readFailed(error);
            }
            // Without a named receipt, RECORD_COUNT=2 can withhold the original's
            // index record until the second store: confirm its exact new file.
            if(!namedProtocol&&registration instanceof DcfBase){
                int[] base=((DcfBase)registration).dcfBase();
                if(base!=null)files.predictAfter(base[0],base[1]);
            }
            stage="await_store";return token;
        }
        catch(Exception error){cancel();throw error;}
    }
    synchronized void rpcStarted(long token){if(!active(token))throw new IllegalStateException("inactive store");rpcActive=true;stage="native_store";}
    synchronized void rpcReturned(long token){if(token==generation&&rpcActive){rpcActive=false;rpcComplete=true;}}
    synchronized boolean rpcActive(){return rpcActive;}
    synchronized boolean confirmed(){return confirmed;}
    synchronized long token(){return generation;}
    synchronized boolean pending(){return pending;}
    synchronized void requestStop(){stopRequested=true;}
    synchronized boolean active(long token){return pending&&token==generation;}
    synchronized boolean overdue(long token,long now){return active(token)&&now-started>=NOTICE_MS;}
    synchronized void cancel(){if(rpcActive)throw new IllegalStateException("native store still owns its arguments");pending=false;continuationAllowed=false;++generation;}
    /** Legacy bodies only, for a store whose source buffer Sony released
     * (storeImage with releaseSource): once the call has returned, a store that
     * nothing has identified within HANDOFF_MS is left to the camera, which
     * writes it as it does any shot. A candidate file still being written
     * (await_file, or a read refused while it is open) gets up to
     * HANDOFF_LIMIT_MS. Returns true when this call ended the store. Never for
     * the original of a pair, whose buffer is graded next: the caller only
     * hands off single-record stores. */
    synchronized boolean handOff(long token,long now){
        if(namedProtocol||continuation||!active(token)||rpcActive||!rpcComplete||confirmed||returnedAt<0)return false;
        long age=now-returnedAt;
        boolean writing=stage.equals("await_file")||stage.equals("file_error");
        if(age<HANDOFF_MS||(writing&&age<HANDOFF_LIMIT_MS))return false;
        pending=false;continuationAllowed=false;++generation;handOffs++;indexRecovery=false;
        stage="handed_off";
        if(registration instanceof DcfBase)((DcfBase)registration).handedOff();
        return true;
    }
    void notified(long token,String directory,String name)throws Exception{
        if(!active(token)||files.knownStore(directory,name))return;
        files.confirmStored(directory,name);
        synchronized(this){if(active(token))notified=true;}
    }
    Receipt poll(long token,long now)throws Exception{
        boolean hasNotice;long age;
        synchronized(this){
            if(!active(token))return null;if(confirmed)return finishConfirmed();
            // Sony's filesystem refuses reads of an open writer. Do not
            // compete with storeImage for its file while that RPC is active.
            if(!rpcComplete){if(rpcActive)stage="native_store";return null;}
            if(returnedAt<0)returnedAt=now;
            hasNotice=notified;age=now-started;
        }
        File ready=null;long begin;boolean recovered=false,predicted=false;
        if(hasNotice){
            // RECORD_COUNT=2 can publish the database only after BOTH files.
            // Never require original-index visibility before submitting graded.
            stage="notified_file";begin=System.nanoTime();fileChecks++;
            try{ready=files.pollNotified(now);}finally{fileNanos+=System.nanoTime()-begin;}
        }else{
            if(namedProtocol&&(age<RECOVERY_DELAY_MS||!recoveryAvailable)){stage="await_notification";return null;}
            PhotoIndex.Entry entry=null;
            if(namedProtocol||indexRecovery){
                stage="index_query";begin=System.nanoTime();indexQueries++;
                try{entry=registration.saved();}finally{indexNanos+=System.nanoTime()-begin;}
            }
            if(entry!=null){
                stage="target_file";begin=System.nanoTime();fileChecks++;
                try{ready=files.pollIndexed(entry,now);}finally{fileNanos+=System.nanoTime()-begin;}
            }else if(!namedProtocol&&files.predicting()){
                stage="predicted_file";begin=System.nanoTime();fileChecks++;
                try{ready=files.pollPredicted(now);}finally{fileNanos+=System.nanoTime()-begin;}
                if(ready==null){stage=files.predictedFound()?"await_file":"await_index";return null;}
                predicted=true;
            }else{stage="await_index";return null;}
            recovered=true;
        }
        if(ready==null){stage="await_file";return null;}
        begin=System.nanoTime();
        boolean valid;
        try{valid=files.expectedHeader(ready);}finally{fileNanos+=System.nanoTime()-begin;}
        synchronized(this){
            if(!active(token)||ready==null)return null;
            try{registration.accepted(ready);}catch(Exception error){recoveryAvailable=false;readFailed(error);}
            confirmed=true;confirmedFile=ready;confirmedRecovered=recovered;confirmedPredicted=predicted;confirmedValid=valid;
            stage="confirmed";
            return finishConfirmed();
        }
    }
    private Receipt finishConfirmed()throws ConfirmedInvalid{
        // Keep the ownership guard at the final transition as well.
        if(rpcActive)return null;
        pending=false;
        if(!confirmedValid)throw new ConfirmedInvalid(confirmedFile);
        continuationAllowed=!continuation;
        return new Receipt(confirmedFile,confirmedRecovered,confirmedPredicted,stopRequested);
    }
}
