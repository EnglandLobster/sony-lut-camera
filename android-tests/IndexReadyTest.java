package org.hongen.sonylutprobe;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class IndexReadyTest {
    private static PhotoIndex.Entry entry() throws IOException {
        return new PhotoIndex.Entry("avindex://1000/00000001-default/0000002C-0000095C",44,100,44);
    }
    private static void join(Thread worker,AtomicReference<Throwable> error) throws Exception {
        worker.join(1500);
        if(worker.isAlive()){worker.interrupt();worker.join(1000);throw new AssertionError("index waiter stuck");}
        if(error.get()!=null)throw new AssertionError("index waiter failed",error.get());
    }
    public static void main(String[] args) throws Exception {
        final PhotoIndex.Entry photo=entry();
        final IndexReady ready=new IndexReady();
        if(ready.await(new IndexReady.Lookup(){public PhotoIndex.Entry read(){return photo;}},1000)!=photo
                ||ready.attempts!=1||ready.waitMs!=0)throw new AssertionError("ready index was delayed");

        // Reproduce the observed camera race: the query returns an old empty
        // cursor, although its invalidation arrives before the query completes.
        final IndexReady duringQuery=new IndexReady();
        duringQuery.await(new IndexReady.Lookup(){int calls;public PhotoIndex.Entry read() throws IOException {
            if(calls++==0){duringQuery.changed();throw new PhotoIndex.NotReady("stale empty cursor");}
            return photo;
        }},1000);
        if(duringQuery.attempts!=2||duringQuery.waitMs>=90)throw new AssertionError("lost update during query");

        final IndexReady delayed=new IndexReady();
        final CountDownLatch queried=new CountDownLatch(1);
        final AtomicBoolean visible=new AtomicBoolean();
        final AtomicReference<Throwable> error=new AtomicReference<Throwable>();
        Thread worker=new Thread(new Runnable(){public void run(){try{
            PhotoIndex.Entry result=delayed.await(new IndexReady.Lookup(){public PhotoIndex.Entry read() throws IOException {
                queried.countDown();if(!visible.get())throw new PhotoIndex.NotReady("not published");return photo;
            }},1000);
            if(result!=photo)throw new AssertionError("wrong result");
        }catch(Throwable failure){error.set(failure);}}});
        worker.start();if(!queried.await(1,TimeUnit.SECONDS))throw new AssertionError("query not started");
        visible.set(true);delayed.changed();join(worker,error);

        // The firmware can publish the index without delivering a broadcast.
        final IndexReady missedEvent=new IndexReady();
        missedEvent.await(new IndexReady.Lookup(){int calls;public PhotoIndex.Entry read() throws IOException {
            if(calls++<2)throw new PhotoIndex.NotReady("no event");return photo;
        }},1000);
        if(missedEvent.attempts!=3)throw new AssertionError("missed-event fallback did not retry");

        final IndexReady absent=new IndexReady();
        try {
            absent.await(new IndexReady.Lookup(){public PhotoIndex.Entry read() throws IOException {
                throw new PhotoIndex.NotReady("still absent");
            }},150);
            throw new AssertionError("missing index did not time out");
        } catch(IOException expected) {
            if(!(expected.getCause() instanceof PhotoIndex.NotReady)||!expected.getMessage().contains("timed out"))throw expected;
        }

        final IndexReady invalid=new IndexReady();final IOException permanent=new IOException("ambiguous photo");
        try {
            invalid.await(new IndexReady.Lookup(){public PhotoIndex.Entry read() throws IOException {throw permanent;}},1000);
            throw new AssertionError("invalid index accepted");
        } catch(IOException expected) {
            if(expected!=permanent||invalid.attempts!=1||invalid.waitMs!=0)throw new AssertionError("permanent error retried");
        }

        final CountDownLatch blocked=new CountDownLatch(1);error.set(null);
        worker=new Thread(new Runnable(){public void run(){try{
            new IndexReady().await(new IndexReady.Lookup(){public PhotoIndex.Entry read() throws IOException {
                blocked.countDown();throw new PhotoIndex.NotReady("interrupted");
            }},3000);
            error.set(new AssertionError("interruption ignored"));
        } catch(IOException expected) {
            if(!(expected.getCause() instanceof InterruptedException)||!Thread.currentThread().isInterrupted())error.set(expected);
        }catch(Throwable failure){error.set(failure);}}});
        worker.start();if(!blocked.await(1,TimeUnit.SECONDS))throw new AssertionError("interrupt query not started");
        worker.interrupt();join(worker,error);
        System.out.println("PASS ready/no-delay, update during query, asynchronous publication, missed event, timeout, permanent error, interruption");
    }
}
