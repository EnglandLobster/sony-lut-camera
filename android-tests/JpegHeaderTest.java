package org.hongen.sonylutprobe;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class JpegHeaderTest {
    private static void check(boolean value,String detail){if(!value)throw new AssertionError(detail);}
    private static byte[] header()throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeShort(0xffd8);out.writeShort(0xffe1);out.writeShort(10);
        out.write(new byte[]{(byte)255,(byte)192,0,17,8,0,1,0}); // Thumbnail-like bytes must be skipped.
        out.writeShort(0xffc0);out.writeShort(17);out.writeByte(8);out.writeShort(4000);out.writeShort(6000);out.writeByte(3);
        out.write(new byte[]{1,0x21,0,2,0x11,1,3,0x11,1});return bytes.toByteArray();
    }
    public static void main(String[] args)throws Exception{
        File f=File.createTempFile("sony-jpeg-header-",".JPG");try{
            byte[] bytes=header();Files.write(f.toPath(),bytes);
            check(PhotoFiles.jpegHeader(f,6000,4000),"full SOF missed");
            check(!PhotoFiles.jpegHeader(f,4000,6000),"wrong geometry accepted");
            check(!PhotoFiles.completeJpeg(f),"header mistaken for complete file");
            Files.write(f.toPath(),Arrays.copyOf(bytes,bytes.length-1));check(!PhotoFiles.jpegHeader(f,6000,4000),"truncated SOF accepted");
            bytes=header();bytes[16]=0;bytes[17]=7;Files.write(f.toPath(),bytes);check(!PhotoFiles.jpegHeader(f,6000,4000),"short SOF accepted");
            bytes=header();bytes[16]=(byte)255;bytes[17]=(byte)255;Files.write(f.toPath(),bytes);check(!PhotoFiles.jpegHeader(f,6000,4000),"out-of-file segment accepted");
            // Main markers may cross a cache-window boundary, or follow a
            // large APP block. The thumbnail-like bytes must stay skipped.
            for(int payload:new int[]{4072,4073,4074,4075,4090,4091,4092,4093,65533}){
                ByteArrayOutputStream buffer=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(buffer);
                out.writeShort(0xffd8);out.writeShort(0xffe1);out.writeShort(payload+2);
                byte[] app=new byte[payload];Arrays.fill(app,(byte)0xff);out.write(app);
                byte[] sof=Arrays.copyOfRange(header(),14,header().length);out.write(sof);
                bytes=buffer.toByteArray();Files.write(f.toPath(),bytes);
                check(PhotoFiles.jpegHeader(f,6000,4000),"cache boundary/APP skip failed: "+payload);
                Files.write(f.toPath(),Arrays.copyOf(bytes,bytes.length-1));
                check(!PhotoFiles.jpegHeader(f,6000,4000),"truncated boundary SOF accepted: "+payload);
            }
            for(String path:args)check(PhotoFiles.jpegHeader(new File(path),6000,4000),"real JPEG header rejected: "+path);
            System.out.println("PASS: exact JPEG dimensions, APP thumbnail skipping, segment bounds; header alone never counts as complete payload");
        }finally{f.delete();}
    }
}
