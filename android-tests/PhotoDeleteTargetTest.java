package io.github.englandlobster.lutcamera;

import java.io.*;
import java.util.*;

/** A delete acts on exactly one index record; a settling index is asked again. */
public final class PhotoDeleteTargetTest {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    /** Answers the queued replies in order, then repeats the last one. */
    private static final class Index implements PhotoDeleteTarget.Lookup {
        final List<List<Long>> replies=new ArrayList<List<Long>>();int asked;
        Index reply(Long... ids){replies.add(Arrays.asList(ids));return this;}
        public List<Long> ids(int folder,int file){
            check(folder==100&&file==143,"queried by DCF number: "+folder+"/"+file);
            return replies.get(Math.min(asked++,replies.size()-1));
        }
    }
    private static String refused(Index index,File jpeg,boolean raw)throws Exception{
        try{PhotoDeleteTarget.settle(index,new int[]{100,143},jpeg,raw,7,0);}catch(IOException expected){return expected.getMessage();}
        throw new AssertionError("delete was not refused");
    }
    public static void main(String[] args)throws Exception{
        File dir=File.createTempFile("photo-delete-","",args.length==0?null:new File(args[0]));check(dir.delete()&&dir.mkdir(),"scratch");
        File jpeg=new File(dir,"BJK00143.JPG");
        try{
            new FileOutputStream(jpeg).close();check(jpeg.isFile(),"photo on card");
            Index once=new Index().reply(7L);
            check(PhotoDeleteTarget.settle(once,new int[]{100,143},jpeg,false,7,0)==7&&once.asked==1,"one record is deleted at once");
            // NAS 298007 (ILCE-7M2 4.01): two records for BJK00143, one on the next attempt.
            Index settling=new Index().reply(7L,8L).reply(7L,8L).reply(8L);
            check(PhotoDeleteTarget.settle(settling,new int[]{100,143},jpeg,false,7,0)==8&&settling.asked==3,"duplicate records settle to one");
            Index late=new Index().reply().reply(9L);
            check(PhotoDeleteTarget.settle(late,new int[]{100,143},jpeg,false,7,0)==9,"a photo not yet indexed is found when it appears");
            Index twice=new Index().reply(7L,8L);
            String message=refused(twice,jpeg,false);
            check(twice.asked==7&&message.contains("2 条记录"),"lasting duplicates are refused, never guessed: "+message);
            Index missing=new Index().reply();
            message=refused(missing,jpeg,false);
            check(missing.asked==7&&message.contains("找不到 BJK00143.JPG"),"an unindexed photo still on the card is refused: "+message);
            check(jpeg.delete(),"photo removed");
            Index gone=new Index().reply();
            check(PhotoDeleteTarget.settle(gone,new int[]{100,143},jpeg,false,7,0)==-1&&gone.asked==1,"a photo gone from index and card counts as deleted");
            refused(new Index().reply(),jpeg,true);
            System.out.println("PASS photo delete acts on one settled index record");
        }finally{jpeg.delete();dir.delete();}
    }
}
