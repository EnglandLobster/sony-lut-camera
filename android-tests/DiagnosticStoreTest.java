package io.github.englandlobster.lutcamera;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class DiagnosticStoreTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static String read(File f)throws Exception{return new String(Files.readAllBytes(f.toPath()),"UTF-8");}
    static String member(File zip,String name)throws Exception{
        ZipFile z=new ZipFile(zip);try{ZipEntry e=z.getEntry(name);if(e==null)return null;
            InputStream in=z.getInputStream(e);try{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),"UTF-8");}finally{in.close();}
        }finally{z.close();}
    }
    /** Fails opens of the named journals like Sony's SD stack while another process writes them. */
    static final class Refusing implements DiagnosticStore.LogOpener {
        final Map<String,Integer> busyTimes=new HashMap<String,Integer>();final Map<String,String> message=new HashMap<String,String>();
        public InputStream open(File file)throws IOException{
            String key=file.getParentFile().getName()+"/"+file.getName();
            if(message.containsKey(key))throw new FileNotFoundException(file.getPath()+message.get(key));
            Integer left=busyTimes.get(key);
            if(left!=null&&left.intValue()!=0){busyTimes.put(key,left.intValue()-1);throw new FileNotFoundException(file.getPath()+" (Device or resource busy)");}
            return new FileInputStream(file);
        }
    }
    static File journals(File root)throws Exception{
        File local=new File(root,"internal"),sd=new File(root,"sd");check(local.mkdirs()&&sd.mkdirs(),"busy fixture");
        Files.write(new File(local,"UI0.TXT").toPath(),"ui\n".getBytes("UTF-8"));
        Files.write(new File(sd,"CAP0.TXT").toPath(),"cap0\n".getBytes("UTF-8"));Files.write(new File(sd,"CAP1.TXT").toPath(),"cap1\n".getBytes("UTF-8"));
        return root;
    }
    /** The capture process can hold its active CAP journal: retry within one
     * export-wide budget, then publish the rest and name what was omitted. */
    static void busyJournals(File root)throws Exception{
        journals(root);File local=new File(root,"internal"),sd=new File(root,"sd"),dest=new File(root,"exports");
        Refusing brief=new Refusing();brief.busyTimes.put("sd/CAP0.TXT",2);List<String> skipped=new ArrayList<String>();
        File report=DiagnosticStore.export(local,sd,dest,"summary",skipped,brief,2000,5);
        check(skipped.isEmpty()&&"cap0\n".equals(member(report,"sd/CAP0.TXT")),"a briefly busy journal is retried and included");
        check(member(report,"SUMMARY.TXT").equals("summary\nskipped_busy=none\n"),"summary records no omission: "+member(report,"SUMMARY.TXT"));

        Refusing held=new Refusing();held.busyTimes.put("sd/CAP0.TXT",-1);held.busyTimes.put("sd/CAP1.TXT",-1);
        long begin=System.nanoTime();report=DiagnosticStore.export(local,sd,dest,"summary",skipped,held,500,20);long ms=(System.nanoTime()-begin)/1000000;
        check(skipped.equals(Arrays.asList("sd/CAP0.TXT","sd/CAP1.TXT")),"held journals are skipped and named: "+skipped);
        check(member(report,"sd/CAP0.TXT")==null&&member(report,"sd/CAP1.TXT")==null&&"ui\n".equals(member(report,"internal/UI0.TXT")),"remaining journals still publish");
        check(member(report,"SUMMARY.TXT").endsWith("skipped_busy=sd/CAP0.TXT,sd/CAP1.TXT\n"),"the report itself names the omitted journals");
        check(ms>=450&&ms<950,"one shared busy budget, not one per file: "+ms+" ms");
        check(DiagnosticStore.busy(new FileNotFoundException("/x/CAP0.TXT: open failed: EBUSY (Device or resource busy)")),"newer libcore EBUSY text");
        check(!DiagnosticStore.busy(new FileNotFoundException("/x/CAP0.TXT (Permission denied)")),"permission errors are not busy");

        byte[] published=Files.readAllBytes(report.toPath());
        Refusing denied=new Refusing();denied.message.put("sd/CAP0.TXT"," (Permission denied)");skipped.clear();boolean rejected=false;
        try{DiagnosticStore.export(local,sd,dest,"summary",skipped,denied,2000,5);}catch(FileNotFoundException expected){rejected=expected.getMessage().contains("Permission denied");}
        check(rejected&&skipped.isEmpty()&&Arrays.equals(published,Files.readAllBytes(report.toPath())),"a non-busy open failure still aborts the export and keeps the last report");

        Refusing all=new Refusing();for(String key:new String[]{"internal/UI0.TXT","sd/CAP0.TXT","sd/CAP1.TXT"})all.busyTimes.put(key,-1);rejected=false;
        try{DiagnosticStore.export(local,sd,dest,"summary",skipped,all,50,10);}catch(IOException expected){rejected="no readable diagnostic logs".equals(expected.getMessage());}
        check(rejected&&skipped.isEmpty()&&Arrays.equals(published,Files.readAllBytes(report.toPath())),"every journal busy fails explicitly and keeps the last report");
    }
    /** Paths of this JVM's open file descriptors (Linux /proc). */
    static List<String> openPaths()throws Exception{
        List<String> paths=new ArrayList<String>();File[] fds=new File("/proc/self/fd").listFiles();check(fds!=null,"/proc/self/fd unavailable");
        for(File fd:fds)try{paths.add(Files.readSymbolicLink(fd.toPath()).toString());}catch(IOException closedMeanwhile){}
        return paths;
    }
    static boolean held(File dir)throws Exception{String prefix=dir.getCanonicalPath();for(String p:openPaths())if(p.startsWith(prefix))return true;return false;}
    /** Sony's power-off backup fails while any journal is open, so a writer
     * must not keep one open between events, buffered or durable. */
    static void noHeldFiles(File dir)throws Exception{
        DiagnosticStore store=new DiagnosticStore(dir,"CAP","held-session");
        check(!held(dir),"header written and closed");
        store.append("buffered",false);
        check(!held(dir)&&store.hasPending()&&!read(new File(dir,"CAP0.TXT")).contains("buffered"),"a plain event waits in memory without an open file");
        store.flush(false);
        check(!held(dir)&&!store.hasPending()&&read(new File(dir,"CAP0.TXT")).equals("held-session\nbuffered\n"),"flush appends and closes");
        store.append("store_wait",true);
        check(!held(dir)&&!store.hasPending()&&read(new File(dir,"CAP0.TXT")).endsWith("buffered\nstore_wait\n"),"a durable event is written at once and closed");
        String block=String.join("",Collections.nCopies(1000,"x"));for(int i=0;i<9;i++)store.append(block,false);
        check(!held(dir)&&read(new File(dir,"CAP0.TXT")).contains(block),"a full buffer is written without waiting");
        store.close();boolean rejected=false;try{store.append("late",false);}catch(IOException expected){rejected=true;}
        check(rejected&&!held(dir),"a closed writer refuses events");
        store.resume();store.append("resumed",true);check(read(new File(dir,"CAP0.TXT")).endsWith("resumed\n")&&!held(dir),"resume appends to the active journal");
    }
    static String header(String boot){return "{\"event\":\"session_header\",\"detail\":\"app=x boot_id="+boot+"\"}";}
    static String event(String name,long uptime){return "{\"seq\":1,\"wall_ms\":1700000000000,\"uptime_ms\":"+uptime+",\"event\":\""+name+"\",\"detail\":\"\"}";}
    /** The private fallback stays small and drops a full history's older members;
     * the next session reports how the previous one ended. */
    static void fallbackAndPrevious(File dir)throws Exception{
        check(dir.mkdirs(),"fallback fixture");
        for(int i=0;i<6;i++)Files.write(new File(dir,"CAP"+i+".TXT").toPath(),new byte[60000]);
        DiagnosticStore store=new DiagnosticStore(dir,"CAP",header("boot-a"),DiagnosticStore.FALLBACK_LIMIT,DiagnosticStore.FALLBACK_COUNT);
        check(!new File(dir,"CAP2.TXT").exists()&&!new File(dir,"CAP5.TXT").exists()&&new File(dir,"CAP1.TXT").exists(),"a fallback keeps two members");
        check(store.previousSession("boot-a").startsWith("previous=journal bytes=60000 same_boot=unknown"),"a header without boot id is unknown: "+store.previousSession("boot-a"));
        String block=String.join("",Collections.nCopies(1000,"y"));for(int i=0;i<30;i++)store.append(block,false);store.close();
        for(File f:dir.listFiles())check(f.length()<=DiagnosticStore.FALLBACK_LIMIT+1100,"fallback members stay small: "+f.length());
        boolean rejected=false;try{new DiagnosticStore(dir,"UI",header("x"),DiagnosticStore.FALLBACK_LIMIT,3);}catch(IOException expected){rejected=true;}check(rejected,"bounds are checked");

        File sd=new File(dir,"sd");
        DiagnosticStore first=new DiagnosticStore(sd,"CAP",header("boot-a"));first.append(event("armed",500),false);first.append(event("closed",900),true);first.close();
        String report=new DiagnosticStore(sd,"CAP",header("boot-a")).previousSession("boot-a");
        check(report.equals("previous=journal bytes="+new File(sd,"CAP1.TXT").length()+" same_boot=true closed=true last_event=closed last_uptime_ms=900 last_wall_ms=1700000000000"),"a closed session: "+report);
        DiagnosticStore cut=new DiagnosticStore(sd,"CAP",header("boot-a"));cut.append(event("store_wait",1200),true);
        report=new DiagnosticStore(sd,"CAP",header("boot-b")).previousSession("boot-b");
        check(report.contains("same_boot=false closed=false last_event=store_wait last_uptime_ms=1200"),"a session cut off by another boot: "+report);
        Files.write(new File(sd,"CAP0.TXT").toPath(),new byte[0]);
        check(new DiagnosticStore(sd,"CAP",header("boot-c")).previousSession("boot-c").equals("previous=empty bytes=0"),"a 0-byte journal from an older version");

        File internal=new File(dir,"internal");check(internal.mkdirs(),"internal fixture");
        for(String name:new String[]{"CAP0.TXT","CAP5.TXT","UI0.TXT","PRIVATE.TXT"})Files.write(new File(internal,name).toPath(),new byte[]{1});
        DiagnosticStore.remove(internal,"CAP");
        check(!new File(internal,"CAP0.TXT").exists()&&!new File(internal,"CAP5.TXT").exists()&&new File(internal,"UI0.TXT").exists()&&new File(internal,"PRIVATE.TXT").exists(),"remove clears only this role's journals");
    }
    public static void main(String[] args)throws Exception {
        File root=Files.createTempDirectory("diagnostic-store").toFile(),local=new File(root,"internal"),sd=new File(root,"sd"),dest=new File(root,"exports");
        DiagnosticStore first=new DiagnosticStore(local,"CAP","first-session");first.append("camera_open_begin",true);first.close();
        DiagnosticStore second=new DiagnosticStore(local,"CAP","second-session");second.append("retry",false);
        check(read(new File(local,"CAP1.TXT")).contains("camera_open_begin"),"retry must preserve interrupted previous attempt");
        String block=String.join("",Collections.nCopies(6000,"界"));for(int i=0;i<50;i++)second.append(block,false);
        second.append("failed: last error",true);second.close();
        check(read(new File(local,"CAP0.TXT")).contains("last error"),"rotation must preserve latest failure");
        int count=0;for(File f:local.listFiles()){check(f.length()<=DiagnosticStore.LIMIT,"bounded member");count++;}check(count==6,"bounded capture history");
        File unavailable=new File(root,"unavailable");Files.write(unavailable.toPath(),new byte[]{1});boolean rejected=false;
        try{new DiagnosticStore(unavailable,"UI","header");}catch(IOException expected){rejected=true;}check(rejected,"unavailable storage is explicit");
        DiagnosticStore ui=new DiagnosticStore(local,"UI","ui-session");ui.append("export",false);
        Files.write(new File(local,"PRIVATE.JPG").toPath(),new byte[]{1,2,3});Files.write(new File(local,"wifi.txt").toPath(),new byte[]{4});
        ui.close();File report=DiagnosticStore.export(local,sd,dest,"summary",null);ui.resume();ui.append("after export",true);
        check(read(new File(local,"UI0.TXT")).contains("export\nafter export"),"resume appends without replacing prior events");check(DiagnosticStore.verify(report)==8,"summary plus seven log members");
        ZipFile zip=new ZipFile(report);check(zip.getEntry("internal/PRIVATE.JPG")==null&&zip.getEntry("internal/wifi.txt")==null,"never collect unrelated files");zip.close();
        byte[] previous=Files.readAllBytes(report.toPath());
        File external=new File(root,"external.TXT");Files.write(external.toPath(),"secret".getBytes("UTF-8"));sd.mkdirs();Files.createSymbolicLink(new File(sd,"CAP0.TXT").toPath(),external.toPath());
        rejected=false;try{DiagnosticStore.export(local,sd,dest,"summary",null);}catch(IOException expected){rejected=true;}
        check(rejected&&Arrays.equals(previous,Files.readAllBytes(report.toPath())),"failed export preserves last report; outside symlinks rejected");
        Files.delete(new File(sd,"CAP0.TXT").toPath());
        DiagnosticStore.export(local,sd,dest,"next summary",null);check(Arrays.equals(previous,Files.readAllBytes(new File(dest,"PREV.ZIP").toPath())),"keep previous export");
        byte[] valid=Files.readAllBytes(report.toPath());Files.write(new File(dest,"BROKEN.ZIP").toPath(),Arrays.copyOf(valid,valid.length-20));
        rejected=false;try{DiagnosticStore.verify(new File(dest,"BROKEN.ZIP"));}catch(IOException expected){rejected=true;}check(rejected,"reject truncated ZIP central directory");
        check(DiagnosticText.clean("file=/storage/sdcard0/private/image.JPG").equals("file=<path>"),"absolute path redaction");
        check(DiagnosticText.quote("a\n\"b\\").equals("\"a\\n\\\"b\\\\\""),"JSON escaping");ui.close();
        busyJournals(new File(root,"busy"));
        noHeldFiles(new File(root,"held"));
        fallbackAndPrevious(new File(root,"fallback"));
        System.out.println("PASS diagnostics: rotation, interrupted session retention, unavailable storage, export allowlist, atomic failure, previous export, busy journals, ZIP validation, no journal held open between writes, small private fallback and previous-session summary");
    }
}
