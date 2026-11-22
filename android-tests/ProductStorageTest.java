package io.github.englandlobster.lutcamera;
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
            File alias=new File(root,"lut");alias.mkdir();write(new File(alias,"third.CUBE"),"z");
            File nested=new File(alias,"nested");nested.mkdir();write(new File(nested,"hidden.cube"),"z");
            write(new File(alias,"wrong.cube.txt"),"z");
            require(LutLibrary.scanImports(root).size()==3,"LUTS and lowercase singular lut are accepted; nested and double-extension files stay excluded");
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
            File history=new File(root,"history");
            // Original deleted elsewhere: the pair stays, without comparison.
            File keep=new File(root,"keep.jpg");Files.copy(original.toPath(),keep.toPath());original.delete();
            pairs=catalog.list();require(pairs.size()==1&&pairs.get(0).original==null&&catalog.pruned==0,"missing original drops only the comparison");
            Files.copy(keep.toPath(),original.toPath());
            // Card trouble (DCIM unreadable) hides the pair but never deletes its receipt.
            File dcimRoot=new File(sd,"DCIM"),parked=new File(sd,"PARKED");require(dcimRoot.renameTo(parked),"park DCIM");
            require(catalog.list().isEmpty()&&catalog.pruned==0&&receipts(history)==1,"unreadable DCIM keeps receipts");
            require(parked.renameTo(dcimRoot),"restore DCIM");
            // Graded deleted in Sony playback or on a computer: receipt dropped, original untouched.
            graded.delete();require(catalog.list().isEmpty()&&catalog.pruned==1&&original.isFile()&&receipts(history)==0,"missing graded drops receipt, keeps original");
            require(new String(Files.readAllBytes(original.toPath()),"UTF-8").equals("original"),"never changes photo payload");
            write(graded,"graded");catalog.save(original,graded,"暖色 测试",33,4000,4200);
            // Graded only (original not saved): no original key; old pair receipts still read.
            File solo=new File(dcim,"DSC00003.JPG");write(solo,"graded only");
            catalog.save(null,solo,"直出",25,3000,3100,1000L);
            List<PhotoCatalog.Pair> mixed=catalog.list();
            require(mixed.size()==2&&mixed.get(0).graded.equals(solo.getCanonicalFile())&&mixed.get(0).original==null
                &&mixed.get(1).original.equals(original.getCanonicalFile()),"graded-only receipt beside a pair");
            rejected=false;try{catalog.save(null,same,"x",25,1,2);}catch(IOException e){rejected=true;}require(rejected,"graded-only DCIM boundary required");
            catalog.remove(mixed.get(0));require(catalog.list().size()==1&&solo.isFile(),"remove drops only the receipt");
            rejected=false;try{catalog.remove(new PhotoCatalog.Pair("../x",null,0,0,0,0,null,solo));}catch(IOException e){rejected=true;}require(rejected,"remove rejects foreign names");
            // Derived index: second open parses nothing, new receipts parse alone, damage rebuilds.
            File cache=new File(history,"INDEX.CAC");
            List<PhotoCatalog.Pair> indexed=catalog.list();require(cache.isFile()||new File(history,"INDEX.LOG").isFile(),"index or journal written");
            List<PhotoCatalog.Pair> again=catalog.list();
            require(catalog.timing.contains("parsed=0")&&catalog.timing.contains("cache_written=false")&&again.size()==indexed.size()
                &&again.get(0).graded.equals(indexed.get(0).graded)&&again.get(0).lut.equals(indexed.get(0).lut)&&again.get(0).grid==indexed.get(0).grid
                &&(again.get(0).original==null)==(indexed.get(0).original==null),"index reused without reparsing");
            File fresh=new File(dcim,"DSC00004.JPG");write(fresh,"new");catalog.save(null,fresh,"新 LUT=暖\n色",17,1,2);
            List<PhotoCatalog.Pair> grown=catalog.list();
            File journal=new File(history,"INDEX.LOG");
            require(catalog.timing.contains("parsed=0")&&!catalog.timing.contains("journal=0")&&catalog.timing.contains("cache_written=false")
                &&journal.isFile()&&grown.size()==indexed.size()+1&&grown.get(0).lut.equals("新 LUT=暖\n色")&&grown.get(0).grid==17,"saved receipt read from the journal, odd LUT names survive");
            // A torn or altered journal line fails its checksum: that receipt is parsed and the journal folded away.
            String line=new String(Files.readAllBytes(journal.toPath()),"ISO-8859-1");
            write(journal,line.replaceFirst("\\|17\\|","|18|"));
            List<PhotoCatalog.Pair> reread=catalog.list();
            require(catalog.timing.contains("parsed=1")&&reread.get(0).grid==17&&!journal.exists()&&catalog.timing.contains("cache_written=true"),"bad journal line ignored, receipt parsed");
            write(journal,line.substring(0,line.length()-9));
            require(catalog.list().get(0).grid==17&&catalog.timing.contains("parsed=0"),"truncated journal line ignored");
            require(catalog.list().get(0).lut.equals("新 LUT=暖\n色")&&catalog.timing.contains("parsed=0"),"index round-trips names");
            // The journal is folded into the index once it is long.
            File[] many=new File[PhotoCatalog.COMPACT_AT];
            for(int i=0;i<many.length;i++){many[i]=new File(dcim,String.format("DSC%05d.JPG",100+i));write(many[i],"m");catalog.save(null,many[i],"批量",25,1,2);}
            List<PhotoCatalog.Pair> bulk=catalog.list();
            require(bulk.size()==grown.size()+many.length&&catalog.timing.contains("parsed=0")&&catalog.timing.contains("cache_written=true")&&!journal.exists(),"long journal compacted");
            require(catalog.list().size()==bulk.size()&&catalog.timing.contains("journal=0")&&catalog.timing.contains("cache_written=false"),"compacted index reused");
            for(File m:many){m.delete();}catalog.list();require(catalog.pruned==many.length,"bulk deletions pruned");
            write(cache,"format=1\ncount=zz\n");require(catalog.list().size()==grown.size()&&!catalog.timing.contains("parsed=0"),"corrupt index rebuilt");
            write(cache,"format=1\ndcim="+new File(sd,"DCIM").getCanonicalPath()+"\ncount=1\n0.id=PAAAAAAA.JSN\n0.grid=1\n0.time=1\n0.display=1\n0.ready=1\n0.graded="+same.getCanonicalPath()+"\n");
            require(catalog.list().size()==grown.size(),"index with a path outside DCIM ignored");
            fresh.delete();require(catalog.list().size()==indexed.size()&&catalog.pruned==1,"photo deleted after indexing is dropped");
            System.out.println("PASS product storage: exact-byte import, title/grid, dedup, failed/interrupted imports, source containment, independent pair and graded-only history, missing-photo pruning, receipt removal, derived index, checksummed journal");
        }finally{delete(root);}
    }
    private static int receipts(File dir){int n=0;for(String name:dir.list())if(name.endsWith(".JSN"))n++;return n;}
    private static void delete(File f){if(!Files.isSymbolicLink(f.toPath())){File[] files=f.listFiles();if(files!=null)for(File child:files)delete(child);}f.delete();}
}
