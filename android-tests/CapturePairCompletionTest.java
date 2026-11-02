package org.hongen.sonylutprobe;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Sony may publish both records only after the second store in a capture. */
public final class CapturePairCompletionTest {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static final class Database implements PhotoIndex.Query {
        volatile boolean published,blockRead,blockSnapshot;
        volatile int reads,snapshots;
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final PhotoIndex.Entry original,graded;
        Database()throws IOException{
            original=new PhotoIndex.Entry("avindex://1000/00000001-default/00000001-00000001",0,100,1);
            graded=new PhotoIndex.Entry("avindex://1000/00000001-default/00000001-00000002",1,100,2);
        }
        public List<PhotoIndex.Entry> read(String selection,String order,int limit)throws IOException{
            boolean snapshot=selection==null;if(snapshot)snapshots++;else reads++;
            if(snapshot?blockSnapshot:blockRead){
                entered.countDown();try{release.await();}catch(InterruptedException e){throw new IOException("query interrupted",e);}
            }
            if(!published)return Collections.emptyList();
            return snapshot?Arrays.asList(graded):Arrays.asList(graded,original);
        }
    }
    private static File photo(File root,int number)throws Exception{
        File file=new File(root,"DCIM/100MSDCF/DSC0000"+number+".JPG");
        CapturePipelineTest.write(file,CapturePipelineTest.jpeg(6000,4000,true));return file;
    }
    private static void notice(CaptureStoreCompletion c,long token,File file)throws Exception{c.notified(token,"DCIM/100MSDCF/",file.getName());}
    private static CaptureStoreCompletion.Receipt await(CaptureStoreCompletion c,long token,long at)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<deadline){CaptureStoreCompletion.Receipt r=c.poll(token,at);if(r!=null)return r;at+=200;Thread.sleep(5);}
        throw new AssertionError("completion stalled: "+c.diagnostics());
    }
    private static void pair(File root,boolean stop,boolean loseFinal)throws Exception{
        Database db=new Database();CaptureIndexQueries index=new CaptureIndexQueries(new PhotoIndex(db,new File(root,"DCIM").getCanonicalFile()));
        PhotoFiles files=new PhotoFiles(root,PhotoFiles.Format.JPEG,false);files.expect(new CaptureGeometry(6000,4000));
        CaptureStoreCompletion c=new CaptureStoreCompletion(files,index,true);
        try{
            long first=c.begin(0);c.rpcStarted(first);File original=photo(root,1);notice(c,first,original);
            if(stop)c.requestStop();check(c.poll(first,31000)==null,"callback never releases active native arguments");c.rpcReturned(first);
            CaptureStoreCompletion.Receipt r=await(c,first,31200);
            check(!r.recovered&&r.stopRequested==stop&&!db.published&&db.reads==0,"original confirmed without pair index publication");files.accepted(r.file);
            long second=c.beginFinalStore(32000);c.rpcStarted(second);File graded=photo(root,2);
            c.notified(first,"DCIM/100MSDCF/",original.getName());notice(c,second,original);
            check(c.poll(second,33000)==null,"late original cannot complete final native store");
            db.published=true;if(!loseFinal)notice(c,second,graded);c.rpcReturned(second);
            r=await(c,second,34000);
            check(r.file.equals(graded.getCanonicalFile())&&r.recovered==loseFinal&&r.stopRequested==stop,"final receipt or batched-index recovery preserves both files");files.accepted(r.file);
            boolean refused=false;try{c.beginFinalStore(35000);}catch(IllegalStateException expected){refused=true;}
            check(refused,"cannot submit a third declared record");
            if(stop){refused=false;try{c.begin(36000);}catch(IllegalStateException expected){refused=true;}check(refused,"stop prevents a new capture after pair drains");}
            check(PhotoFiles.completeJpeg(original)&&PhotoFiles.completeJpeg(graded),"both records remain complete");
            System.out.println("PASS batched pair index stop="+stop+" lost_final="+loseFinal);
        }finally{db.release.countDown();index.close();}
    }
    private static void blockedIndex(File root,boolean snapshot,boolean modern)throws Exception{
        Database db=new Database();db.blockSnapshot=snapshot;db.blockRead=!snapshot;
        CaptureIndexQueries index=new CaptureIndexQueries(new PhotoIndex(db,new File(root,"DCIM").getCanonicalFile()),100);
        PhotoFiles files=new PhotoFiles(root,PhotoFiles.Format.JPEG,false);files.expect(new CaptureGeometry(6000,4000));
        CaptureStoreCompletion c=new CaptureStoreCompletion(files,index,modern);
        try{
            long token;
            try{token=c.begin(0);}catch(IOException expected){
                check(snapshot&&!modern&&!c.pending(),"legacy snapshot failure occurs before store submission");
                System.out.println("PASS blocked legacy snapshot fails before submission");return;
            }
            check(modern,"legacy snapshot must not proceed");c.rpcStarted(token);c.rpcReturned(token);File file=photo(root,1);
            if(!snapshot)c.poll(token,1000);
            check(db.entered.await(2,TimeUnit.SECONDS),"index worker entered controlled block");
            for(int n=0;n<20;n++)c.poll(token,1200+n*200);
            check(db.reads<=1&&db.snapshots==1,"only one index query may be outstanding");
            notice(c,token,file);CaptureStoreCompletion.Receipt r=await(c,token,6000);
            check(r!=null&&!r.recovered,"late success receipt bypasses blocked index worker");files.accepted(r.file);
            c.requestStop();long last=c.beginFinalStore(7000);c.rpcStarted(last);File graded=photo(root,2);notice(c,last,graded);c.rpcReturned(last);
            r=await(c,last,8000);check(r.stopRequested&&!c.pending(),"pair drains despite blocked index and stop");
            System.out.println("PASS blocked index isolated snapshot="+snapshot+" modern="+modern);
        }finally{db.release.countDown();index.close();}
    }
    public static void main(String[] args)throws Exception{
        File root=File.createTempFile("pair-complete-","",args.length==0?null:new File(args[0]));check(root.delete()&&root.mkdir(),"scratch");
        try{
            int number=0;
            for(boolean stop:new boolean[]{false,true})for(boolean lost:new boolean[]{false,true}){
                File dir=new File(root,"pair"+number++);check(new File(dir,"DCIM/100MSDCF").mkdirs(),"DCF");pair(dir,stop,lost);
            }
            for(boolean snapshot:new boolean[]{false,true}){
                File dir=new File(root,"block"+snapshot);check(new File(dir,"DCIM/100MSDCF").mkdirs(),"DCF");blockedIndex(dir,snapshot,true);
            }
            File dir=new File(root,"legacy");check(new File(dir,"DCIM/100MSDCF").mkdirs(),"DCF");blockedIndex(dir,true,false);
            File large=new File(root,"LARGE.JPG");byte[] header=CapturePipelineTest.jpeg(6000,4000,false),chunk=new byte[65536];
            Arrays.fill(chunk,(byte)42);OutputStream out=new FileOutputStream(large);
            try{out.write(header);for(int n=0;n<192;n++)out.write(chunk);}finally{out.close();}
            check(!PhotoFiles.completeJpeg(large),"12MiB partial stream is not complete");
            out=new FileOutputStream(large,true);try{out.write(255);out.write(217);out.write(new byte[4096]);}finally{out.close();}
            long begin=System.nanoTime();check(PhotoFiles.completeJpeg(large),"12MiB stream with Sony-style padding completes");
            System.out.println("PASS bounded 12MiB JPEG scan ms="+(System.nanoTime()-begin)/1000000+" heap_max="+Runtime.getRuntime().maxMemory());
        }finally{CapturePipelineTest.remove(root);}
    }
}
