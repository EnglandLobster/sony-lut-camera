package org.hongen.sonylutprobe;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class ProductStorageTest {
    private static void require(boolean condition,String why){if(!condition)throw new AssertionError(why);}
    private static void write(File f,String text)throws Exception{Files.write(f.toPath(),text.getBytes("UTF-8"));}
    public static void main(String[] args)throws Exception{
        File root=Files.createTempDirectory("sony-product-").toFile();
        try{
            File input=new File(root,"测试 LUT.cube");String source="TITLE \"暖色 测试\"\nLUT_3D_SIZE 33\n";write(input,source);
            final int[] inspected={0};
            LutLibrary.Verifier valid=new LutLibrary.Verifier(){public int inspect(File file)throws Exception{inspected[0]++;require(file.getName().equals("TABLE.CUB"),"verify staged bytes");return 33;}};
            File libraryRoot=new File(root,"library");LutLibrary library=new LutLibrary(libraryRoot);
            LutLibrary.Imported first=library.importFile(input,null,"自定义",valid);
            require(first.added&&first.entry.size==33&&first.entry.name.equals("暖色 测试"),"preserve title and grid");
            require(Arrays.equals(Files.readAllBytes(input.toPath()),Files.readAllBytes(first.entry.file.toPath())),"preserve exact bytes");
            library.select(first.entry.id);require(new LutLibrary(libraryRoot).selectedId().equals(first.entry.id),"selection persists outside process");
            File same=new File(root,"RENAMED.CUB");write(same,source);
            require(!library.importFile(same,null,"custom",valid).added&&inspected[0]==1,"content dedup independent of filename");
            input.delete();require(library.find(first.entry.id)!=null,"managed import survives removal of source");
            write(same,"broken");boolean rejected=false;
            try{library.importFile(same,null,"custom",new LutLibrary.Verifier(){public int inspect(File f)throws Exception{throw new IOException("invalid grid");}});}catch(IOException e){rejected=true;}
            require(rejected&&library.entries().size()==1&&libraryRoot.list().length==3,"failed import leaves no partial entry/stage");
            require(new String(Files.readAllBytes(same.toPath()),"UTF-8").equals("broken"),"invalid source preserved");
            require(library.find("../escape")==null,"entry id path boundary");
            File pending=new File(libraryRoot,"pending-interrupted");pending.mkdir();write(new File(pending,"TABLE.CUB"),"partial");
            require(library.entries().size()==1,"interrupted staging invisible");
            File scan=new File(root,"LUTS");scan.mkdir();write(new File(scan,"B.CUB"),"x");write(new File(scan,"a.cube"),"y");write(new File(scan,"notes.txt"),"z");
            Files.createSymbolicLink(new File(scan,"outside.cube").toPath(),same.toPath());
            require(LutLibrary.scan(scan).size()==2&&LutLibrary.scan(scan).get(0).getName().equals("a.cube"),"sorted scan and source containment");
            File emptyBase=new File(root,"EMPTY");emptyBase.mkdir();
            File sonyEmpty=new File(emptyBase.getPath()){@Override public File[] listFiles(){File[] f=super.listFiles();return f!=null&&f.length==0?null:f;}};
            require(LutLibrary.scan(sonyEmpty).isEmpty()&&new File(emptyBase,"README.TXT").isFile(),"Sony empty-directory null enumeration");
            File sd=new File(root,"sd"),dcim=new File(sd,"DCIM/100MSDCF");dcim.mkdirs();
            File original=new File(dcim,"DSC00001.JPG"),graded=new File(dcim,"DSC00002.JPG");write(original,"original");write(graded,"graded");
            PhotoCatalog catalog=new PhotoCatalog(new File(root,"history"),sd);
            catalog.save(original,graded,"暖色 测试",33,4000,4200);
            List<PhotoCatalog.Pair> pairs=catalog.list();require(pairs.size()==1&&pairs.get(0).original.equals(original.getCanonicalFile())&&pairs.get(0).grid==33,"pair roundtrip");
            rejected=false;try{catalog.save(original,original,"x",33,1,2);}catch(IOException e){rejected=true;}require(rejected,"distinct original required");
            rejected=false;try{catalog.save(same,graded,"x",33,1,2);}catch(IOException e){rejected=true;}require(rejected,"DCIM boundary required");
            graded.delete();require(catalog.list().size()==1&&original.isFile(),"missing image keeps receipt and original");
            require(new String(Files.readAllBytes(original.toPath()),"UTF-8").equals("original"),"never changes photo payload");
            System.out.println("PASS product storage: exact-byte import, title/grid, dedup, failed/interrupted imports, source containment, independent pair history, missing-file recovery");
        }finally{delete(root);}
    }
    private static void delete(File f){if(!Files.isSymbolicLink(f.toPath())){File[] files=f.listFiles();if(files!=null)for(File child:files)delete(child);}f.delete();}
}
