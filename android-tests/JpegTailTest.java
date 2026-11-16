package io.github.englandlobster.lutcamera;

import java.io.*;
import java.util.*;

/** Completed-store check from both ends of the file, without rereading it. */
public final class JpegTailTest {
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    private static File write(File dir,String name,byte[] data)throws Exception{
        File file=new File(dir,name);CapturePipelineTest.write(file,data);return file;
    }
    /** A complete JPEG followed by a second image and zero padding, as Sony stores it. */
    private static byte[] sony(int padding)throws Exception{
        byte[] main=CapturePipelineTest.jpeg(6000,4000,true),preview=CapturePipelineTest.jpeg(1616,1080,true);
        byte[] all=new byte[main.length+preview.length+padding];
        System.arraycopy(main,0,all,0,main.length);System.arraycopy(preview,0,all,main.length,preview.length);
        return all;
    }
    private static final class Index implements CaptureStoreCompletion.Registration {
        public void begin(){}
        public PhotoIndex.Entry saved(){return null;}
        public void accepted(File file){}
    }
    public static void main(String[] args)throws Exception{
        // A leading directory argument is the scratch parent (API10 has no default tmpdir); the rest are camera JPEGs.
        File parent=args.length>0&&new File(args[0]).isDirectory()?new File(args[0]):null;
        File sd=File.createTempFile("jpeg-tail-","",parent);
        check(sd.delete()&&sd.mkdir(),"scratch");
        try{
            File dir=new File(sd,"DCIM/100MSDCF");check(dir.mkdirs(),"DCF");
            byte[] plain=CapturePipelineTest.jpeg(6000,4000,true);
            check(PhotoFiles.jpegEnds(write(dir,"A.JPG",plain)),"EOI at the physical end");
            for(int padding:new int[]{1,263,32766,65534,65535,65536,65537,200000})
                check(PhotoFiles.jpegEnds(write(dir,"A.JPG",sony(padding))),"Sony zero padding "+padding);
            check(!PhotoFiles.jpegEnds(write(dir,"A.JPG",sony(PhotoFiles.TAIL_LIMIT+70000))),"padding beyond the bounded tail is left to the full scan");
            check(PhotoFiles.completeJpeg(new File(dir,"A.JPG")),"the full scan still accepts it");
            check(!PhotoFiles.jpegEnds(write(dir,"A.JPG",CapturePipelineTest.jpeg(6000,4000,false))),"no EOI");
            byte[] cut=Arrays.copyOf(plain,plain.length-1);
            check(!PhotoFiles.jpegEnds(write(dir,"A.JPG",cut)),"EOI cut in half");
            byte[] zeros=Arrays.copyOf(CapturePipelineTest.jpeg(6000,4000,false),40000);
            check(!PhotoFiles.jpegEnds(write(dir,"A.JPG",zeros)),"preallocated zeros after a partial scan");
            byte[] garbage=sony(100);garbage[garbage.length-1]=7;
            check(!PhotoFiles.jpegEnds(write(dir,"A.JPG",garbage)),"nonzero bytes after EOI are not Sony padding");
            byte[] noStart=sony(100);noStart[1]=0;
            check(!PhotoFiles.jpegEnds(write(dir,"A.JPG",noStart)),"missing SOI");
            check(!PhotoFiles.jpegEnds(write(dir,"A.JPG",new byte[]{(byte)255,(byte)216,(byte)255})),"three bytes");
            // 70000 - TAIL_WINDOW = 4464: the first tail window starts exactly on this D9.
            byte[] lone=new byte[70000];lone[0]=(byte)255;lone[1]=(byte)216;lone[4464]=(byte)217;
            check(!PhotoFiles.jpegEnds(write(dir,"A.JPG",lone)),"D9 without FF at a window start");
            lone[4463]=(byte)255;
            check(PhotoFiles.jpegEnds(write(dir,"A.JPG",lone)),"FF D9 split across tail windows");
            new File(dir,"A.JPG").delete();
            System.out.println("PASS JPEG tail: EOI plus Sony zero padding across window boundaries; partial, preallocated, cut and foreign tails rejected");

            // Named receipt: complete at once from the tail; a partial file waits and is rescanned.
            PhotoFiles files=new PhotoFiles(sd,PhotoFiles.Format.JPEG,false);files.expect(new CaptureGeometry(6000,4000));
            CaptureStoreCompletion completion=new CaptureStoreCompletion(files,new Index(),true);
            long token=CapturePipelineTest.returnedStore(completion,0);
            File first=write(dir,"DSC00001.JPG",sony(12345));
            completion.notified(token,"DCIM/100MSDCF/","DSC00001");
            CaptureStoreCompletion.Receipt receipt=completion.poll(token,0);
            check(receipt!=null&&!receipt.recovered&&receipt.file.equals(first.getCanonicalFile()),"named receipt confirmed on the first poll");
            check(completion.diagnostics().contains("complete_by=tail"),"confirmed by the tail: "+completion.diagnostics());
            files.accepted(receipt.file);

            token=CapturePipelineTest.returnedStore(completion,1000);
            File second=write(dir,"DSC00002.JPG",CapturePipelineTest.jpeg(6000,4000,false));
            completion.notified(token,"DCIM/100MSDCF/","DSC00002");
            for(int now=1000;now<=4000;now+=200)check(completion.poll(token,now)==null,"callback for a partial file must wait");
            byte[] odd=sony(100);odd[odd.length-1]=7;
            CapturePipelineTest.write(second,odd);
            receipt=null;for(int now=4200;now<=8000&&receipt==null;now+=200)receipt=completion.poll(token,now);
            check(receipt!=null&&receipt.file.equals(second.getCanonicalFile()),"unusual tail still confirmed");
            check(completion.diagnostics().contains("complete_by=scan"),"confirmed by the full scan: "+completion.diagnostics());
            files.accepted(receipt.file);

            token=CapturePipelineTest.returnedStore(completion,9000);
            write(dir,"DSC00003.JPG",CapturePipelineTest.jpeg(4000,6000,true));
            completion.notified(token,"DCIM/100MSDCF/","DSC00003");
            boolean invalid=false;try{completion.poll(token,9000);}catch(CaptureStoreCompletion.ConfirmedInvalid expected){invalid=true;}
            check(invalid,"complete file of the wrong size is a confirmed failure");
            System.out.println("PASS named receipt: tail confirms on the first poll; partial file waits; unusual tail falls back to the scan; wrong geometry rejected");

            for(String path:args)if(!new File(path).isDirectory()){
                long begin=System.nanoTime();
                check(PhotoFiles.jpegEnds(new File(path)),"real camera JPEG tail rejected: "+path);
                System.out.println("PASS real camera JPEG tail "+new File(path).getName()+" "+(System.nanoTime()-begin)/1000000+" ms");
            }
        }finally{CapturePipelineTest.remove(sd);}
    }
}
