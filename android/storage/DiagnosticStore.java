package io.github.englandlobster.lutcamera;

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
    /** Opens one journal for export; tests substitute Sony's EBUSY refusal. */
    interface LogOpener {InputStream open(File file)throws IOException;}
    private static final LogOpener FILES=new LogOpener(){public InputStream open(File file)throws IOException{return new FileInputStream(file);}};
    static final long BUSY_BUDGET_MS=2000,BUSY_RETRY_MS=250;
    /** Sony's SD stack refuses to open a file another process still writes:
     * FileNotFoundException "(Device or resource busy)" on API10, "EBUSY" later. */
    static boolean busy(FileNotFoundException error){
        String message=error.getMessage();
        return message!=null&&(message.indexOf("EBUSY")>=0||message.indexOf("Device or resource busy")>=0);
    }
    /** Null only when the file stayed busy until the export-wide deadline;
     * every other open failure propagates and aborts the export as before. */
    private static InputStream openLog(File file,LogOpener opener,long deadline,long retryMs)throws IOException {
        while(true){
            try{return opener.open(file);}
            catch(FileNotFoundException error){
                if(!busy(error))throw error;
                long remaining=(deadline-System.nanoTime())/1000000;
                if(remaining<=0)return null;
                try{Thread.sleep(Math.min(retryMs,remaining));}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("interrupted while waiting for diagnostic log",interrupted);}
            }
        }
    }
    private static boolean add(ZipOutputStream zip,String name,File file,LogOpener opener,long deadline,long retryMs)throws IOException {
        if(file.length()>LIMIT)throw new IOException("oversized diagnostic log");
        // Never follow a link outside the selected diagnostics directory.
        if(!file.getCanonicalFile().getParentFile().equals(file.getParentFile().getCanonicalFile()))throw new IOException("diagnostic path outside directory");
        InputStream in=openLog(file,opener,deadline,retryMs);if(in==null)return false;
        try{
            zip.putNextEntry(new ZipEntry(name));
            byte[] buffer=new byte[4096];int total=0,n;while((n=in.read(buffer))!=-1){total+=n;if(total>LIMIT)throw new IOException("growing diagnostic log");zip.write(buffer,0,n);}
        }finally{in.close();}
        zip.closeEntry();return true;
    }
    static File export(File internal,File sd,File destination,String summary,List<String> skipped)throws IOException {
        return export(internal,sd,destination,summary,skipped,FILES,BUSY_BUDGET_MS,BUSY_RETRY_MS);
    }
    /** A journal another process (the capture process) still holds is retried
     * within one export-wide budget, then omitted and named in SUMMARY.TXT and
     * in skipped. The export lock pauses this process's event appends, so the
     * budget is shared rather than per file. */
    static File export(File internal,File sd,File destination,String summary,List<String> skipped,LogOpener opener,long busyBudgetMs,long retryMs)throws IOException {
        if(!destination.isDirectory()&&!destination.mkdirs())throw new IOException("cannot create SD diagnostic export directory");
        File temp=new File(destination,"REPORT.TMP"),target=new File(destination,"REPORT.ZIP"),previous=new File(destination,"PREV.ZIP");
        if(temp.exists()&&!temp.delete())throw new IOException("cannot remove incomplete report");
        List<String> busy=new ArrayList<String>();int logs=0;
        long deadline=System.nanoTime()+busyBudgetMs*1000000L;
        try{
            FileOutputStream raw=new FileOutputStream(temp);ZipOutputStream zip=new ZipOutputStream(raw);
            try{
                File[] roots={internal,sd};String[] prefixes={"internal/","sd/"};
                for(int i=0;i<roots.length;i++){
                    File[] files=roots[i].listFiles();if(files==null)continue;
                    Arrays.sort(files);for(File file:files)if(file.isFile()&&owned(file.getName())){
                        String name=prefixes[i]+file.getName();
                        if(add(zip,name,file,opener,deadline,retryMs))logs++;else busy.add(name);
                    }
                }
                if(logs==0)throw new IOException("no readable diagnostic logs");
                // Written last so it can name the journals left out.
                StringBuilder text=new StringBuilder(summary);if(text.length()>0&&text.charAt(text.length()-1)!='\n')text.append('\n');
                text.append("skipped_busy=").append(busy.isEmpty()?"none":join(busy)).append('\n');
                zip.putNextEntry(new ZipEntry("SUMMARY.TXT"));zip.write(text.toString().getBytes("UTF-8"));zip.closeEntry();
                zip.finish();raw.getFD().sync();
            }finally{zip.close();}
            // Read every member before publishing the archive, including its CRC.
            verify(temp);
            if(previous.exists()&&!previous.delete())throw new IOException("cannot replace previous report");
            if(target.exists()&&!target.renameTo(previous))throw new IOException("cannot preserve previous report");
            if(!temp.renameTo(target)){
                if(previous.exists()&&!previous.renameTo(target))throw new IOException("report publish failed; previous report retained as PREV.ZIP");
                throw new IOException("cannot publish diagnostic report");
            }
            if(skipped!=null)skipped.addAll(busy);
            return target;
        }finally{if(temp.exists())temp.delete();}
    }
    static String join(List<String> names){
        StringBuilder out=new StringBuilder();for(String name:names){if(out.length()>0)out.append(',');out.append(name);}return out.toString();
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
