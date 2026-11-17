package io.github.englandlobster.lutcamera;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Bounded journals and transactional export; owns only its named diagnostic files.
 * No file stays open between writes: Sony's power-off and auto power-off back up
 * the /data/data RAM disk and release the SD card first, and an open journal on
 * either made that fail and the camera cold-boot, losing native settings and
 * leaving the SD journal at 0 bytes (A6300 2.01, 2026-11-14 and 2026-11-17).
 * Events collect in memory and each flush opens, appends and closes. */
final class DiagnosticStore implements Closeable {
    static final int LIMIT=65536,FLUSH_BYTES=8192;
    /** Private-storage fallback, used only when the SD journal is unavailable: two
     * small files per role, since that storage is the camera's settings RAM disk. */
    static final int FALLBACK_LIMIT=8192,FALLBACK_COUNT=2;
    private final File directory;
    private final String role,header;
    private final int count,limit;
    private final ByteArrayOutputStream pending=new ByteArrayOutputStream();
    private boolean open;
    private int length;
    DiagnosticStore(File directory,String role,String header)throws IOException {this(directory,role,header,LIMIT,role.equals("CAP")?6:2);}
    DiagnosticStore(File directory,String role,String header,int limit,int count)throws IOException {
        if(!role.equals("CAP")&&!role.equals("UI"))throw new IOException("unknown log role");
        if(limit<1024||limit>LIMIT||count<1||count>(role.equals("CAP")?6:2))throw new IOException("invalid diagnostic bounds");
        this.directory=directory;this.role=role;this.header=header;this.limit=limit;this.count=count;
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("cannot create diagnostic directory");
        rotate();
    }
    private File file(int index){return new File(directory,role+index+".TXT");}
    private void rotate()throws IOException {
        flush(false);open=false;
        // A smaller fallback history also drops the older members a full one left.
        for(int i=(role.equals("CAP")?6:2)-1;i>=count-1;i--)if(file(i).exists()&&!file(i).delete())throw new IOException("cannot rotate diagnostic log");
        for(int i=count-2;i>=0;i--)if(file(i).exists()&&!file(i).renameTo(file(i+1)))throw new IOException("cannot rotate diagnostic log");
        byte[] bytes=(header+"\n").getBytes("UTF-8");
        if(bytes.length>8192)throw new IOException("diagnostic header too large");
        write(file(0),false,bytes,bytes.length,false);length=bytes.length;open=true;
    }
    private static void write(File file,boolean append,byte[] bytes,int size,boolean sync)throws IOException {
        FileOutputStream out=new FileOutputStream(file,append);
        try{out.write(bytes,0,size);if(sync)out.getFD().sync();}finally{out.close();}
    }
    /** Durable events and a full buffer are written at once; anything else waits for {@link #flush}. */
    synchronized void append(String line,boolean durable)throws IOException {
        byte[] bytes=(line+"\n").getBytes("UTF-8");if(bytes.length>32768)throw new IOException("diagnostic event too large");
        if(!open)throw new IOException("diagnostic writer closed");
        if(length+bytes.length>limit)rotate();
        pending.write(bytes,0,bytes.length);length+=bytes.length;
        if(durable||pending.size()>=FLUSH_BYTES)flush(durable);
    }
    synchronized boolean hasPending(){return pending.size()>0;}
    /** Removes this role's journals from a directory this process no longer writes. */
    static void remove(File directory,String role){
        File[] files=directory.listFiles();if(files==null)return;
        for(File file:files)if(owned(file.getName())&&file.getName().startsWith(role))file.delete();
    }
    /** How the previous session of this role ended, from the journal rotation
     * just moved to index 1. Before 2026-11-17 an SD journal still open at a cold
     * boot stayed at 0 bytes; a journal without "closed" whose header names
     * another boot was cut off by a cold boot, battery pull or crash. */
    synchronized String previousSession(String bootId){
        File previous=file(1);
        if(count<2||!previous.isFile())return "previous=none";
        long size=previous.length();
        if(size==0)return "previous=empty bytes=0";
        try{
            RandomAccessFile in=new RandomAccessFile(previous,"r");
            try{
                byte[] head=new byte[(int)Math.min(size,8192)];in.readFully(head);
                String first=new String(head,"UTF-8");int end=first.indexOf('\n');if(end>=0)first=first.substring(0,end);
                int tailSize=(int)Math.min(size,8192);byte[] tail=new byte[tailSize];in.seek(size-tailSize);in.readFully(tail);
                String text=new String(tail,"UTF-8");
                boolean closed=text.contains("\"event\":\"closed\"");
                String last=text.trim();int cut=last.lastIndexOf('\n');if(cut>=0)last=last.substring(cut+1);
                String sameBoot=bootId==null||first.indexOf("boot_id=")<0?"unknown":Boolean.toString(first.contains("boot_id="+bootId));
                return "previous=journal bytes="+size+" same_boot="+sameBoot+" closed="+closed
                    +" last_event="+field(last,"event")+" last_uptime_ms="+number(last,"uptime_ms")+" last_wall_ms="+number(last,"wall_ms");
            }finally{in.close();}
        }catch(IOException error){return "previous=unreadable error="+error.getClass().getSimpleName();}
    }
    private static String field(String json,String name){
        String key="\""+name+"\":\"";int at=json.indexOf(key);if(at<0)return "unknown";
        int end=json.indexOf('"',at+key.length());return end<0?"unknown":json.substring(at+key.length(),end);
    }
    private static String number(String json,String name){
        String key="\""+name+"\":";int at=json.indexOf(key);if(at<0)return "unknown";
        int end=at+key.length();while(end<json.length()&&Character.isDigit(json.charAt(end)))end++;
        return end==at+key.length()?"unknown":json.substring(at+key.length(),end);
    }
    /** Appends the buffered events and closes the file again. While the other
     * process exports this journal, Sony's SD stack refuses the open (EBUSY);
     * the events then stay buffered for the next flush. Any other failure drops them. */
    synchronized void flush(boolean sync)throws IOException {
        if(pending.size()==0)return;
        try{write(file(0),true,pending.toByteArray(),pending.size(),sync);}
        catch(FileNotFoundException error){if(busy(error)&&pending.size()<LIMIT)return;pending.reset();throw error;}
        catch(IOException error){pending.reset();throw error;}
        pending.reset();
    }
    public synchronized void close()throws IOException {if(open){try{flush(false);}finally{open=false;}}}
    synchronized void resume()throws IOException {
        if(open)return;
        if(!file(0).isFile())throw new IOException("active diagnostic log missing");
        if(file(0).length()>LIMIT)throw new IOException("active diagnostic log oversized");
        length=(int)file(0).length();open=true;
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
