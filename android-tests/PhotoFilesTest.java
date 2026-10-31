package org.hongen.sonylutprobe;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class PhotoFilesTest {
    public static void main(String[] args) throws Exception {
        File sd=Files.createTempDirectory("sony-photo-test-").toFile();
        try {
            File dir=new File(sd,"DCIM/100MSDCF");dir.mkdirs();byte[] jpeg={(byte)255,(byte)216,(byte)255,(byte)218,0,2,1,2,(byte)255,0,3,(byte)255,(byte)208,4,(byte)255,(byte)217};
            File old=new File(dir,"DSC00001.JPG");Files.write(old.toPath(),jpeg);
            PhotoFiles photos=new PhotoFiles(sd);if(photos.pollReady()!=null)throw new AssertionError("old photo selected");
            File fresh=new File(dir,"DSC00002.JPG");Files.write(fresh.toPath(),Arrays.copyOf(jpeg,4));
            if(photos.pollReady()!=null||photos.pollReady()!=null)throw new AssertionError("partial JPEG accepted");
            Files.write(fresh.toPath(),jpeg);if(photos.pollReady()!=null)throw new AssertionError("changing length accepted");
            if(!fresh.getCanonicalFile().equals(photos.pollReady()))throw new AssertionError("complete stable photo missed");
            photos.accepted(fresh.getCanonicalFile());if(photos.pollReady()!=null)throw new AssertionError("photo processed twice");
            File padded=new File(dir,"DSC00005.JPG");Files.write(padded.toPath(),Arrays.copyOf(jpeg,32768));
            if(photos.pollReady()!=null||!padded.getCanonicalFile().equals(photos.pollReady()))throw new AssertionError("Sony padded JPEG missed");
            photos.accepted(padded.getCanonicalFile());
            File confirmed=new File(dir,"DSC00008.JPG");Files.write(confirmed.toPath(),jpeg);
            photos.confirmStored("DCIM/100MSDCF/","DSC00001");
            if(photos.pollReady(true)!=null||photos.pollReady(true)!=null)throw new AssertionError("unrelated store notification accepted");
            photos.confirmStored("DCIM/100MSDCF/","DSC00008");
            if(!confirmed.getCanonicalFile().equals(photos.pollReady(true)))throw new AssertionError("matching stable store notification missed");
            photos.accepted(confirmed.getCanonicalFile());if(photos.pollReady(true)!=null)throw new AssertionError("stored photo processed twice");
            if(!photos.knownStore("DCIM/100MSDCF/","DSC00008"))throw new AssertionError("duplicate completion not recognized");
            File next=new File(dir,"DSC00009.JPG");Files.write(next.toPath(),jpeg);
            if(photos.knownStore("DCIM/100MSDCF/","DSC00009"))throw new AssertionError("new completion marked duplicate");
            photos.confirmStored("DCIM/100MSDCF/","DSC00009");
            photos.confirmStored("DCIM/100MSDCF/","DSC00008");
            if(!next.getCanonicalFile().equals(photos.pollReady(true)))throw new AssertionError("late duplicate replaced current saved identity");
            photos.accepted(next.getCanonicalFile());
            boolean badPath=false;try{photos.confirmStored("DCIM/../OTHER/","DSC00009");}catch(IOException e){badPath=true;}
            if(!badPath)throw new AssertionError("unsafe completion path accepted");
            File thumbnail=new File(dir,"DSC00006.JPG");
            byte[] embedded={(byte)255,(byte)216,(byte)255,(byte)225,0,6,(byte)255,(byte)216,(byte)255,(byte)217,(byte)255,(byte)218,0,2,1,2};
            Files.write(thumbnail.toPath(),embedded);
            if(photos.pollReady()!=null||photos.pollReady()!=null)throw new AssertionError("thumbnail EOI accepted as complete image");
            thumbnail.delete();
            File boundary=new File(dir,"BOUNDARY.JPG");byte[] edge=new byte[65540];
            System.arraycopy(jpeg,0,edge,0,6);edge[65535]=(byte)255;edge[65536]=0;edge[65538]=(byte)255;edge[65539]=(byte)217;
            Files.write(boundary.toPath(),edge);if(!PhotoFiles.completeJpeg(boundary))throw new AssertionError("stuffed FF at buffer boundary missed");
            edge[65536]=(byte)217;Files.write(boundary.toPath(),Arrays.copyOf(edge,65537));
            if(!PhotoFiles.completeJpeg(boundary))throw new AssertionError("EOI at buffer boundary missed");
            Files.write(boundary.toPath(),Arrays.copyOf(edge,65536));if(PhotoFiles.completeJpeg(boundary))throw new AssertionError("truncated boundary marker accepted");
            boundary.delete();
            Files.write(new File(dir,"DSC00003.JPG").toPath(),jpeg);Files.write(new File(dir,"DSC00004.JPG").toPath(),jpeg);
            boolean failed=false;try{photos.pollReady();}catch(IOException e){failed=true;}if(!failed)throw new AssertionError("ambiguous burst accepted");
            if(!Arrays.equals(jpeg,Files.readAllBytes(old.toPath())))throw new AssertionError("original modified");
            for(String path:args)if(!PhotoFiles.completeJpeg(new File(path)))throw new AssertionError("real JPEG rejected: "+path);
            System.out.println("PASS: old photos ignored; partial/changing JPEG deferred; new stable JPEG selected once; padded Sony JPEG accepted; embedded thumbnail EOI ignored; burst ambiguity rejected; originals preserved");
        }finally{delete(sd);}
    }
    private static void delete(File file){File[] children=file.listFiles();if(children!=null)for(File c:children)delete(c);file.delete();}
}
