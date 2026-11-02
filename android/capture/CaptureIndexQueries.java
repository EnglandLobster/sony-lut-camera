package org.hongen.sonylutprobe;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** One read-only index worker; slow Binder queries never occupy receipt delivery. */
final class CaptureIndexQueries implements CaptureStoreCompletion.Registration,Closeable {
    private final PhotoIndex index;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(new ThreadFactory(){
        public Thread newThread(Runnable task){Thread t=new Thread(task,"sony-index-query");t.setDaemon(true);return t;}
    });
    private final long snapshotWaitMs;
    private final LinkedHashSet<String> accepted=new LinkedHashSet<String>();
    private volatile Future<?> outstanding;
    private Future<PhotoIndex.Entry> reading;
    private long before=Long.MIN_VALUE;
    private volatile long queryStarted;
    private volatile String queryKind="none";
    private volatile int submittedQueries;
    CaptureIndexQueries(PhotoIndex index){this(index,1000);}
    CaptureIndexQueries(PhotoIndex index,long waitMs){this.index=index;this.snapshotWaitMs=waitMs;}
    public synchronized void begin()throws Exception{
        reading=null;
        if(outstanding!=null&&!outstanding.isDone()){
            if(before!=Long.MIN_VALUE)return; // Earlier watermark + confirmed DCF exclusions remain safe.
            throw new IOException("previous Sony index query still running");
        }
        queryStarted=System.nanoTime();queryKind="snapshot";
        Future<Long> snapshot=worker.submit(new Callable<Long>(){public Long call()throws Exception{return index.latestOrder();}});
        submittedQueries++;
        outstanding=snapshot;
        try{before=snapshot.get(snapshotWaitMs,TimeUnit.MILLISECONDS);}
        catch(TimeoutException e){if(before==Long.MIN_VALUE)throw new IOException("Sony index snapshot timed out; waiting for save notification",e);}
        catch(ExecutionException e){if(before==Long.MIN_VALUE)throw failure(e);}
    }
    public synchronized PhotoIndex.Entry saved()throws Exception{
        if(before==Long.MIN_VALUE)throw new IOException("no pre-store index watermark; waiting for save notification");
        if(reading==null){
            if(outstanding!=null&&!outstanding.isDone())return null;
            final long watermark=before;final Set<String> known=new HashSet<String>(accepted);
            queryStarted=System.nanoTime();queryKind="recovery";
            reading=worker.submit(new Callable<PhotoIndex.Entry>(){public PhotoIndex.Entry call()throws Exception{
                try{return index.savedAfter(watermark,"",known);}catch(PhotoIndex.NotReady pending){return null;}
            }});submittedQueries++;outstanding=reading;return null;
        }
        if(!reading.isDone())return null;
        Future<PhotoIndex.Entry> done=reading;reading=null;
        try{return done.get();}catch(ExecutionException e){throw failure(e);}
    }
    public synchronized void accepted(File file)throws IOException{
        accepted.add(index.key(file));
        while(accepted.size()>64){Iterator<String> first=accepted.iterator();first.next();first.remove();}
    }
    public synchronized void close(){worker.shutdown();}
    String diagnostics(){Future<?> current=outstanding;boolean busy=current!=null&&!current.isDone();
        return "index_worker="+queryKind+" index_worker_active="+busy+" index_queries_submitted="+submittedQueries
            +" index_worker_ms="+(busy?(System.nanoTime()-queryStarted)/1000000:0);}
    private static IOException failure(ExecutionException error){
        Throwable cause=error.getCause();return cause instanceof IOException?(IOException)cause:new IOException("Sony index query failed",cause);
    }
}
