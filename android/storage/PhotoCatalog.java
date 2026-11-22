package io.github.englandlobster.lutcamera;

import java.io.*;
import java.util.*;

/** Small photo receipts: the graded JPEG, plus the original when one was
 * saved. Never renames, overwrites or deletes DCIM photos itself; a receipt
 * whose graded photo is gone (deleted in Sony playback, on a computer or by
 * PhotoDelete) is dropped when the history is listed. */
final class PhotoCatalog {
    static final class Pair {
        final String id,lut;final int grid;final long time,displayMs,readyMs;final File graded;
        /** Null when the original was not saved. */
        final File original;
        Pair(String id,String lut,int grid,long time,long displayMs,long readyMs,File original,File graded){
            this.id=id;this.lut=lut;this.grid=grid;this.time=time;this.displayMs=displayMs;this.readyMs=readyMs;this.original=original;this.graded=graded;
        }
    }
    private final File root,dcim;
    PhotoCatalog(File root,File sd)throws IOException {this.root=root;dcim=new File(sd,"DCIM").getCanonicalFile();
        // Sony FuFsys reports an empty directory as an enumeration error.
        LutLibrary.directoryNote(root,"FORMAT.INI","format=1\ncontent=LUT Camera photo-pair receipts; photographs remain in DCIM\n");
    }
    private File photo(String path)throws IOException {File f=new File(path).getCanonicalFile();if(!f.getPath().startsWith(dcim.getPath()+File.separator)||!f.getName().matches("[A-Z0-9_]{1,8}\\.JPG"))throw new IOException("照片路径无效");return f;}
    synchronized Pair save(File original,File graded,String lut,int grid,long displayMs,long readyMs)throws IOException {
        return save(original,graded,lut,grid,displayMs,readyMs,System.currentTimeMillis());
    }
    synchronized Pair save(File original,File graded,String lut,int grid,long displayMs,long readyMs,long capturedAt)throws IOException {
        graded=photo(graded.getPath());
        if(original!=null){original=photo(original.getPath());if(original.equals(graded))throw new IOException("原片与成片必须独立保存");}
        long time=capturedAt;String id="P"+UUID.randomUUID().toString().replace("-","").substring(0,7).toUpperCase(Locale.US);
        Properties p=new Properties();if(original!=null)p.setProperty("original",original.getPath());p.setProperty("graded",graded.getPath());p.setProperty("lut",lut);
        p.setProperty("grid",Integer.toString(grid));p.setProperty("time",Long.toString(time));p.setProperty("display",Long.toString(displayMs));p.setProperty("ready",Long.toString(readyMs));
        File temp=new File(root,id+".TMP"),dest=new File(root,id+".JSN");
        if(temp.exists()||dest.exists())throw new IOException("照片记录文件重名，请重试");
        try{LutLibrary.writeProperties(temp,p);if(!temp.renameTo(dest))throw new IOException("照片记录保存失败");}finally{temp.delete();}
        return new Pair(id,lut,grid,time,displayMs,readyMs,original,graded);
    }
    /** Receipts dropped by the last list() because their graded photo is gone. */
    int pruned;
    /** Timing of the last list(), for the gallery log. */
    String timing="";
    static final String CACHE="INDEX.CAC";
    /** Receipts are the record; INDEX.CAC is a derived copy of the parsed
     * receipts so opening the gallery reads one file instead of one per photo
     * (about 45 ms each on the A6300). Missing, stale or corrupt: rebuilt. */
    synchronized List<Pair> list()throws IOException {pruned=0;long t0=System.currentTimeMillis();
        File[] files=root.listFiles();if(files==null)throw new IOException("无法读取照片记录");
        long t1=System.currentTimeMillis();
        Map<String,Pair> cached=readCache();long t2=System.currentTimeMillis();
        // Only trust a missing photo when DCIM itself is readable; an unmounted
        // or failing card must never cost the user their history.
        boolean dcimReadable=dcim.isDirectory()&&dcim.list()!=null;
        Map<String,Set<String>> folders=new HashMap<String,Set<String>>();
        List<Pair> pairs=new ArrayList<Pair>();Map<String,Pair> keep=new HashMap<String,Pair>();int parsed=0;
        for(File file:files){String name=file.getName();if(!name.matches("P[0-9A-F]{7}\\.JSN"))continue;
            Pair pair=cached.get(name);
            if(pair==null){pair=parse(file);parsed++;}
            keep.put(name,pair);
            if(!exists(folders,pair.graded)){keep.remove(name);if(dcimReadable&&file.delete())pruned++;continue;}
            if(pair.original!=null&&!exists(folders,pair.original))
                pair=new Pair(pair.id,pair.lut,pair.grid,pair.time,pair.displayMs,pair.readyMs,null,pair.graded);
            pairs.add(pair);
        }
        long t3=System.currentTimeMillis();boolean wrote=false;
        if(parsed>0||!keep.keySet().equals(cached.keySet())){
            try{writeCache(keep);wrote=true;}catch(IOException e){new File(root,CACHE).delete();}
        }
        Collections.sort(pairs,new Comparator<Pair>(){public int compare(Pair a,Pair b){return b.graded.getPath().compareTo(a.graded.getPath());}});
        timing="receipts="+keep.size()+" parsed="+parsed+" list_ms="+(t1-t0)+" cache_ms="+(t2-t1)+" check_ms="+(t3-t2)+" write_ms="+(System.currentTimeMillis()-t3)+" cache_written="+wrote;
        return pairs;
    }
    private Pair parse(File file)throws IOException {
        Properties p=LutLibrary.readProperties(file);
        try{return new Pair(file.getName(),p.getProperty("lut","LUT"),Integer.parseInt(p.getProperty("grid")),Long.parseLong(p.getProperty("time")),
            Long.parseLong(p.getProperty("display")),Long.parseLong(p.getProperty("ready")),
            p.getProperty("original")==null?null:photo(p.getProperty("original")),photo(p.getProperty("graded")));}
        catch(RuntimeException e){throw new IOException("照片记录损坏："+file.getName());}
    }
    /** One directory listing per DCF folder instead of a stat per photo. A
     * folder that cannot be listed (FuFsys also reports an empty directory as
     * an error) falls back to checking each file. */
    private static boolean exists(Map<String,Set<String>> folders,File f){
        String dir=f.getParent();
        if(!folders.containsKey(dir)){String[] list=new File(dir).list();
            folders.put(dir,list==null?null:new HashSet<String>(Arrays.asList(list)));}
        Set<String> names=folders.get(dir);
        return names==null?f.isFile():names.contains(f.getName());
    }
    private Map<String,Pair> readCache(){
        Map<String,Pair> out=new HashMap<String,Pair>();File f=new File(root,CACHE);if(!f.isFile())return out;
        try{Properties p=LutLibrary.readProperties(f);
            if(!"1".equals(p.getProperty("format"))||!dcim.getPath().equals(p.getProperty("dcim")))return out;
            int n=Integer.parseInt(p.getProperty("count"));
            for(int i=0;i<n;i++){String k=i+".";String id=p.getProperty(k+"id"),o=p.getProperty(k+"original");
                if(id==null||!id.matches("P[0-9A-F]{7}\\.JSN"))return new HashMap<String,Pair>();
                out.put(id,new Pair(id,p.getProperty(k+"lut","LUT"),Integer.parseInt(p.getProperty(k+"grid")),Long.parseLong(p.getProperty(k+"time")),
                    Long.parseLong(p.getProperty(k+"display")),Long.parseLong(p.getProperty(k+"ready")),
                    o==null?null:cachedPhoto(o),cachedPhoto(p.getProperty(k+"graded"))));}
        }catch(Exception e){return new HashMap<String,Pair>();}
        return out;
    }
    /** Cached paths were canonical when written; check the boundary without
     * re-resolving every path component. */
    private File cachedPhoto(String path)throws IOException {
        File f=new File(path);
        if(!path.startsWith(dcim.getPath()+File.separator)||path.contains("/../")||!f.getName().matches("[A-Z0-9_]{1,8}\\.JPG"))throw new IOException("照片路径无效");
        return f;
    }
    private void writeCache(Map<String,Pair> pairs)throws IOException {
        Properties p=new Properties();p.setProperty("format","1");p.setProperty("dcim",dcim.getPath());int i=0;
        for(Pair pair:pairs.values()){String k=(i++)+".";p.setProperty(k+"id",pair.id);p.setProperty(k+"lut",pair.lut);
            p.setProperty(k+"grid",Integer.toString(pair.grid));p.setProperty(k+"time",Long.toString(pair.time));
            p.setProperty(k+"display",Long.toString(pair.displayMs));p.setProperty(k+"ready",Long.toString(pair.readyMs));
            if(pair.original!=null)p.setProperty(k+"original",pair.original.getPath());p.setProperty(k+"graded",pair.graded.getPath());}
        p.setProperty("count",Integer.toString(i));
        File temp=new File(root,"INDEX.TMP"),dest=new File(root,CACHE);
        try{LutLibrary.writeProperties(temp,p);if(dest.exists()&&!dest.delete())throw new IOException("cache replace failed");
            if(!temp.renameTo(dest))throw new IOException("cache rename failed");}
        finally{temp.delete();}
    }
    /** Drops one receipt after its photos were deleted. */
    synchronized void remove(Pair pair)throws IOException {
        if(!pair.id.matches("P[0-9A-F]{7}\\.JSN"))throw new IOException("照片记录无效");
        File f=new File(root,pair.id);if(f.exists()&&!f.delete())throw new IOException("照片记录删除失败");
    }
}
