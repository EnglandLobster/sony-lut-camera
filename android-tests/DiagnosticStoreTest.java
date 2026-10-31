package org.hongen.sonylutprobe;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class DiagnosticStoreTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static String read(File f)throws Exception{return new String(Files.readAllBytes(f.toPath()),"UTF-8");}
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
        ui.close();File report=DiagnosticStore.export(local,sd,dest,"summary");ui.resume();ui.append("after export",true);
        check(read(new File(local,"UI0.TXT")).contains("export\nafter export"),"resume appends without replacing prior events");check(DiagnosticStore.verify(report)==8,"summary plus seven log members");
        ZipFile zip=new ZipFile(report);check(zip.getEntry("internal/PRIVATE.JPG")==null&&zip.getEntry("internal/wifi.txt")==null,"never collect unrelated files");zip.close();
        byte[] previous=Files.readAllBytes(report.toPath());
        File external=new File(root,"external.TXT");Files.write(external.toPath(),"secret".getBytes("UTF-8"));sd.mkdirs();Files.createSymbolicLink(new File(sd,"CAP0.TXT").toPath(),external.toPath());
        rejected=false;try{DiagnosticStore.export(local,sd,dest,"summary");}catch(IOException expected){rejected=true;}
        check(rejected&&Arrays.equals(previous,Files.readAllBytes(report.toPath())),"failed export preserves last report; outside symlinks rejected");
        Files.delete(new File(sd,"CAP0.TXT").toPath());
        DiagnosticStore.export(local,sd,dest,"next summary");check(Arrays.equals(previous,Files.readAllBytes(new File(dest,"PREV.ZIP").toPath())),"keep previous export");
        byte[] valid=Files.readAllBytes(report.toPath());Files.write(new File(dest,"BROKEN.ZIP").toPath(),Arrays.copyOf(valid,valid.length-20));
        rejected=false;try{DiagnosticStore.verify(new File(dest,"BROKEN.ZIP"));}catch(IOException expected){rejected=true;}check(rejected,"reject truncated ZIP central directory");
        check(DiagnosticText.clean("file=/storage/sdcard0/private/image.JPG").equals("file=<path>"),"absolute path redaction");
        check(DiagnosticText.quote("a\n\"b\\").equals("\"a\\n\\\"b\\\\\""),"JSON escaping");ui.close();
        System.out.println("PASS diagnostics: rotation, interrupted session retention, unavailable storage, export allowlist, atomic failure, previous export and ZIP validation");
    }
}
