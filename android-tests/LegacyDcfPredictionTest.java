package io.github.englandlobster.lutcamera;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Legacy bodies have no named store receipt. With RECORD_COUNT=2 Sony can withhold
 * the original's index record until the graded store, so the original must be confirmed
 * by its exact predicted DCF file: absent before the store, complete after RPC return. */
public final class LegacyDcfPredictionTest {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    /** Sony image index: only published records are visible to queries. */
    private static final class Database implements PhotoIndex.Query {
        private final List<PhotoIndex.Entry> rows=new ArrayList<PhotoIndex.Entry>();
        public synchronized List<PhotoIndex.Entry> read(String selection,String order,int limit)throws IOException{
            long after=Long.MIN_VALUE;
            if(selection!=null){
                if(!selection.startsWith("rec_order>"))throw new IOException("unexpected selection: "+selection);
                after=Long.parseLong(selection.substring("rec_order>".length()));
            }
            if(!"rec_order DESC".equals(order))throw new IOException("unexpected order: "+order);
            List<PhotoIndex.Entry> found=new ArrayList<PhotoIndex.Entry>();
            for(PhotoIndex.Entry row:rows)if(row.order>after)found.add(row);
            Collections.sort(found,new Comparator<PhotoIndex.Entry>(){public int compare(PhotoIndex.Entry a,PhotoIndex.Entry b){return a.order<b.order?1:a.order>b.order?-1:0;}});
            return new ArrayList<PhotoIndex.Entry>(found.subList(0,Math.min(limit,found.size())));
        }
        synchronized void publish(long order,int folder,int file)throws IOException{
            rows.add(new PhotoIndex.Entry(String.format(Locale.US,"avindex://1000/00000001-default/%08x-%08x",folder,file),order,folder,file));
        }
    }
    private static final class Session {
        final File root;final Database db=new Database();final CaptureIndexQueries index;final PhotoFiles files;final CaptureStoreCompletion completion;
        Session(File root,boolean named)throws Exception{
            this.root=root;check(root.mkdirs(),"scratch session");
            index=new CaptureIndexQueries(new PhotoIndex(db,new File(root,"DCIM").getCanonicalFile()),1000);
            files=new PhotoFiles(root,PhotoFiles.Format.JPEG,false);files.expect(new CaptureGeometry(6000,4000));
            completion=new CaptureStoreCompletion(files,index,named);
        }
        File photo(String folder,String name,int width,int height,boolean complete)throws Exception{
            File dir=new File(root,"DCIM/"+folder);if(!dir.isDirectory())check(dir.mkdirs(),"DCF folder "+folder);
            File file=new File(dir,name);CapturePipelineTest.write(file,CapturePipelineTest.jpeg(width,height,complete));return file.getCanonicalFile();
        }
        File photo(String folder,String name)throws Exception{return photo(folder,name,6000,4000,true);}
        /** Sony store: RPC runs, the JPEG appears, then the RPC returns. Files may not be read meanwhile. */
        long store(long token,long at,Runnable sonyWrites)throws Exception{
            completion.rpcStarted(token);sonyWrites.run();
            check(completion.poll(token,at)==null,"no file read while the native store owns its arguments");
            completion.rpcReturned(token);return token;
        }
        CaptureStoreCompletion.Receipt await(long token,long at)throws Exception{
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<deadline){
                CaptureStoreCompletion.Receipt receipt=completion.poll(token,at);if(receipt!=null)return receipt;
                at+=200;Thread.sleep(2);
            }
            throw new AssertionError("legacy store stalled: "+completion.diagnostics());
        }
        void stays(long token,long at)throws Exception{
            for(int n=0;n<40;n++){check(completion.poll(token,at)==null,"unexpected confirmation: "+completion.diagnostics());at+=200;Thread.sleep(1);}
            check(completion.pending(),"store remains pending");
        }
        void close(){index.close();}
    }
    private interface Write {void run()throws Exception;}
    private static Runnable writes(final Write write){
        return new Runnable(){public void run(){try{write.run();}catch(Exception error){throw new RuntimeException(error);}}};
    }
    /** The A6000 report: index never publishes during the session, or only after the pair. */
    private static void pairs(File root,final boolean publishAfterPair)throws Exception{
        final Session s=new Session(root,false);
        try{
            s.photo("100MSDCF","_DSC9696.JPG");s.db.publish(10,100,9696);
            long at=0;
            for(int shot=0;shot<2;shot++){
                final int number=9697+shot*2;
                long original=s.completion.begin(at);
                s.store(original,at+100,writes(new Write(){public void run()throws Exception{s.photo("100MSDCF","_DSC"+number+".JPG");}}));
                CaptureStoreCompletion.Receipt r=s.await(original,at+200);
                check(r.recovered&&r.predicted&&r.file.getName().equals("_DSC"+number+".JPG"),"original confirmed by its predicted DCF file: "+r.file);
                s.files.accepted(r.file);
                long graded=s.completion.beginFinalStore(at+5000);
                s.store(graded,at+5100,writes(new Write(){public void run()throws Exception{
                    s.photo("100MSDCF","_DSC"+(number+1)+".JPG");
                    if(publishAfterPair){s.db.publish(11+number,100,number);s.db.publish(12+number,100,number+1);}
                }}));
                r=s.await(graded,at+5200);
                check(r.recovered&&r.file.getName().equals("_DSC"+(number+1)+".JPG"),"graded confirmed after the pair: "+r.file);
                check(publishAfterPair||r.predicted,"graded uses prediction when the index never publishes");
                s.files.accepted(r.file);
                boolean refused=false;try{s.completion.beginFinalStore(at+9000);}catch(IllegalStateException expected){refused=true;}
                check(refused,"no third record in a declared pair");
                at+=10000;
            }
            System.out.println("PASS legacy pair by predicted DCF publish_after_pair="+publishAfterPair+" shots=2");
        }finally{s.close();}
    }
    /** An unindexed file already at the predicted number can never confirm the store;
     * the camera numbers after it, which the folder's newest file predicts. */
    private static void preexisting(File root)throws Exception{
        final Session s=new Session(root,false);
        try{
            s.photo("100MSDCF","DSC09696.JPG");s.db.publish(10,100,9696);File stale=s.photo("100MSDCF","DSC09697.JPG");
            long token=s.completion.begin(0);
            check(s.files.prediction().contains("100/9697:exists")&&s.files.prediction().contains("folder_max=100/9697"),"pre-existing candidate excluded: "+s.files.prediction());
            s.store(token,100,writes(new Write(){public void run()throws Exception{s.photo("100MSDCF","DSC09698.JPG");}}));
            CaptureStoreCompletion.Receipt r=s.await(token,200);
            check(r.recovered&&r.predicted&&r.file.getName().equals("DSC09698.JPG"),"the new file after the pre-existing one is confirmed: "+r.file);
            check(stale.isFile(),"pre-existing photo untouched");
            System.out.println("PASS legacy prediction never confirms a pre-existing file and predicts after it");
        }finally{s.close();}
    }
    /** A file outside every prediction is never guessed at; index recovery confirms it. */
    private static void unpredicted(File root)throws Exception{
        final Session s=new Session(root,false);
        try{
            s.photo("100MSDCF","DSC09696.JPG");s.db.publish(10,100,9696);
            long token=s.completion.begin(0);
            s.store(token,100,writes(new Write(){public void run()throws Exception{s.photo("100MSDCF","DSC09699.JPG");}}));
            s.stays(token,200);
            s.db.publish(11,100,9699);
            CaptureStoreCompletion.Receipt r=s.await(token,20000);
            check(r.recovered&&!r.predicted&&r.file.getName().equals("DSC09699.JPG"),"index recovery still confirms the real new record");
            System.out.println("PASS legacy prediction miss falls back to the index");
        }finally{s.close();}
    }
    /** Confirm one original written by the camera, then its graded file after it. */
    private static void confirmPair(final Session s,final String original,final String graded)throws Exception{
        long token=s.completion.begin(0);
        s.store(token,100,writes(new Write(){public void run()throws Exception{s.photo("100MSDCF",original);}}));
        CaptureStoreCompletion.Receipt r=s.await(token,200);
        check(r.recovered&&r.predicted&&r.file.getName().equals(original),"original confirmed by prediction: "+r.file+" "+s.files.prediction());
        s.files.accepted(r.file);
        token=s.completion.beginFinalStore(10000);
        s.store(token,10100,writes(new Write(){public void run()throws Exception{s.photo("100MSDCF",graded);}}));
        r=s.await(token,10200);
        check(r.predicted&&r.file.getName().equals(graded),"graded confirmed after the original: "+r.file);
    }
    /** The A6000 field report (ILCE-6000 3.21, rc.10): the index head names a photo
     * that is not on the card, and the camera numbers after the files present. */
    private static void phantomAnchor(File root)throws Exception{
        final Session s=new Session(root,false);
        try{
            s.photo("100MSDCF","DSC00690.JPG");s.db.publish(10,100,690);
            s.photo("100MSDCF","DSC00691.JPG");s.db.publish(11,100,691);
            s.db.publish(12,100,693); // indexed newest, but no such file on the card
            confirmPair(s,"DSC00692.JPG","DSC00693.JPG");
            System.out.println("PASS legacy prediction confirms below a phantom index head");
        }finally{s.close();}
    }
    /** After an off-camera delete, Sony's series counter may still continue after the phantom. */
    private static void seriesCounter(File root)throws Exception{
        final Session s=new Session(root,false);
        try{
            s.photo("100MSDCF","DSC00690.JPG");s.db.publish(10,100,690);
            s.photo("100MSDCF","DSC00691.JPG");s.db.publish(11,100,691);
            s.db.publish(12,100,693);
            long token=s.completion.begin(0);
            String note=s.files.prediction();
            check(note.contains("after=100/693 folder_max=100/691 before_head=100/692 candidates=100/694,101/694,101/1,100/692,101/692,"),"both numberings predicted: "+note);
            s.store(token,100,writes(new Write(){public void run()throws Exception{s.photo("100MSDCF","DSC00694.JPG");}}));
            CaptureStoreCompletion.Receipt r=s.await(token,200);
            check(r.recovered&&r.predicted&&r.file.getName().equals("DSC00694.JPG"),"counter numbering after the phantom confirmed: "+r.file);
            System.out.println("PASS legacy prediction keeps the index head's own numbering");
        }finally{s.close();}
    }
    /** The A6000 field report (ILCE-6000 3.21, beta.8): the newest photos were
     * deleted in camera playback, so the folder's newest file falls far behind
     * the still-advanced index head; the camera writes just before the head. */
    private static void deletedInPlayback(File root)throws Exception{
        final Session s=new Session(root,false);
        try{
            s.photo("100MSDCF","DSC00711.JPG");s.db.publish(10,100,711);
            s.photo("100MSDCF","DSC00712.JPG");s.db.publish(11,100,712);
            s.db.publish(12,100,724); // head kept advancing; 713..724 never landed or were deleted
            long token=s.completion.begin(0);
            String note=s.files.prediction();
            check(note.contains("folder_max=100/712 before_head=100/723 candidates=")&&note.contains("100/723,"),"identity before the phantom head predicted: "+note);
            s.store(token,100,writes(new Write(){public void run()throws Exception{s.photo("100MSDCF","DSC00723.JPG");}}));
            CaptureStoreCompletion.Receipt r=s.await(token,200);
            check(r.recovered&&r.predicted&&r.file.getName().equals("DSC00723.JPG"),"write just below the phantom head confirmed: "+r.file);
            System.out.println("PASS legacy prediction includes the identity before a phantom index head");
        }finally{s.close();}
    }
    /** The full pair after a playback delete: the original lands just below the
     * phantom head, then the graded file takes the phantom head's own number. */
    private static void deletedPair(File root)throws Exception{
        final Session s=new Session(root,false);
        try{
            s.photo("100MSDCF","DSC00711.JPG");s.db.publish(10,100,711);
            s.photo("100MSDCF","DSC00712.JPG");s.db.publish(11,100,712);
            s.db.publish(12,100,724);
            confirmPair(s,"DSC00723.JPG","DSC00724.JPG");
            System.out.println("PASS legacy prediction confirms a pair below and at a phantom index head");
        }finally{s.close();}
    }
    /** The ILCE-7R 3.20 report (NAS a7r1, beta.3): its newest photo DSC04089 was
     * deleted, the index head is 990/4090 with no file, the folder's newest is 4088,
     * and the camera writes at the missing head's own number. */
    private static void missingHeadItself(File root)throws Exception{
        final Session s=new Session(root,false);
        try{
            s.photo("990MSDCF","DSC04087.JPG");s.db.publish(10,990,4087);
            s.photo("990MSDCF","DSC04088.JPG");s.db.publish(11,990,4088);
            s.db.publish(12,990,4090); // 4089 deleted; the head names no file
            long token=s.completion.begin(0);
            String note=s.files.prediction();
            check(note.contains("after=990/4090 folder_max=990/4088 before_head=990/4089 candidates=")&&note.contains("990/4090,"),"missing head itself predicted: "+note);
            s.store(token,100,writes(new Write(){public void run()throws Exception{s.photo("990MSDCF","DSC04090.JPG");}}));
            CaptureStoreCompletion.Receipt r=s.await(token,200);
            check(r.recovered&&r.predicted&&r.file.getName().equals("DSC04090.JPG"),"write at the missing head confirmed: "+r.file);
            System.out.println("PASS legacy prediction includes a missing index head's own number");
        }finally{s.close();}
    }
    /** The ILCE-7R 3.20 report (NAS 志威, beta.1): the first shot after the camera had
     * been off a long time. The index head is already the first number of a new folder
     * (103/1) and names no file or folder on the card; the camera writes there. The
     * previous folder's newest photo is the head's predecessor in neither numbering,
     * and the store stayed at 「正在核验照片保存」 until the camera was switched off. */
    private static void newFolderHead(File root,String folder,final String next)throws Exception{
        final Session s=new Session(root,false);
        try{
            for(int n=1;n<=3;n++){s.photo(folder,"_DSC000"+n+".JPG");s.db.publish(9+n,102,n);}
            s.db.publish(13,103,1); // first number of a folder not yet on the card
            long token=s.completion.begin(0);
            String note=s.files.prediction();
            check(note.contains("after=103/1 head_missing=103/1 candidates=")&&note.contains("103/1,"),"new folder's first number predicted: "+note);
            s.store(token,100,writes(new Write(){public void run()throws Exception{s.photo(next,"_DSC0001.JPG");}}));
            CaptureStoreCompletion.Receipt r=s.await(token,200);
            check(r.recovered&&r.predicted&&r.file.getName().equals("_DSC0001.JPG")&&r.file.getParentFile().getName().equals(next),"write at the new folder's first number confirmed: "+r.file);
            System.out.println("PASS legacy prediction includes a missing index head at a new folder's first number ("+next+")");
        }finally{s.close();}
    }
    private static final long HANDOFF=CaptureStoreCompletion.HANDOFF_MS,LIMIT=CaptureStoreCompletion.HANDOFF_LIMIT_MS;
    /** Owner decision 2026-11-27: a legacy store nothing identifies is handed off
     * to the camera HANDOFF_MS after storeImage returned, never waited on until
     * power-off. Its index record, published during the next store's wait, must
     * not be taken for that store's file: after a hand-off only the prediction
     * (absent before the store) confirms. */
    private static void handOffUnidentified(File root)throws Exception{
        final Session s=new Session(root,false);
        try{
            s.photo("100MSDCF","DSC00010.JPG");s.db.publish(10,100,10);
            long token=s.completion.begin(0);
            s.store(token,100,writes(new Write(){public void run()throws Exception{s.photo("100MSDCF","DSC00020.JPG");}}));
            check(!s.completion.handOff(token,150),"no hand-off before the first poll after the return");
            check(s.completion.poll(token,200)==null,"unpredicted file not confirmed");
            check(!s.completion.handOff(token,200+HANDOFF-1),"no hand-off before HANDOFF_MS");
            check(s.completion.handOff(token,200+HANDOFF),"handed off at HANDOFF_MS");
            check(!s.completion.pending()&&!s.completion.active(token),"handed-off store no longer pending");
            check(s.completion.diagnostics().contains("confirmation_stage=handed_off")&&s.completion.diagnostics().contains("hand_offs=1 index_recovery=false"),"hand-off diagnostics: "+s.completion.diagnostics());
            check(s.completion.poll(token,200+HANDOFF+200)==null,"a handed-off store never confirms later");
            // Next store: the handed-off photo's record appears only after this
            // store's snapshot, exactly when an index recovery would take it.
            // While the camera has not written the new file yet, nothing confirms.
            long next=s.completion.begin(20000);
            s.db.publish(11,100,20);
            s.store(next,20100,writes(new Write(){public void run(){}}));
            s.stays(next,20200);
            s.photo("100MSDCF","DSC00021.JPG");
            CaptureStoreCompletion.Receipt r=s.await(next,30000);
            check(r.predicted&&r.file.getName().equals("DSC00021.JPG"),"the next store confirms its own file, not the handed-off one: "+r.file);
            System.out.println("PASS legacy hand-off after HANDOFF_MS; the handed-off photo's late index record is not taken for the next store");
        }finally{s.close();}
    }
    /** A predicted file that is still being written gets up to HANDOFF_LIMIT_MS. */
    private static void handOffWhileWriting(File root)throws Exception{
        final Session s=new Session(root,false);
        try{
            s.photo("100MSDCF","DSC00010.JPG");s.db.publish(10,100,10);
            long token=s.completion.begin(0);
            s.store(token,100,writes(new Write(){public void run()throws Exception{s.photo("100MSDCF","DSC00011.JPG",6000,4000,false);}}));
            long at=200;
            for(int n=0;n<8;n++,at+=1100)check(s.completion.poll(token,at)==null,"incomplete file not confirmed");
            check(s.completion.stage().equals("await_file"),"found file awaits completion: "+s.completion.stage());
            check(!s.completion.handOff(token,200+HANDOFF),"no hand-off at HANDOFF_MS while the found file is written");
            check(!s.completion.handOff(token,200+LIMIT-1),"still written before the limit");
            check(s.completion.handOff(token,200+LIMIT),"handed off at HANDOFF_LIMIT_MS");
            System.out.println("PASS legacy hand-off waits up to HANDOFF_LIMIT_MS for a found file still being written");
        }finally{s.close();}
    }
    /** Legacy +原片: a handed-off original still opens its declared second
     * record; a handed-off graded store closes the pair. */
    private static void pairHandOff(File root)throws Exception{
        final Session s=new Session(root,false);
        try{
            s.photo("100MSDCF","DSC00010.JPG");s.db.publish(10,100,10);
            long original=s.completion.begin(0);
            s.store(original,100,writes(new Write(){public void run()throws Exception{s.photo("100MSDCF","DSC00030.JPG");}}));
            check(s.completion.poll(original,200)==null,"unpredicted original not confirmed");
            check(s.completion.handOff(original,200+HANDOFF),"original handed off");
            long graded=s.completion.beginFinalStore(20000);
            s.store(graded,20100,writes(new Write(){public void run()throws Exception{s.photo("100MSDCF","DSC00040.JPG");}}));
            check(s.completion.poll(graded,20200)==null,"unpredicted graded not confirmed");
            check(s.completion.handOff(graded,20200+HANDOFF),"graded handed off");
            try{s.completion.beginFinalStore(40000);throw new AssertionError("a third record after the pair");}
            catch(IllegalStateException expected){}
            System.out.println("PASS legacy pair hand-off: the original's hand-off opens the declared graded record, the graded one closes the pair");
        }finally{s.close();}
    }
    /** Bodies with Sony's named receipt never hand off. */
    private static void namedNeverHandsOff(File root)throws Exception{
        final Session s=new Session(root,true);
        try{
            s.photo("100MSDCF","DSC00010.JPG");s.db.publish(10,100,10);
            long token=s.completion.begin(0);
            s.store(token,100,writes(new Write(){public void run(){}}));
            check(s.completion.poll(token,200)==null,"no receipt yet");
            check(!s.completion.handOff(token,200+LIMIT*10)&&s.completion.pending(),"named protocol keeps waiting for its receipt");
            System.out.println("PASS named-receipt stores are never handed off");
        }finally{s.close();}
    }
    /** ILCE-7R 3.20 heads its index with the number it writes next. After a
     * hand-off the last confirmed file is no longer the newest; the base is the
     * later of it and the index head, so the following store confirms again. */
    private static void handOffThenHead(File root)throws Exception{
        final Session s=new Session(root,false);
        try{
            for(int n=1;n<=3;n++){s.photo("102MSDCF","_DSC000"+n+".JPG");s.db.publish(9+n,102,n);}
            s.db.publish(13,102,4); // the number written next
            long first=s.completion.begin(0);
            s.store(first,100,writes(new Write(){public void run()throws Exception{s.photo("102MSDCF","_DSC0004.JPG");}}));
            CaptureStoreCompletion.Receipt r=s.await(first,200);s.files.accepted(r.file);
            check(r.file.getName().equals("_DSC0004.JPG"),"first store at the head: "+r.file);
            long second=s.completion.begin(10000);
            s.store(second,10100,writes(new Write(){public void run()throws Exception{s.photo("105MSDCF","_DSC0001.JPG");}}));
            check(s.completion.poll(second,10200)==null,"unpredicted folder not confirmed");
            check(s.completion.handOff(second,10200+HANDOFF),"second store handed off");
            s.db.publish(14,105,2); // the head moves past the handed-off photo
            long third=s.completion.begin(30000);
            check(s.files.prediction().startsWith("after=105/2"),"base is the index head after a hand-off: "+s.files.prediction());
            s.store(third,30100,writes(new Write(){public void run()throws Exception{s.photo("105MSDCF","_DSC0002.JPG");}}));
            r=s.await(third,30200);
            check(r.predicted&&r.file.getName().equals("_DSC0002.JPG")&&r.file.getParentFile().getName().equals("105MSDCF"),"third store confirmed after the hand-off: "+r.file);
            System.out.println("PASS after a hand-off the later of last confirmed file and index head is the prediction base");
        }finally{s.close();}
    }
    /** A predecessor still on the card is never a candidate; the head's successor still confirms. */
    private static void beforeExists(File root)throws Exception{
        final Session s=new Session(root,false);
        try{
            s.photo("100MSDCF","DSC00722.JPG");s.db.publish(10,100,722);
            File kept=s.photo("100MSDCF","DSC00723.JPG");s.db.publish(11,100,723);
            s.db.publish(12,100,724);
            long token=s.completion.begin(0);
            String note=s.files.prediction();
            check(note.contains("before_head=100/723 candidates=")&&note.contains("100/723:exists"),"existing predecessor excluded: "+note);
            s.store(token,100,writes(new Write(){public void run()throws Exception{s.photo("100MSDCF","DSC00725.JPG");}}));
            CaptureStoreCompletion.Receipt r=s.await(token,200);
            check(r.recovered&&r.predicted&&r.file.getName().equals("DSC00725.JPG"),"successor of the phantom head confirmed: "+r.file);
            check(kept.isFile(),"pre-existing predecessor untouched");
            System.out.println("PASS legacy prediction never confirms an existing predecessor of a phantom head");
        }finally{s.close();}
    }
    /** Files present above an absent index head, alone or with older files below it. */
    private static void filesAbove(File root,boolean below)throws Exception{
        final Session s=new Session(root,false);
        try{
            if(below){s.photo("100MSDCF","DSC00690.JPG");s.db.publish(10,100,690);}
            s.photo("100MSDCF","DSC00695.JPG"); // present but never indexed
            s.db.publish(11,100,693); // index head, absent
            confirmPair(s,"DSC00696.JPG","DSC00697.JPG");
            System.out.println("PASS legacy prediction follows the newest file above an absent index head below="+below);
        }finally{s.close();}
    }
    /** The index can lag files written earlier in the same boot (it may publish only
     * when the app exits); a new capture session starts from the stale head. */
    private static void staleIndex(File root)throws Exception{
        final Session s=new Session(root,false);
        try{
            s.photo("100MSDCF","DSC00690.JPG");s.db.publish(10,100,690);
            s.photo("100MSDCF","DSC00691.JPG");s.db.publish(11,100,691);
            s.photo("100MSDCF","DSC00692.JPG");s.photo("100MSDCF","DSC00693.JPG"); // previous session, unpublished
            confirmPair(s,"DSC00694.JPG","DSC00695.JPG");
            System.out.println("PASS legacy prediction follows files newer than a stale index head");
        }finally{s.close();}
    }
    /** A partially written prediction waits; wrong geometry is a confirmed terminal failure. */
    private static void validation(File root)throws Exception{
        final Session s=new Session(root,false);
        try{
            s.photo("100MSDCF","DSC00041.JPG");s.db.publish(1,100,41);
            long token=s.completion.begin(0);
            s.store(token,100,writes(new Write(){public void run()throws Exception{s.photo("100MSDCF","DSC00042.JPG",6000,4000,false);}}));
            s.stays(token,200);
            check(s.completion.stage().equals("await_file"),"incomplete predicted JPEG reports file wait: "+s.completion.stage());
            s.photo("100MSDCF","DSC00042.JPG");
            CaptureStoreCompletion.Receipt r=s.await(token,20000);
            check(r.predicted&&r.file.getName().equals("DSC00042.JPG"),"completed predicted JPEG confirms");
            s.files.accepted(r.file);
            token=s.completion.beginFinalStore(30000);
            s.store(token,30100,writes(new Write(){public void run()throws Exception{s.photo("100MSDCF","DSC00043.JPG",4000,3000,true);}}));
            boolean invalid=false;
            try{s.await(token,30200);}catch(CaptureStoreCompletion.ConfirmedInvalid expected){invalid=expected.file.getName().equals("DSC00043.JPG");}
            check(invalid,"wrong-sized predicted JPEG is a confirmed invalid store");
            System.out.println("PASS legacy prediction waits for a complete JPEG and rejects wrong geometry");
        }finally{s.close();}
    }
    /** Full folder and date-form folders move the next photo to a new DCF folder. */
    private static void folders(File root,final String last,final String nextFolder,final String nextName,int folder,int file)throws Exception{
        final Session s=new Session(root,false);
        try{
            s.photo(last.substring(0,8),last.substring(9));s.db.publish(5,folder,file);
            long token=s.completion.begin(0);
            s.store(token,100,writes(new Write(){public void run()throws Exception{s.photo(nextFolder,nextName);}}));
            CaptureStoreCompletion.Receipt r=s.await(token,200);
            check(r.predicted&&r.file.getName().equals(nextName)&&r.file.getParentFile().getName().equals(nextFolder),"next DCF folder predicted: "+r.file);
            System.out.println("PASS legacy prediction "+last+" -> "+nextFolder+"/"+nextName);
        }finally{s.close();}
    }
    /** Two predicted identities appearing together is ambiguous, never a guess. */
    private static void ambiguous(File root)throws Exception{
        final Session s=new Session(root,false);
        try{
            s.photo("100MSDCF","DSC00042.JPG");s.db.publish(1,100,42);
            long token=s.completion.begin(0);
            s.store(token,100,writes(new Write(){public void run()throws Exception{s.photo("100MSDCF","DSC00043.JPG");s.photo("101MSDCF","DSC00043.JPG");}}));
            boolean rejected=false;
            for(int n=0;n<40&&!rejected;n++)try{check(s.completion.poll(token,200+n*200)==null,"ambiguous prediction confirmed");}
            catch(IOException expected){rejected=expected.getMessage().contains("ambiguous predicted DCF photo");}
            check(rejected&&s.completion.pending(),"ambiguous predicted files are reported and stay unconfirmed");
            System.out.println("PASS legacy prediction rejects ambiguous new files");
        }finally{s.close();}
    }
    /** Named-receipt bodies keep the verified path; an empty index has no safe base. */
    private static void notUsed(File root,final boolean named)throws Exception{
        final Session s=new Session(root,named);
        try{
            if(named){s.photo("100MSDCF","DSC00042.JPG");s.db.publish(1,100,42);}
            long token=s.completion.begin(0);
            check(s.files.prediction().equals("none"),"no prediction for named="+named+": "+s.files.prediction());
            s.store(token,100,writes(new Write(){public void run()throws Exception{s.photo("100MSDCF",named?"DSC00043.JPG":"DSC00001.JPG");}}));
            s.stays(token,200);
            System.out.println("PASS legacy prediction not used "+(named?"with a named receipt protocol":"without an index base"));
        }finally{s.close();}
    }
    public static void main(String[] args)throws Exception{
        File root=File.createTempFile("legacy-dcf-","",args.length==0?null:new File(args[0]));check(root.delete()&&root.mkdir(),"scratch");
        try{
            pairs(new File(root,"never"),false);
            pairs(new File(root,"after-pair"),true);
            preexisting(new File(root,"preexisting"));
            unpredicted(new File(root,"unpredicted"));
            phantomAnchor(new File(root,"phantom"));
            seriesCounter(new File(root,"series"));
            deletedInPlayback(new File(root,"deleted"));
            deletedPair(new File(root,"deleted-pair"));
            missingHeadItself(new File(root,"missing-head"));
            newFolderHead(new File(root,"new-folder"),"102MSDCF","103MSDCF");
            newFolderHead(new File(root,"new-date-folder"),"10261126","10361127");
            handOffUnidentified(new File(root,"hand-off"));
            handOffWhileWriting(new File(root,"hand-off-writing"));
            namedNeverHandsOff(new File(root,"hand-off-named"));
            pairHandOff(new File(root,"hand-off-pair"));
            handOffThenHead(new File(root,"hand-off-head"));
            beforeExists(new File(root,"before-exists"));
            filesAbove(new File(root,"above"),false);
            filesAbove(new File(root,"above-below"),true);
            staleIndex(new File(root,"stale"));
            validation(new File(root,"validation"));
            folders(new File(root,"full"),"100MSDCF/DSC09999.JPG","101MSDCF","DSC00001.JPG",100,9999);
            folders(new File(root,"date"),"10061105/DSC00042.JPG","10161106","DSC00043.JPG",100,42);
            ambiguous(new File(root,"ambiguous"));
            notUsed(new File(root,"named"),true);
            notUsed(new File(root,"empty"),false);
        }finally{CapturePipelineTest.remove(root);}
    }
}
