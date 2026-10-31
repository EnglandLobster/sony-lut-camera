package org.hongen.sonylutprobe;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Bounded journals and transactional export; owns only its named diagnostic files. */
final class DiagnosticStore implements Closeable {
    static final int LIMIT=65536;
    private final File directory;
    private final String role,header;
    private final int count;
    private FileOutputStream output;
    private int length;
    DiagnosticStore(File directory,String role,String header)throws IOException {
        if(!role.equals("CAP")&&!role.equals("UI"))throw new IOException("unknown log role");
        this.directory=directory;this.role=role;this.header=header;count=role.equals("CAP")?6:2;
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("cannot create diagnostic directory");
        rotate();
    }
    private File file(int index){return new File(directory,role+index+".TXT");}
    private void rotate()throws IOException {
        close();
        if(file(count-1).exists()&&!file(count-1).delete())throw new IOException("cannot rotate diagnostic log");
        for(int i=count-2;i>=0;i--)if(file(i).exists()&&!file(i).renameTo(file(i+1)))throw new IOException("cannot rotate diagnostic log");
        output=new FileOutputStream(file(0));byte[] bytes=(header+"\n").getBytes("UTF-8");
        if(bytes.length>8192)throw new IOException("diagnostic header too large");output.write(bytes);length=bytes.length;
    }
    synchronized void append(String line,boolean durable)throws IOException {
        byte[] bytes=(line+"\n").getBytes("UTF-8");if(bytes.length>32768)throw new IOException("diagnostic event too large");
        if(length+bytes.length>LIMIT)rotate();
        if(output==null)throw new IOException("diagnostic writer closed");
        output.write(bytes);length+=bytes.length;if(durable)output.getFD().sync();
    }
    public synchronized void close()throws IOException {if(output!=null){try{output.close();}finally{output=null;}}}
    synchronized void resume()throws IOException {
        if(output!=null)return;
        if(!file(0).isFile())throw new IOException("active diagnostic log missing");
        if(file(0).length()>LIMIT)throw new IOException("active diagnostic log oversized");
        length=(int)file(0).length();output=new FileOutputStream(file(0),true);
    }
    static boolean owned(String name){return name.matches("CAP[0-5]\\.TXT|UI[0-1]\\.TXT");}
    private static void add(ZipOutputStream zip,String name,File file)throws IOException {
        if(file.length()>LIMIT)throw new IOException("oversized diagnostic log");
        // Never follow a link outside the selected diagnostics directory.
        if(!file.getCanonicalFile().getParentFile().equals(file.getParentFile().getCanonicalFile()))throw new IOException("diagnostic path outside directory");
        zip.putNextEntry(new ZipEntry(name));InputStream in=new FileInputStream(file);
        try{byte[] buffer=new byte[4096];int total=0,n;while((n=in.read(buffer))!=-1){total+=n;if(total>LIMIT)throw new IOException("growing diagnostic log");zip.write(buffer,0,n);}}
        finally{in.close();}zip.closeEntry();
    }
    static File export(File internal,File sd,File destination,String summary)throws IOException {
        if(!destination.isDirectory()&&!destination.mkdirs())throw new IOException("cannot create SD diagnostic export directory");
        File temp=new File(destination,"REPORT.TMP"),target=new File(destination,"REPORT.ZIP"),previous=new File(destination,"PREV.ZIP");
        if(temp.exists()&&!temp.delete())throw new IOException("cannot remove incomplete report");
        int logs=0;
        try{
            FileOutputStream raw=new FileOutputStream(temp);ZipOutputStream zip=new ZipOutputStream(raw);
            try{
                zip.putNextEntry(new ZipEntry("SUMMARY.TXT"));zip.write(summary.getBytes("UTF-8"));zip.closeEntry();
                File[] roots={internal,sd};String[] prefixes={"internal/","sd/"};
                for(int i=0;i<roots.length;i++){
                    File[] files=roots[i].listFiles();if(files==null)continue;
                    Arrays.sort(files);for(File file:files)if(file.isFile()&&owned(file.getName())){add(zip,prefixes[i]+file.getName(),file);logs++;}
                }
                if(logs==0)throw new IOException("no readable diagnostic logs");zip.finish();raw.getFD().sync();
            }finally{zip.close();}
            // Read every member before publishing the archive, including its CRC.
            verify(temp);
            if(previous.exists()&&!previous.delete())throw new IOException("cannot replace previous report");
            if(target.exists()&&!target.renameTo(previous))throw new IOException("cannot preserve previous report");
            if(!temp.renameTo(target)){
                if(previous.exists()&&!previous.renameTo(target))throw new IOException("report publish failed; previous report retained as PREV.ZIP");
                throw new IOException("cannot publish diagnostic report");
            }
            return target;
        }finally{if(temp.exists())temp.delete();}
    }
    static int verify(File file)throws IOException {
        ZipFile catalog=new ZipFile(file);try{if(catalog.size()<2)throw new IOException("report contains no events");}finally{catalog.close();}
        ZipInputStream in=new ZipInputStream(new FileInputStream(file));int entries=0,total=0;byte[] buffer=new byte[4096];
        try{ZipEntry entry;while((entry=in.getNextEntry())!=null){
            String name=entry.getName();if(!name.equals("SUMMARY.TXT")&&!name.matches("(internal|sd)/(CAP[0-5]|UI[0-1])\\.TXT"))throw new IOException("unexpected report member");
            int n;while((n=in.read(buffer))!=-1){total+=n;if(total>2*1024*1024)throw new IOException("report too large");}in.closeEntry();entries++;
        }}finally{in.close();}if(entries<2)throw new IOException("report contains no events");return entries;
    }
}
