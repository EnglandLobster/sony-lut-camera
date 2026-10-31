package org.hongen.sonylutprobe;

import java.io.*;
import java.util.*;

public final class PhotoIndexTest {
    private interface Check {void run() throws Exception;}
    private static void reject(Check check,String reason)throws Exception{
        try{check.run();}catch(IOException expected){return;}throw new AssertionError(reason);
    }
    private static void notReady(Check check)throws Exception{
        try{check.run();}catch(PhotoIndex.NotReady expected){return;}throw new AssertionError("empty index must be retryable");
    }
    private static PhotoIndex.Entry entry(int n)throws IOException{
        return new PhotoIndex.Entry(String.format(Locale.US,"avindex://1000/00000001-default/%08X-%08X",n,n+100),n,100,n);
    }
    private static final class Fake implements PhotoIndex.Query {
        List<PhotoIndex.Entry> rows=new ArrayList<PhotoIndex.Entry>();String selection,order;int limit;
        public List<PhotoIndex.Entry> read(String selection,String order,int limit){
            this.selection=selection;this.order=order;this.limit=limit;return rows;
        }
    }
    public static void main(String[] args)throws Exception{
        final Fake fake=new Fake();final PhotoIndex index=new PhotoIndex(fake,new File("/sd/DCIM"));
        final File source=new File("/sd/DCIM/100MSDCF/DSC00039.JPG");
        fake.rows=Arrays.asList(entry(39));
        if(!index.source(source).uri.equals(entry(39).uri)||fake.limit!=2
                ||!"dcf_folder_number=100 AND dcf_file_number=39".equals(fake.selection))throw new AssertionError("exact DCF selection failed");
        fake.rows=Collections.emptyList();notReady(new Check(){public void run()throws Exception{index.source(source);}});
        fake.rows=Arrays.asList(entry(39),entry(39));reject(new Check(){public void run()throws Exception{index.source(source);}},"ambiguous DCF accepted");
        fake.rows=Arrays.asList(entry(40));reject(new Check(){public void run()throws Exception{index.source(source);}},"ignored provider selection accepted");
        for(final String path:new String[]{"/sd/OTHER/100MSDCF/DSC00039.JPG","/sd/DCIM/100MSDCF/../100MSDCF/DSC00039.JPG","/sd/DCIM/100MSDCF/DSC00000.JPG","/sd/DCIM/099MSDCF/DSC00039.JPG","/sd/DCIM/100MSDCF/BAD.JPG"})
            reject(new Check(){public void run()throws Exception{index.source(new File(path));}},"bad path accepted: "+path);
        fake.rows=Arrays.asList(entry(40));
        if(index.latestOrder()!=40||fake.limit!=1||!"rec_order DESC".equals(fake.order)||fake.selection!=null)throw new AssertionError("unbounded save marker");
        fake.rows=Collections.emptyList();if(index.latestOrder()!=0)throw new AssertionError("empty index watermark");
        fake.rows=Arrays.asList(entry(41));if(index.savedAfter(40,entry(39).uri).order!=41||fake.limit!=2||!"rec_order>40".equals(fake.selection))throw new AssertionError("incremental save query");
        fake.rows=Collections.emptyList();notReady(new Check(){public void run()throws Exception{index.savedAfter(40,entry(39).uri);}});
        fake.rows=Arrays.asList(entry(41),entry(42));reject(new Check(){public void run()throws Exception{index.savedAfter(40,entry(39).uri);}},"multiple new outputs accepted");
        fake.rows=Arrays.asList(entry(39));reject(new Check(){public void run()throws Exception{index.savedAfter(40,entry(38).uri);}},"stale output accepted");
        fake.rows=Arrays.asList(entry(41));reject(new Check(){public void run()throws Exception{index.savedAfter(40,entry(41).uri);}},"original accepted as output");
        fake.rows=Collections.emptyList();if(index.registeredAfter(38,source))throw new AssertionError("unregistered file accepted");
        fake.rows=Arrays.asList(entry(39));if(!index.registeredAfter(38,source))throw new AssertionError("matching new index/file rejected");
        reject(new Check(){public void run()throws Exception{index.registeredAfter(39,source);}},"old index entry reused");
        fake.rows=Arrays.asList(entry(40));reject(new Check(){public void run()throws Exception{index.registeredAfter(38,source);}},"new index for another file accepted");
        for(final String uri:new String[]{"avindex://1000/00000001-default/fcspoint","avindex://1000/00000001-default/rsmpoint","file:///sd/DSC00041.JPG"})
            reject(new Check(){public void run()throws Exception{new PhotoIndex.Entry(uri,41,100,41);}},"mutable or foreign URI accepted");
        System.out.println("PASS exact DCF identity, bounded queries, missing/ambiguous/stale results, source preservation, anchor rejection");
    }
}
