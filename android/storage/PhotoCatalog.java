package org.hongen.sonylutprobe;

import java.io.*;
import java.util.*;

/** Small append-only pair receipts. Never renames, overwrites or deletes DCIM photos. */
final class PhotoCatalog {
    static final class Pair {
        final String id,lut;final int grid;final long time,displayMs,readyMs;final File original,graded;
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
        original=photo(original.getPath());graded=photo(graded.getPath());if(original.equals(graded))throw new IOException("原片与成片必须独立保存");
        long time=capturedAt;String id="P"+UUID.randomUUID().toString().replace("-","").substring(0,7).toUpperCase(Locale.US);
        Properties p=new Properties();p.setProperty("original",original.getPath());p.setProperty("graded",graded.getPath());p.setProperty("lut",lut);
        p.setProperty("grid",Integer.toString(grid));p.setProperty("time",Long.toString(time));p.setProperty("display",Long.toString(displayMs));p.setProperty("ready",Long.toString(readyMs));
        File temp=new File(root,id+".TMP"),dest=new File(root,id+".JSN");
        if(temp.exists()||dest.exists())throw new IOException("照片记录文件重名，请重试");
        try{LutLibrary.writeProperties(temp,p);if(!temp.renameTo(dest))throw new IOException("照片记录保存失败");}finally{temp.delete();}
        return new Pair(id,lut,grid,time,displayMs,readyMs,original,graded);
    }
    synchronized List<Pair> list()throws IOException {
        File[] files=root.listFiles();if(files==null)throw new IOException("无法读取照片记录");List<Pair> pairs=new ArrayList<Pair>();
        for(File file:files){if(!file.getName().matches("P[0-9A-F]{7}\\.JSN"))continue;
            Properties p=LutLibrary.readProperties(file);
            try{pairs.add(new Pair(file.getName(),p.getProperty("lut","LUT"),Integer.parseInt(p.getProperty("grid")),Long.parseLong(p.getProperty("time")),
                Long.parseLong(p.getProperty("display")),Long.parseLong(p.getProperty("ready")),photo(p.getProperty("original")),photo(p.getProperty("graded"))));}
            catch(RuntimeException e){throw new IOException("照片记录损坏："+file.getName());}
        }
        Collections.sort(pairs,new Comparator<Pair>(){public int compare(Pair a,Pair b){return b.graded.getPath().compareTo(a.graded.getPath());}});return pairs;
    }
}
