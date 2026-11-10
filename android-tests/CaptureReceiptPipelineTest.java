package io.github.englandlobster.lutcamera;

import java.io.*;
import java.util.*;

/** Replay nonzero native receipt identifiers through the reflection adapter. */
public final class CaptureReceiptPipelineTest {
    public static class Camera {
        public interface StoreImageCompleteListener {void onDone(int id,Info info,Camera camera);}
        public static class Info {
            public String DirectoryName="DCIM/100MSDCF/",FileName;
            Info(String name){FileName=name;}
        }
        StoreImageCompleteListener listener;
        public void setStoreImageCompleteListener(StoreImageCompleteListener value){listener=value;}
        void saved(int id,File file){listener.onDone(id,new Info(file.getName()),this);}
    }
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    private static final class ReceiptSink implements CaptureStoreEvents.Sink {
        final CaptureStoreCompletion completion;
        long token;
        Throwable error;
        boolean reproduceRc7;
        ReceiptSink(CaptureStoreCompletion value){completion=value;}
        public void named(int id,String directory,String name){
            // The removed RC3-RC7 gate. Replay it once to prove the stall.
            if(reproduceRc7&&id!=0)return;
            try{completion.notified(token,directory,name);}catch(Throwable e){error=e;}
        }
        public void failed(Throwable value){error=value;}
    }
    private static void run(File root)throws Exception{
        check(root.mkdir(),"scratch card");
        File dcim=new File(root,"DCIM");
        PhotoFiles files=new PhotoFiles(root,PhotoFiles.Format.JPEG,false);
        files.expect(new CaptureGeometry(6000,4000));
        check(!dcim.exists(),"observer must not create native photo directories");
        CaptureStoreCompletion.Registration index=new CaptureStoreCompletion.Registration(){
            public void begin(){}
            public PhotoIndex.Entry saved(){return null;}
            public void accepted(File file){}
        };
        CaptureStoreCompletion completion=new CaptureStoreCompletion(files,index,true,true);
        ReceiptSink sink=new ReceiptSink(completion);Camera camera=new Camera();
        CaptureStoreEvents events=new CaptureStoreEvents(camera,sink);
        try{
            int number=0;
            // Nonzero first receipt also covers counters retained outside Java.
            for(int id:new int[]{41,0,1,2,32767}){
                for(int role=0;role<2;role++){
                    sink.token=role==0?completion.begin(0):completion.beginFinalStore(0);
                    completion.rpcStarted(sink.token);
                    File file=new File(dcim,"100MSDCF/"+String.format(Locale.US,"DSC%05d.JPG",++number));
                    check(file.getParentFile().isDirectory()||file.getParentFile().mkdirs(),"native creates DCF");
                    CapturePipelineTest.write(file,CapturePipelineTest.jpeg(6000,4000,true));
                    if(number==1){
                        sink.reproduceRc7=true;camera.saved(id,file);completion.rpcReturned(sink.token);
                        for(int now=0;now<=40000;now+=200)check(completion.poll(sink.token,now)==null,"RC7 nonzero receipt stall");
                        check(completion.pending(),"RC7 waits forever for pair index");
                        sink.reproduceRc7=false;
                        System.out.println("PASS reproduced RC7 numeric gate: receipt 41 stalls for 40 seconds despite complete original");
                    }
                    camera.saved(id,file);
                    if(number!=1)check(completion.poll(sink.token,0)==null,"receipt cannot release active RPC");
                    completion.rpcReturned(sink.token);
                    CaptureStoreCompletion.Receipt receipt=completion.poll(sink.token,41000);
                    check(sink.error==null,"reflection callback accepted");
                    check(receipt!=null&&!receipt.recovered&&receipt.file.equals(file.getCanonicalFile()),
                        "verified named receipt completes on the first check without index publication: "+id);
                    files.accepted(receipt.file);
                    camera.saved(id,file); // A repeated callback cannot select this photo again.
                }
            }
            check(number==10&&!completion.pending(),"five consecutive original/graded pairs complete");
            System.out.println("PASS verified native receipts: 5 pairs, first-check completion, nonzero and repeated IDs, duplicates, delayed DCIM creation");

            sink.token=completion.begin(0);
            completion.rpcStarted(sink.token);
            File wrongSize=new File(dcim,"100MSDCF/DSC00011.JPG");
            camera.saved(41,wrongSize);
            completion.rpcReturned(sink.token);
            check(completion.poll(sink.token,1000)==null,"named file must be visible");
            CapturePipelineTest.write(wrongSize,CapturePipelineTest.jpeg(5998,4000,true));
            boolean invalid=false;
            try{completion.poll(sink.token,1200);}
            catch(CaptureStoreCompletion.ConfirmedInvalid error){invalid=error.file.equals(wrongSize.getCanonicalFile());}
            check(invalid&&!completion.pending(),"verified named file still requires expected geometry");
        }finally{events.close();}
        File bad=new File(root,"bad");check(bad.mkdir(),"bad card");
        CapturePipelineTest.write(new File(bad,"DCIM"),new byte[]{1});
        boolean refused=false;try{new PhotoFiles(bad,PhotoFiles.Format.JPEG,false);}catch(IOException expected){refused=true;}
        check(refused,"DCIM regular file rejected");
        refused=false;try{new PhotoFiles(new File(root,"absent"),PhotoFiles.Format.JPEG,false);}catch(IOException expected){refused=true;}
        check(refused,"missing SD root rejected");
    }
    public static void main(String[] args)throws Exception{
        File root=File.createTempFile("receipt-flow-","",args.length==0?null:new File(args[0]));
        check(root.delete(),"scratch");
        try{run(root);}finally{CapturePipelineTest.remove(root);}
    }
}
