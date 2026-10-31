package org.hongen.sonylutprobe;
import java.io.*;
import java.util.Arrays;

public final class LegacyStoreCompletionTest {
    private static final byte[] JPEG={(byte)255,(byte)216,(byte)255,(byte)218,0,2,1,2,(byte)255,0,3,(byte)255,(byte)217};
    static void check(boolean value,String detail){if(!value)throw new AssertionError(detail);}
    static void write(File file,byte[] bytes)throws Exception{OutputStream out=new FileOutputStream(file);try{out.write(bytes);}finally{out.close();}}
    static void remove(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);file.delete();}
    static final class Registration implements LegacyStoreCompletion.Registration {
        File indexed;int starts;boolean fail;
        public void begin()throws IOException{starts++;indexed=null;if(fail)throw new IOException("index unavailable");}
        public boolean registered(File file)throws Exception{return indexed!=null&&file.equals(indexed.getCanonicalFile());}
    }
    public static void main(String[] args)throws Exception {
        File sd=File.createTempFile("legacy-save-","",args.length>0?new File(args[0]):null);check(sd.delete()&&sd.mkdir(),"scratch");
        try{
            File dir=new File(sd,"DCIM/100MSDCF");check(dir.mkdirs(),"DCIM");
            File old=new File(dir,"DSC00001.JPG");write(old,JPEG);
            PhotoFiles files=new PhotoFiles(sd);Registration registration=new Registration();LegacyStoreCompletion completion=new LegacyStoreCompletion(files,registration);
            long first=completion.begin(0);File original=new File(dir,"DSC00002.JPG");write(original,JPEG);
            check(completion.poll(first,200)==null&&completion.poll(first,400)==null,"file alone cannot advance without new Sony index entry");
            registration.indexed=original;write(original,Arrays.copyOf(JPEG,7));
            check(completion.poll(first,600)==null&&completion.poll(first,800)==null,"index cannot accept partial stable JPEG");
            write(original,JPEG);check(completion.poll(first,1000)==null,"changed file needs stability");
            File ready=completion.poll(first,1200);check(original.getCanonicalFile().equals(ready),"original confirmed");
            files.accepted(ready);check(completion.poll(first,1400)==null,"exactly once");
            long second=completion.begin(1500);registration.indexed=original;
            check(completion.poll(first,1700)==null&&completion.poll(second,1700)==null,"stale poll/old index record cannot reuse original");
            File graded=new File(dir,"DSC00003.JPG");write(graded,JPEG);
            check(completion.poll(second,1900)==null,"graded file must settle");
            check(completion.poll(second,2100)==null,"graded JPEG waits for its own new index entry");registration.indexed=graded;
            ready=completion.poll(second,2300);check(graded.getCanonicalFile().equals(ready)&&!ready.equals(original.getCanonicalFile()),"distinct graded JPEG confirmed");files.accepted(ready);
            long canceled=completion.begin(2400);completion.cancel();check(completion.poll(canceled,2600)==null,"canceled generation ignored");
            long timeout=completion.begin(3000);boolean failed=false;try{completion.poll(timeout,33000);}catch(IOException expected){failed=true;}check(failed,"missing file/index times out");completion.cancel();
            long ambiguous=completion.begin(34000);write(new File(dir,"DSC00004.JPG"),JPEG);write(new File(dir,"DSC00005.JPG"),JPEG);
            failed=false;try{completion.poll(ambiguous,34200);}catch(IOException expected){failed=true;}check(failed,"ambiguous new files rejected");
            completion.cancel();registration.fail=true;failed=false;
            try{completion.begin(35000);}catch(IOException expected){failed=true;}check(failed,"index snapshot failure surfaced");
            registration.fail=false;long retry=completion.begin(36000);check(completion.active(retry),"failed snapshot does not wedge future stores");completion.cancel();
            check(PhotoFiles.completeJpeg(old)&&PhotoFiles.completeJpeg(original)&&PhotoFiles.completeJpeg(graded),"originals preserved");
            System.out.println("PASS legacy save: new index plus unique stable full JPEG, original/graded ordering, stale generations, cancellation, timeout, ambiguity");
        }finally{remove(sd);}
    }
}
