package org.hongen.sonylutprobe;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class AssetsTest {
    public static void main(String[] args) throws Exception {
        File root=Files.createTempDirectory("sony-assets-").toFile();
        try {
            byte[] expected=new byte[70000];new Random(7).nextBytes(expected);
            File apk=new File(root,"app.apk"),cache=new File(root,"cache");cache.mkdir();
            ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(apk));
            zip.putNextEntry(new ZipEntry("assets/TEST.JPG"));zip.write(expected);zip.closeEntry();zip.close();
            File target=new File(cache,"TEST.JPG");
            Files.write(target.toPath(),Arrays.copyOf(expected,123));
            Assets.extract(apk,"TEST.JPG",cache);
            if(!Arrays.equals(expected,Files.readAllBytes(target.toPath())))throw new AssertionError("truncated cache not repaired");
            byte[] corrupt=expected.clone();corrupt[300]^=1;Files.write(target.toPath(),corrupt);
            Assets.extract(apk,"TEST.JPG",cache);
            if(!Arrays.equals(expected,Files.readAllBytes(target.toPath())))throw new AssertionError("CRC mismatch not repaired");
            long modified=target.lastModified();Assets.extract(apk,"TEST.JPG",cache);
            if(target.lastModified()!=modified)throw new AssertionError("valid cache rewritten");
            Files.write(apk.toPath(),new byte[]{1,2,3});
            boolean failed=false;try{Assets.extract(apk,"TEST.JPG",cache);}catch(IOException e){failed=true;}
            if(!failed || !Arrays.equals(expected,Files.readAllBytes(target.toPath())))throw new AssertionError("invalid APK replaced valid cache");
            if(cache.list().length!=1)throw new AssertionError("temporary file leaked");
            System.out.println("PASS: partial and same-size corrupted cache repaired; verified cache reused; invalid APK preserves cache");
        } finally {delete(root);}
    }
    private static void delete(File file) {File[] children=file.listFiles();if(children!=null)for(File c:children)delete(c);file.delete();}
}
