package org.hongen.sonylutprobe;

import java.io.IOException;
import java.nio.file.Files;
import java.io.File;
import java.util.*;

public final class CaptureSetupTest {
    static class Driver implements SimpleExposure.Driver {
        final Map<String,String> values=new LinkedHashMap<String,String>();
        boolean reject,partial;int writes;
        Driver(){values.put("drive-mode","burst");values.put("drive-mode-values","single,burst,bracket");
            values.put("storage-fmt","raw+jpeg");values.put("picture-size","3008x2000");
            values.put("picture-size-values","6000x4000,3008x2000");values.put("jpeg-quality","50");values.put("whitebalance","daylight");}
        public Map<String,String> read(){return new LinkedHashMap<String,String>(values);}
        public void write(Map<String,String> patch)throws Exception{
            writes++;if(partial){values.put("drive-mode",patch.get("drive-mode"));throw new IOException("vendor partially rejected");}
            values.putAll(patch);if(reject)values.put("picture-size","3008x2000");
        }
    }
    static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Driver d=new Driver();Map<String,String> baseline=d.read();CaptureSetup setup=new CaptureSetup(d);
        setup.open();check("single".equals(d.values.get("drive-mode"))&&"jpeg".equals(d.values.get("storage-fmt"))&&"6000x4000".equals(d.values.get("picture-size")),"RAW/burst/small input normalized");
        check("daylight".equals(d.values.get("whitebalance")),"unrelated field preserved");
        setup.close();check(d.read().equals(baseline),"original settings restored");
        d.partial=true;boolean failed=false;try{setup.open();}catch(IOException e){failed=true;}
        check(failed,"partial vendor failure surfaced");d.partial=false;setup.close();check(d.read().equals(baseline),"partial write rollback");
        d.reject=true;failed=false;try{setup.open();}catch(IOException e){failed=true;}
        check(failed,"readback mismatch surfaced");d.reject=false;setup.close();check(d.read().equals(baseline),"mismatch rollback");
        d.values.put("picture-size-values","3008x2000");int writes=d.writes;failed=false;
        try{setup.open();}catch(IOException e){failed=true;}check(failed&&d.writes==writes,"unsupported size rejected before writing");
        d.values.put("drive-mode","single");d.values.put("storage-fmt","jpeg");d.values.put("picture-size","6000x4000");
        d.values.remove("picture-size-values");setup.open();setup.close();check(d.writes==writes,"already-correct session untouched");
        File sd=Files.createTempDirectory("capture-diagnostics-").toFile();
        try{
            CaptureDiagnostics report=new CaptureDiagnostics(sd);report.append("platform memory=unknown");report.append("camera_open_begin");report.failure(new IOException("unsupported camera"));
            File file=new File(sd,"SONYLUT/DIAG/INIT.TXT");String log=new String(Files.readAllBytes(file.toPath()),"UTF-8");
            check(log.contains("camera_open_begin")&&log.contains("IOException: unsupported camera"),"last stage and error persisted");
            char[] many=new char[70000];Arrays.fill(many,'x');report.append(new String(many));report.append("extra");check(file.length()<=CaptureDiagnostics.MAX_BYTES,"bounded diagnostic");
            new CaptureDiagnostics(sd).append("new attempt");check(new String(Files.readAllBytes(file.toPath()),"UTF-8").equals("new attempt\n"),"attempt reset");
        }finally{delete(sd);}
        System.out.println("PASS capture startup: inherited RAW/burst/size, rollback on partial writes and readback mismatch, unsupported settings, bounded persistent diagnostics");
    }
    static void delete(File f){File[] children=f.listFiles();if(children!=null)for(File child:children)delete(child);f.delete();}
}
