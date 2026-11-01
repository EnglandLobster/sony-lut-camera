package org.hongen.sonylutprobe;

import java.io.*;
import java.util.*;

/** Drive production state/settings/file/receipt logic with controlled Sony events. */
public final class CapturePipelineTest {
    private static File scratchParent;
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static class Driver implements SimpleExposure.Driver {
        final Map<String,String> values=new LinkedHashMap<String,String>();
        public Map<String,String> read(){return new LinkedHashMap<String,String>(values);}
        public void write(Map<String,String> patch){values.putAll(patch);}
    }
    static class Index implements CaptureStoreCompletion.Registration {
        File visible;int before;int order;
        public void begin(){before=order;}
        public boolean registered(File file)throws Exception{return order>before&&visible!=null&&visible.getCanonicalFile().equals(file);}
        void saved(File file){visible=file;order++;}
    }
    static byte[] jpeg(int width,int height,boolean complete)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeShort(0xffd8);out.writeShort(0xffc0);out.writeShort(17);out.writeByte(8);
        out.writeShort(height);out.writeShort(width);out.writeByte(3);
        for(int c=1;c<=3;c++){out.writeByte(c);out.writeByte(0x11);out.writeByte(0);}
        out.writeShort(0xffda);out.writeShort(12);out.writeByte(3);
        for(int c=1;c<=3;c++){out.writeByte(c);out.writeByte(0);}
        out.writeByte(0);out.writeByte(63);out.writeByte(0);out.write(new byte[]{1,2,3,4});
        if(complete)out.writeShort(0xffd9);return bytes.toByteArray();
    }
    static void write(File file,byte[] data)throws Exception{OutputStream out=new FileOutputStream(file);try{out.write(data);}finally{out.close();}}
    static void remove(File file){File[] children=file.listFiles();if(children!=null)for(File f:children)remove(f);file.delete();}
    static PreJpegGate armed(){
        PreJpegGate gate=new PreJpegGate();gate.preview();gate.registering();gate.defaultRegistered();gate.defaultUnlocked();
        gate.configuring();gate.registered(false);gate.arm();gate.raw();gate.savingOriginal();return gate;
    }
    static void run(String memory,int mp,int width,int height,boolean modern)throws Exception{
        CaptureGeometry geometry=CaptureGeometry.select(width+"x"+height+",1920x1080","1920x1080",memory,mp);
        geometry.verify(width,height);
        check(geometry.reviewWidth()<=750&&geometry.reviewHeight()<=500,"bounded review");
        check(Math.abs((double)geometry.reviewWidth()/geometry.reviewHeight()-(double)width/height)<0.01,"review aspect preserved");
        Driver driver=new Driver();driver.values.put("picture-size-values",width+"x"+height+",1920x1080");
        driver.values.put("picture-size","1920x1080");driver.values.put("drive-mode","burst");
        driver.values.put("drive-mode-values","single,burst");driver.values.put("storage-fmt","rawjpeg");
        driver.values.put("jpeg-quality","50");Map<String,String> original=driver.read();
        CaptureSetup setup=new CaptureSetup(driver,geometry);setup.open();
        check(driver.values.get("picture-size").equals(geometry.toString()),"native full size selected");
        File sd=File.createTempFile("capture-flow-","",scratchParent);check(sd.delete()&&sd.mkdir(),"scratch");
        try{
            File dir=new File(sd,"DCIM/100MSDCF");check(dir.mkdirs(),"DCF");
            File old=new File(dir,"DSC00001.JPG");write(old,jpeg(width,height,true));
            PhotoFiles files=new PhotoFiles(sd);files.expect(geometry);Index index=new Index();index.saved(old);
            CaptureStoreCompletion completion=new CaptureStoreCompletion(files,index,modern);PreJpegGate gate=armed();
            long a=completion.begin(0);File source=new File(dir,"DSC00002.JPG");
            write(source,jpeg(width,height,false));index.saved(source);
            if(modern)completion.notified(a,"DCIM/100MSDCF/",source.getName());
            check(completion.poll(a,1000)==null&&completion.poll(a,1200)==null,"early callback/index cannot accept partial JPEG");
            write(source,jpeg(width-2,height,true));
            boolean invalid=false;
            try{completion.poll(a,1400);completion.poll(a,1600);}
            catch(CaptureStoreCompletion.ConfirmedInvalid error){invalid=true;files.accepted(error.file);}
            check(invalid&&!completion.pending(),"fully saved wrong size terminates safely instead of waiting forever");
            // Drop the callback entirely, with the index appearing after the file.
            source=new File(dir,"DSC00008.JPG");
            a=completion.begin(1800);index.saved(old);index.visible=null;
            write(source,jpeg(width,height,true));
            check(completion.poll(a,3000)==null&&completion.poll(a,3200)==null,"file waits for matching new index");
            index.saved(source);CaptureStoreCompletion.Receipt first=completion.poll(a,3400);
            check(first!=null&&first.recovered&&first.file.equals(source.getCanonicalFile()),"original callback loss recovered");
            files.accepted(first.file);gate.originalSaved();gate.savingGraded();
            long b=completion.begin(3500);File graded=new File(dir,"DSC00003.JPG");
            completion.notified(a,"DCIM/100MSDCF/",source.getName());
            completion.notified(b,"DCIM/100MSDCF/",source.getName());
            check(completion.poll(a,3700)==null&&completion.poll(b,3700)==null,"late/duplicate original cannot finish graded store");
            check(completion.overdue(b,40000)&&completion.pending()&&gate.busy(),"deadline retains buffer barrier; no false success or resubmit");
            write(graded,jpeg(width,height,true));index.saved(graded);
            check(completion.poll(b,40200)==null,"recovery file must be stable");
            CaptureStoreCompletion.Receipt second=completion.poll(b,40400);
            check(second!=null&&second.recovered&&!second.file.equals(first.file),"graded callback loss recovers even after deadline");
            files.accepted(second.file);gate.saved();check(completion.poll(b,40600)==null,"exactly once");
            check(gate.beginRearm(gate.shot())&&gate.rearmed(gate.shot()),"safe next shot");
            long c=completion.begin(41000);File third=new File(dir,"DSC00004.JPG");write(third,jpeg(width,height,true));
            completion.notified(c,"DCIM/100MSDCF/",third.getName());
            check(completion.poll(c,41200)==null,"named file also waits for index before the next watermark");
            index.saved(third);CaptureStoreCompletion.Receipt named=completion.poll(c,41400);
            check(named!=null&&!named.recovered,"named receipt and matching index confirmed");files.accepted(named.file);
            long stopping=completion.begin(41500);completion.requestStop();
            check(completion.pending()&&completion.poll(stopping,41600)==null,"stop request retains pending ownership");
            File stoppingFile=new File(dir,"DSC00007.JPG");write(stoppingFile,jpeg(width,height,true));index.saved(stoppingFile);
            completion.poll(stopping,42600);CaptureStoreCompletion.Receipt stopped=completion.poll(stopping,42800);
            check(stopped!=null&&stopped.stopRequested,"confirmed stop returns preserved photo");files.accepted(stopped.file);
            boolean furtherBlocked=false;try{completion.begin(42900);}catch(IllegalStateException e){furtherBlocked=true;}
            check(furtherBlocked,"stop cannot accidentally submit graded/next photo");
            completion=new CaptureStoreCompletion(files,index,modern);
            long uncertain=completion.begin(42000);File extra=new File(dir,"DSC00005.JPG"),extra2=new File(dir,"DSC00006.JPG");
            write(extra,jpeg(width,height,true));write(extra2,jpeg(width,height,true));
            boolean rejected=false;try{completion.poll(uncertain,44000);}catch(IOException e){rejected=true;}
            check(rejected&&completion.pending(),"ambiguous completion cannot release ownership");
            completion.cancel();check(completion.poll(uncertain,45000)==null,"canceled polls ignored");
            check(PhotoFiles.completeJpeg(source)&&PhotoFiles.completeJpeg(graded),"both photos preserved");
        }finally{remove(sd);setup.close();}
        check(driver.read().equals(original),"all inherited settings restored");
        System.out.println("PASS pipeline "+memory+"/"+mp+" "+width+"x"+height+" modern="+modern);
    }
    public static void main(String[] args)throws Exception{
        scratchParent=args.length==0?null:new File(args[0]);
        String[] memory={"ILDCSC_MUSASHI8Gb","unknown","ILDCSC_MUSASHI4Gb","DSCSC_KOJIRO4Gb","DSCSC_MUSASHI8Gb","ILDCSC_MUSASHI8Gb","ILDCSC_MUSASHI8Gb","ILDCSC_MUSASHI8Gb"};
        int[][] geometry={{12,4240,2832},{16,4912,3264},{20,5456,3632},{20,5184,3888},{20,5472,3648},{24,6000,4000},{36,7360,4912},{42,7952,5304}};
        for(int i=0;i<memory.length;i++)for(boolean modern:new boolean[]{false,true})run(memory[i],geometry[i][0],geometry[i][1],geometry[i][2],modern);
        for(String invalid:new String[]{"6000x3376","3000x2000","7952x5304"}){
            boolean failed=false;try{CaptureGeometry.select(invalid,invalid,"ILDCSC_MUSASHI8Gb",24);}catch(IOException e){failed=true;}
            check(failed,"cropped, small or wrong-sensor dimensions rejected");
        }
    }
}
