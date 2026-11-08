package org.hongen.sonylutprobe;

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
            check(note.contains("after=100/693 folder_max=100/691 candidates=100/694,101/694,101/1,100/692,101/692,"),"both numberings predicted: "+note);
            s.store(token,100,writes(new Write(){public void run()throws Exception{s.photo("100MSDCF","DSC00694.JPG");}}));
            CaptureStoreCompletion.Receipt r=s.await(token,200);
            check(r.recovered&&r.predicted&&r.file.getName().equals("DSC00694.JPG"),"counter numbering after the phantom confirmed: "+r.file);
            System.out.println("PASS legacy prediction keeps the index head's own numbering");
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
