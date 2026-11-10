package io.github.englandlobster.lutcamera;

import java.io.*;
import java.nio.file.*;

public final class LutRemovalTest {
    private static void require(boolean value,String why){if(!value)throw new AssertionError(why);}
    private static void write(File f,String text)throws Exception{Files.write(f.toPath(),text.getBytes("UTF-8"));}
    private static final LutLibrary.Verifier VALID=new LutLibrary.Verifier(){public int inspect(File file){return 33;}};
    public static void main(String[] args)throws Exception {
        File base=Files.createTempDirectory("sony-remove-").toFile();
        try{
            File libraryRoot=new File(base,"library"),luts=new File(base,"LUTS");luts.mkdir();
            LutLibrary library=new LutLibrary(libraryRoot);
            File warmSource=new File(luts,"WARM.CUB"),coolSource=new File(luts,"COOL.CUB");
            write(warmSource,"TITLE \"Warm\"\nLUT_3D_SIZE 33\n");write(coolSource,"TITLE \"Cool\"\nLUT_3D_SIZE 33\n");
            LutLibrary.Entry warm=library.importFile(warmSource,null,"custom",VALID).entry,cool=library.importFile(coolSource,null,"custom",VALID).entry;
            File warmDir=warm.file.getParentFile();
            // Derived caches live beside the table and must go with it.
            write(new File(warmDir,"TABLE.BIN"),"bin");write(new File(warmDir,"PREVIEW.PRV"),"prv");write(new File(warmDir,"VIEWA.CFG"),"enabled=1");
            library.select(warm.id);

            library.remove(warm.id);
            require(!warmDir.exists(),"entry directory and caches removed");
            require(library.find(warm.id)==null&&library.entries().size()==1&&library.find(cool.id)!=null,"other entries preserved");
            require(library.find(library.selectedId())==null,"deleted selection eligible for default fallback");
            require(warmSource.isFile()&&coolSource.isFile(),"import sources on the card untouched");
            boolean rejected=false;try{library.remove(warm.id);}catch(IOException e){rejected=true;}
            require(rejected,"removing a missing entry is reported");
            rejected=false;try{library.remove("../escape");}catch(IOException e){rejected=true;}
            require(rejected&&library.entries().size()==1,"invalid id refused");
            require(library.importFile(warmSource,null,"custom",VALID).added,"deleted content can be imported again");
            warm=library.find(warm.id);warmDir=warm.file.getParentFile();

            // An interrupted delete is invisible immediately and retried later.
            File blocker=new File(warmDir,"BLOCKER");blocker.mkdir();write(new File(blocker,"KEEP"),"x");
            rejected=false;try{library.remove(warm.id);}catch(IOException e){rejected=true;}
            require(rejected&&new File(warmDir,"DELETE.DAT").isFile()&&!new File(warmDir,"ENTRY.DAT").exists(),"failed cleanup keeps the delete marker");
            require(library.find(warm.id)==null&&library.entries().size()==1,"partially deleted entry invisible");
            rejected=false;try{library.purgeDeleted();}catch(IOException e){rejected=true;}
            require(rejected&&warmDir.exists()&&new File(warmDir,"DELETE.DAT").isFile(),"blocked cleanup reported and retained for retry");
            new File(blocker,"KEEP").delete();blocker.delete();
            library.purgeDeleted();
            require(!warmDir.exists()&&library.find(cool.id)!=null,"retry completes the delete and preserves other entries");

            // Purge only acts on delete markers inside managed entry folders.
            File retired=new File(libraryRoot,"L00000AA");retired.mkdir();write(new File(retired,"RETIRED.DAT"),"id=x");
            File foreign=new File(libraryRoot,"NOTES");foreign.mkdir();write(new File(foreign,"DELETE.DAT"),"x");
            library.purgeDeleted();
            require(retired.isDirectory()&&foreign.isDirectory()&&library.find(cool.id)!=null,"purge ignores retirement markers and foreign folders");
            System.out.println("PASS LUT removal: caches removed, sources kept, selection fallback, re-import, interrupted delete retried, purge boundaries");
        }finally{delete(base);}
    }
    private static void delete(File f){File[] children=f.listFiles();if(children!=null)for(File child:children)delete(child);f.delete();}
}
