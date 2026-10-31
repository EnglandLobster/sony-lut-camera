package org.hongen.sonylutprobe;

import java.io.*;
import java.nio.file.*;

public final class ArwFileTest {
    private static void u16(RandomAccessFile f,int v)throws IOException{f.write(v);f.write(v>>>8);}
    private static void u32(RandomAccessFile f,long v)throws IOException{u16(f,(int)v);u16(f,(int)(v>>>16));}
    private static void entry(RandomAccessFile f,int tag,int type,long value)throws IOException{
        u16(f,tag);u16(f,type);u32(f,1);u32(f,value);
    }
    private static void fixture(File file)throws IOException{
        RandomAccessFile f=new RandomAccessFile(file,"rw");try{
            f.setLength(512+6048L*4024);f.write('I');f.write('I');u16(f,42);u32(f,8);
            u16(f,1);entry(f,330,4,32);u32(f,0);
            f.seek(32);u16(f,9);
            entry(f,256,3,6048);entry(f,257,3,4024);entry(f,258,3,12);
            entry(f,259,3,32767);entry(f,262,3,32803);entry(f,277,3,1);
            entry(f,278,3,4024);entry(f,273,4,512);entry(f,279,4,6048L*4024);u32(f,0);
        }finally{f.close();}
    }
    private static void change(File file,long at,long value)throws IOException{
        RandomAccessFile f=new RandomAccessFile(file,"rw");try{f.seek(at);u32(f,value);}finally{f.close();}
    }
    private static void check(boolean ok,String detail){if(!ok)throw new AssertionError(detail);}
    public static void main(String[] args)throws Exception{
        File sd=Files.createTempDirectory("sony-arw-test-").toFile();
        try{
            File dir=new File(sd,"DCIM/100MSDCF");dir.mkdirs();
            File old=new File(dir,"DSC00053.ARW");fixture(old);
            PhotoFiles raw=new PhotoFiles(sd,PhotoFiles.Format.ARW),jpeg=new PhotoFiles(sd);
            File fresh=new File(dir,"DSC00054.ARW");fixture(fresh);
            raw.confirmStored("DCIM/100MSDCF/","DSC00053");check(raw.pollReady(true)==null,"old RAW selected");
            raw.confirmStored("DCIM/100MSDCF/","DSC00054");
            check(fresh.getCanonicalFile().equals(raw.pollReady(true)),"extensionless Sony RAW notification missed");
            check(raw.complete(fresh),"complete RAW structure rejected");
            jpeg.confirmStored("DCIM/100MSDCF/","DSC00054");check(jpeg.pollReady(true)==null,"RAW mistaken for JPEG");
            boolean wrong=false;try{raw.confirmStored("DCIM/100MSDCF/","DSC00054.JPG");}catch(IOException e){wrong=true;}
            check(wrong,"unexpected JPEG suffix accepted in RAW mode");
            raw.confirmStored("DCIM/100MSDCF/","DSC00054.ARW");
            check(fresh.getCanonicalFile().equals(raw.pollReady(true)),"explicit ARW extension missed");
            RandomAccessFile f=new RandomAccessFile(fresh,"rw");try{f.setLength(f.length()-1);}finally{f.close();}
            check(!raw.complete(fresh),"truncated RAW payload accepted");
            fixture(fresh);change(fresh,18,0xfffffff0L);check(!raw.complete(fresh),"out-of-file SubIFD accepted");
            fixture(fresh);change(fresh,18,8);check(!raw.complete(fresh),"cyclic/thumbnail-only IFD accepted");
            fixture(fresh);change(fresh,42,6000);check(!raw.complete(fresh),"unvalidated RAW dimensions accepted");
            fixture(fresh);change(fresh,78,6);check(!raw.complete(fresh),"JPEG thumbnail compression accepted as RAW");
            fixture(fresh);change(fresh,126,0xfffffff0L);check(!raw.complete(fresh),"out-of-file payload accepted");
            fixture(fresh);raw.accepted(fresh.getCanonicalFile());check(raw.pollReady(true)==null,"RAW accepted twice");
            for(String path:args)check(ArwFile.completeA6300(new File(path)),"real A6300 RAW rejected: "+path);
            System.out.println("PASS: RAW/JPEG notifications separated; old files ignored; A6300 CFA payload bounds checked; truncated, cyclic, thumbnail-only, wrong-size RAW rejected");
        }finally{delete(sd);}
    }
    private static void delete(File file){File[] children=file.listFiles();if(children!=null)for(File c:children)delete(c);file.delete();}
}
