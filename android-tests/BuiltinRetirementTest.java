package org.hongen.sonylutprobe;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public final class BuiltinRetirementTest {
    private static final String COLOR="06ceaf85781931e6e1d54007d148435a367a579375aeb2de3f881daca9abc743";
    private static final String IDENTITY="a3ea0238d24b77daa43745137696aa81dffe3f1d5b44e9cb87f4e1c5d85e7f6a";
    private static final String CUSTOM="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static void require(boolean value,String why){if(!value)throw new AssertionError(why);}
    private static File entry(File root,String folder,String id,String name)throws Exception {
        File dir=new File(root,folder);dir.mkdir();
        Files.write(new File(dir,"TABLE.CUB").toPath(),"synthetic test payload".getBytes("US-ASCII"));
        Files.write(new File(dir,"TABLE.BIN").toPath(),new byte[]{1,2,3});
        Properties p=new Properties();p.setProperty("id",id);p.setProperty("name",name);p.setProperty("source","test");p.setProperty("size","33");
        LutLibrary.writeProperties(new File(dir,"ENTRY.DAT"),p);return dir;
    }
    public static void main(String[] args)throws Exception {
        File base=Files.createTempDirectory("sony-retire-").toFile();
        try {
            File root=new File(base,"library"),cache=new File(base,"builtins");cache.mkdir();
            LutLibrary library=new LutLibrary(root);
            File color=entry(root,"L0000001",COLOR,"示例调色");
            File custom=entry(root,"L0000003",CUSTOM,"原色");
            for(String name:new String[]{"COLOR33.CUB","IDENT33.CUB","FJC200.CUB"})Files.write(new File(cache,name).toPath(),new byte[]{7});
            library.select(COLOR);
            // An interrupted deletion must be invisible and retryable.
            File blocker=new File(color,"BLOCKER");blocker.mkdir();
            Files.write(new File(blocker,"KEEP").toPath(),new byte[]{9});
            boolean failed=false;
            try{library.retireBuiltinSamples(cache);}catch(IOException e){failed=true;}
            require(failed&&!new File(color,"ENTRY.DAT").exists()&&new File(color,"RETIRED.DAT").exists(),"failed cleanup retains retirement marker");
            require(library.find(COLOR)==null,"partial retired entry invisible");
            delete(blocker);
            // Simulate an earlier run interrupted after the marker rename.
            File identity=entry(root,"L0000002",IDENTITY,"原色");
            require(new File(identity,"ENTRY.DAT").renameTo(new File(identity,"RETIRED.DAT")),"stage interrupted identity retirement");
            library.retireBuiltinSamples(cache);
            require(!color.exists()&&!identity.exists(),"both retired entry directories removed");
            require(library.entries().size()==1&&custom.exists()&&library.find(CUSTOM)!=null,"same display name with different content preserved");
            require(library.find(library.selectedId())==null,"retired selection eligible for default fallback");
            require(cache.list().length==1&&new File(cache,"FJC200.CUB").exists(),"only retired source caches removed");
            library.select(CUSTOM);library.retireBuiltinSamples(cache);
            require(library.selectedId().equals(CUSTOM)&&library.entries().size()==1,"idempotent cleanup preserves valid selection");
            System.out.println("PASS sample retirement: exact IDs, interrupted retry, old selection fallback, custom imports and valid selection preserved");
        } finally {delete(base);}
    }
    private static void delete(File f){File[] children=f.listFiles();if(children!=null)for(File child:children)delete(child);f.delete();}
}
