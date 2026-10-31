package org.hongen.sonylutprobe;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Immutable, content-addressed imports. An entry is published only after
 * the exact staged bytes pass the same parser used by the capture engine.
 * Uses uppercase DOS 8.3 paths for Sony SD storage. ENTRY.DAT is the commit
 * marker: incomplete imports never appear in the library. */
final class LutLibrary {
    interface Verifier { int inspect(File file) throws Exception; }
    static final long MAX_BYTES=64L*1024*1024;
    static final class Entry {
        final String id,name,source; final int size; final File file;
        Entry(String id,String name,String source,int size,File file){this.id=id;this.name=name;this.source=source;this.size=size;this.file=file;}
    }
    static final class Imported {
        final Entry entry; final boolean added;
        Imported(Entry entry,boolean added){this.entry=entry;this.added=added;}
    }
    private final File root;
    LutLibrary(File root)throws IOException {this.root=root.getCanonicalFile();directoryNote(this.root,"FORMAT.INI","format=1\ncontent=LUT Camera managed LUTs\n");}
    static void ensureDirectory(File dir)throws IOException {if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建存储目录");}
    static void directoryNote(File dir,String name,String text)throws IOException {
        ensureDirectory(dir);File file=new File(dir,name);if(file.exists())return;
        if(!file.createNewFile())throw new IOException("无法初始化存储目录");
        OutputStream out=new FileOutputStream(file);try{out.write(text.getBytes("UTF-8"));out.flush();}finally{out.close();}
    }
    static String label(String text){
        if(text==null)return "未命名 LUT";
        String clean=text.replaceAll("[\\p{Cntrl}]"," ").trim();
        if(clean.length()==0)return "未命名 LUT";
        return clean.length()>80?clean.substring(0,80):clean;
    }
    static boolean cubeFile(File f){String n=f.getName().toLowerCase(Locale.US);return f.isFile()&&(n.endsWith(".cube")||n.endsWith(".cub"));}
    static List<File> scan(File folder)throws IOException {
        ensureDirectory(folder);File[] files=folder.listFiles();
        // Sony FuFsys returns null for a valid, empty directory. A useful note
        // makes enumeration unambiguous; genuine IO errors still fail below.
        if(files==null){directoryNote(folder,"README.TXT","LUT Camera: copy 3D .cube / .cub files into this directory.\nSource files are preserved on import.\n");files=folder.listFiles();}
        if(files==null)throw new IOException("无法读取 SD 卡 LUTS 文件夹");
        List<File> result=new ArrayList<File>();File base=folder.getCanonicalFile();
        for(File file:files)if(cubeFile(file)&&base.equals(file.getCanonicalFile().getParentFile()))result.add(file);
        Collections.sort(result,new Comparator<File>(){public int compare(File a,File b){return a.getName().compareToIgnoreCase(b.getName());}});
        return result;
    }
    synchronized Imported importFile(File input,String displayName,String source,Verifier verifier)throws Exception {
        if(!input.isFile()||input.length()==0||input.length()>MAX_BYTES)throw new IOException("文件为空或超过 64 MB");
        File stage=new File(root,shortId());if(!stage.mkdir())throw new IOException("无法创建 LUT 导入目录");
        boolean committed=false;
        try{
            File cube=new File(stage,"TABLE.CUB");MessageDigest digest=MessageDigest.getInstance("SHA-256");
            InputStream in=new FileInputStream(input);try{
                OutputStream out=new FileOutputStream(cube);try{
                    byte[] buffer=new byte[32768];long total=0;int n;
                    while((n=in.read(buffer))!=-1){total+=n;if(total>MAX_BYTES)throw new IOException("文件超过 64 MB");out.write(buffer,0,n);digest.update(buffer,0,n);}
                    out.flush();
                }finally{out.close();}
            }finally{in.close();}
            String id=hex(digest.digest());Entry existing=find(id);
            if(existing!=null)return new Imported(existing,false);
            int size=verifier.inspect(cube);if(size<2||size>65)throw new IOException("不支持的 3D LUT 阶数");
            String name=displayName==null?title(cube,input.getName()):label(displayName);
            Properties p=new Properties();p.setProperty("id",id);p.setProperty("name",name);p.setProperty("source",label(source));p.setProperty("size",Integer.toString(size));
            File pending=new File(stage,"ENTRY.TMP");writeProperties(pending,p);
            if(!pending.renameTo(new File(stage,"ENTRY.DAT")))throw new IOException("导入未完成：无法保存 LUT");
            committed=true;return new Imported(new Entry(id,name,label(source),size,cube),true);
        }finally{if(!committed)removeStage(stage);}
    }
    private static String shortId(){return "L"+UUID.randomUUID().toString().replace("-","").substring(0,7).toUpperCase(Locale.US);}
    private static final class Choice {final long generation;final String id;final int slot;Choice(long g,String i,int s){generation=g;id=i;slot=s;}}
    private Choice choice()throws IOException {
        Choice latest=null;for(int i=0;i<2;i++){File f=new File(root,i==0?"LASTA.CFG":"LASTB.CFG");if(!f.isFile())continue;
            Properties p=readProperties(f);try{long g=Long.parseLong(p.getProperty("generation","-1"));String id=p.getProperty("id","");
                if(g>=0&&id.matches("[0-9a-f]{64}")&&(latest==null||g>latest.generation))latest=new Choice(g,id,i);
            }catch(NumberFormatException ignored){/* interrupted/corrupt slot; the other slot remains valid */}}
        return latest;
    }
    synchronized String selectedId()throws IOException {Choice c=choice();return c==null?null:c.id;}
    synchronized void select(String id)throws IOException {
        if(find(id)==null)throw new IOException("选择的 LUT 不存在");Choice old=choice();
        if(old!=null&&old.id.equals(id))return;
        File next=new File(root,old==null||old.slot==1?"LASTA.CFG":"LASTB.CFG"),temp=new File(root,"CHOICE.TMP");
        Properties p=new Properties();p.setProperty("id",id);p.setProperty("generation",Long.toString(old==null?0:old.generation+1));
        try{writeProperties(temp,p);if(next.exists()&&!next.delete())throw new IOException("无法保存当前 LUT");
            if(!temp.renameTo(next))throw new IOException("无法保存当前 LUT");}finally{temp.delete();}
    }
    synchronized Entry find(String id)throws IOException {
        if(id==null||!id.matches("[0-9a-f]{64}"))return null;
        for(Entry entry:entries())if(entry.id.equals(id))return entry;return null;
    }
    private Entry read(File directory)throws IOException {
        directory=directory.getCanonicalFile();
        if(!directory.getName().matches("L[0-9A-F]{7}")||!root.equals(directory.getParentFile()))return null;
        File meta=new File(directory,"ENTRY.DAT"),cube=new File(directory,"TABLE.CUB");
        if(!meta.isFile()||!cube.isFile())return null;
        Properties p=readProperties(meta);int size;
        try{size=Integer.parseInt(p.getProperty("size","0"));}catch(NumberFormatException e){throw new IOException("LUT 索引损坏");}
        String id=p.getProperty("id","");if(!id.matches("[0-9a-f]{64}")||size<2||size>65)throw new IOException("LUT 索引损坏");
        return new Entry(id,label(p.getProperty("name")),label(p.getProperty("source")),size,cube);
    }
    synchronized List<Entry> entries()throws IOException {
        File[] dirs=root.listFiles();if(dirs==null)throw new IOException("无法读取 LUT 库");
        List<Entry> entries=new ArrayList<Entry>();for(File dir:dirs){Entry entry=read(dir);if(entry!=null)entries.add(entry);}
        Collections.sort(entries,new Comparator<Entry>(){public int compare(Entry a,Entry b){return a.name.compareToIgnoreCase(b.name);}});return entries;
    }
    private static String title(File file,String fallback)throws IOException {
        BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(file),"UTF-8"));
        try{for(int i=0;i<64;i++){String line=reader.readLine();if(line==null)break;line=line.replace("\ufeff","").trim();
            if(line.startsWith("TITLE ")||line.startsWith("TITLE\t")){String name=line.substring(5).trim();if(name.startsWith("\"")&&name.lastIndexOf('"')>0)name=name.substring(1,name.lastIndexOf('"'));return label(name);}
        }}finally{reader.close();}
        return label(fallback.replaceFirst("(?i)\\.(cube|cub)$",""));
    }
    static Properties readProperties(File file)throws IOException {Properties p=new Properties();InputStream in=new FileInputStream(file);try{p.load(in);}finally{in.close();}return p;}
    static void writeProperties(File file,Properties p)throws IOException {OutputStream out=new FileOutputStream(file);try{p.store(out,null);out.flush();}finally{out.close();}}
    static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
    private static void removeStage(File stage){if(!stage.exists())return;File[] files=stage.listFiles();if(files!=null)for(File f:files)f.delete();stage.delete();}
}
