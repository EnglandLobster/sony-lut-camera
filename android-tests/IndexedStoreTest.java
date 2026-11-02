package org.hongen.sonylutprobe;

import java.io.*;

/** Real filesystem fixtures: index lag must not touch DCIM; only the indexed identity wins. */
public final class IndexedStoreTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final class Index implements CaptureStoreCompletion.Registration {
        PhotoIndex.Entry entry;int queries;
        public void begin(){entry=null;}
        public void accepted(File file){}
        public PhotoIndex.Entry saved(){queries++;return entry;}
        void set(int folder,int number)throws IOException{
            entry=new PhotoIndex.Entry("avindex://test/00000000-default/00000000-00000001",1,folder,number);
        }
    }
    public static void main(String[] args)throws Exception{
        File sd=File.createTempFile("indexed-store-","",args.length==0?null:new File(args[0]));
        check(sd.delete()&&sd.mkdir(),"scratch");
        try{
            File dcim=new File(sd,"DCIM"),folder=new File(dcim,"123MSDCF");check(folder.mkdirs(),"DCF");
            // Many unrelated files, including fresh unindexed files, must not
            // become candidates or make recovery ambiguous.
            for(int i=1;i<=300;i++)CapturePipelineTest.write(new File(folder,String.format(java.util.Locale.US,"DSC0%04d.JPG",i)),new byte[]{1,2});
            PhotoFiles files=new PhotoFiles(sd,PhotoFiles.Format.JPEG,false);files.expect(new CaptureGeometry(6000,4000));
            Index index=new Index();CaptureStoreCompletion completion=new CaptureStoreCompletion(files,index,true);
            long token=completion.begin(0);
            check(completion.poll(token,2000)==null&&index.queries==0,"callback cannot confirm before RPC submission");
            completion.rpcStarted(token);completion.rpcReturned(token);
            File hidden=new File(sd,"HELD");check(dcim.renameTo(hidden),"hide DCIM");
            check(completion.poll(token,2100)==null&&index.queries==1,"index lag does not enumerate unavailable DCIM");
            check(hidden.renameTo(dcim),"restore DCIM");
            // File timestamps are intentionally inverted; the indexed DCF
            // identity, not newest filesystem mtime, selects the photo.
            File target=new File(folder,"_DSC0400.JPG");CapturePipelineTest.write(target,CapturePipelineTest.jpeg(6000,4000,true));
            check(target.setLastModified(1000000000000L),"old timestamp");index.set(123,400);
            check(completion.poll(token,2200)==null,"first metadata sample");
            CaptureStoreCompletion.Receipt receipt=completion.poll(token,2400);
            check(receipt!=null&&receipt.recovered&&receipt.file.equals(target.getCanonicalFile()),"newest-mtime decoys cannot replace indexed photo when callback is absent");
            files.accepted(receipt.file);
            check(completion.poll(token,2600)==null,"one completion");

            token=CapturePipelineTest.returnedStore(completion,3000);index.set(123,401);
            File partial=new File(folder,"DSC00401.JPG");byte[] bytes=CapturePipelineTest.jpeg(6000,4000,true);
            byte[] damaged=bytes.clone();damaged[damaged.length-2]=1;damaged[damaged.length-1]=1;
            CapturePipelineTest.write(partial,damaged);check(partial.setLastModified(1000000000000L),"partial timestamp");
            completion.poll(token,4100);check(completion.poll(token,4300)==null,"partial indexed JPEG stays pending");
            CapturePipelineTest.write(partial,bytes);check(partial.setLastModified(1000000000000L),"preserve FAT timestamp");
            check(completion.poll(token,4500)==null,"unchanged metadata throttles repeated payload reads");
            receipt=completion.poll(token,5400);
            check(receipt!=null&&receipt.file.equals(partial.getCanonicalFile()),"in-place final write eventually rechecked despite unchanged size/mtime");
            files.accepted(receipt.file);

            token=CapturePipelineTest.returnedStore(completion,6000);index.set(123,402);
            check(completion.poll(token,7100)==null,"index can precede file creation");
            File delayed=new File(folder,"DSC00402.JPG");CapturePipelineTest.write(delayed,bytes);
            completion.poll(token,8200);receipt=completion.poll(token,8400);
            check(receipt!=null&&receipt.file.equals(delayed.getCanonicalFile()),"late target discovered after bounded retry");files.accepted(receipt.file);

            token=CapturePipelineTest.returnedStore(completion,9000);index.set(123,403);
            CapturePipelineTest.write(new File(folder,"DSC00403.JPG"),bytes);
            CapturePipelineTest.write(new File(folder,"_DSC0403.JPG"),bytes);
            boolean rejected=false;try{completion.poll(token,10100);}catch(IOException expected){rejected=true;}
            check(rejected&&completion.pending(),"ambiguous DCF identity retains ownership");completion.cancel();
            files.resetCandidate();index.set(123,400);files.pollIndexed(index.entry,11000);
            index.set(123,403);rejected=false;
            try{files.pollIndexed(index.entry,12200);}catch(IOException expected){rejected=true;}
            check(rejected&&files.pollIndexed(index.entry,12400)==null,"failed identity change cannot reuse previously cached photo");
            System.out.println("PASS indexed store: no DCIM IO before index/RPC, 300 decoys, lost callback, timestamps, partial/in-place/delayed files, ambiguous identity");
        }finally{CapturePipelineTest.remove(sd);}
    }
}
