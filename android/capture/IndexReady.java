package org.hongen.sonylutprobe;

import java.io.IOException;

/** Wait only for an empty, asynchronously updated Sony index, never for bad data. */
final class IndexReady {
    interface Lookup {PhotoIndex.Entry read() throws IOException;}
    private long generation;int attempts;long waitMs;
    synchronized void changed(){generation++;notifyAll();}
    private static long now(){return System.nanoTime()/1000000L;}
    PhotoIndex.Entry await(Lookup lookup,long timeoutMs) throws IOException {
        long deadline=now()+timeoutMs;attempts=0;waitMs=0;
        for(;;) {
            long observed;synchronized(this){observed=generation;}
            try{attempts++;return lookup.read();}
            catch(PhotoIndex.NotReady empty) {
                long remaining=deadline-now();if(remaining<=0)throw new IOException("Sony index update timed out: "+empty.getMessage(),empty);
                long start=now();
                synchronized(this) {
                    // The event can arrive during the query. The generation
                    // check prevents losing that wakeup before wait().
                    if(generation==observed)try{wait(Math.min(remaining,100));}
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Sony index wait interrupted",interrupted);}
                }
                waitMs+=now()-start;
            }
        }
    }
}
