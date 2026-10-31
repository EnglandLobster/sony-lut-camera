package org.hongen.sonylutprobe;
import java.io.*;
import java.nio.file.Files;

public final class PreviewProfileStoreTest {
    static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
    static String text(String sha,int version,double mae){StringBuilder s=new StringBuilder("SONY_PREVIEW_V2\n"+sha+"\nmatrix_gamma\n"+version+" "+mae+" 30 15 123\n1024 0 0 0 1024 0 0 0 1024\n");for(int i=0;i<1024;i++)s.append(i).append(' ');return s.toString();}
    static void write(File f,String value)throws Exception{Files.write(f.toPath(),value.getBytes("US-ASCII"));}
    public static void main(String[] args)throws Exception{
        File dir=Files.createTempDirectory("preview-store").toFile();File cube=new File(dir,"TABLE.CUB");write(cube,"source stays exact");
        final String sha=new String(new char[64]).replace('\0','a');final int[] calls={0};
        LutLibrary.Entry e=new LutLibrary.Entry(sha,"Test","Test",33,cube);
        PreviewProfileStore.Fitter fitter=new PreviewProfileStore.Fitter(){public String fit(LutLibrary.Entry e){calls[0]++;return text(sha,1,20);}};
        check(PreviewProfileStore.choice(e).enabled,"default approximate even before fit");
        NativePreviewProfile p=PreviewProfileStore.ensure(e,fitter);check(p.largeError()&&p.fitVersion==1,"high error is usable metadata");
        PreviewProfileStore.ensure(e,fitter);check(calls[0]==1,"cache hit avoids solver");
        check(PreviewProfileStore.choice(e).enabled,"high error does not disable preview");
        PreviewProfileStore.choose(e,false,1);check(!PreviewProfileStore.choice(e).enabled,"original preference persists");
        PreviewProfileStore.choose(e,true,1);check(PreviewProfileStore.choice(e).enabled&&PreviewProfileStore.choice(e).acknowledged==1,"override and warning acknowledgement persist");
        write(new File(dir,"VIEWB.CFG"),"partial");check(!PreviewProfileStore.choice(e).enabled,"interrupted newest choice recovers prior slot");
        write(new File(dir,"PREVIEW.PRV"),"partial");PreviewProfileStore.ensure(e,fitter);check(calls[0]==2,"truncated derived cache regenerated");
        for(String invalid:new String[]{text(sha,2,20),text(sha,1,Double.NaN),text(sha,1,Double.POSITIVE_INFINITY),text(sha,1,-1),text(sha,1,256),text(sha.replace('a','b'),1,20)}){
            boolean rejected=false;try{NativePreviewProfile.read(new ByteArrayInputStream(invalid.getBytes("US-ASCII")),sha);}catch(IOException expected){rejected=true;}
            check(rejected,"invalid/stale metadata rejected");
        }
        write(new File(dir,"VIEWA.CFG"),"partial");
        boolean badChoice=false;try{PreviewProfileStore.choice(e);}catch(IOException expected){badChoice=true;}check(badChoice,"both invalid slots require user choice");
        PreviewProfileStore.choose(e,false,1);check(!PreviewProfileStore.choice(e).enabled,"explicit user choice repairs corrupted preferences");
        check(new String(Files.readAllBytes(cube.toPath()),"US-ASCII").equals("source stays exact"),"original CUBE unchanged");
        System.out.println("PreviewProfileStoreTest PASS: cached fit, corrupt recovery, high-error opt-in, durable per-LUT choice, stale metadata and source preservation");
    }
}
