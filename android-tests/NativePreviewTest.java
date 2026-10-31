package org.hongen.sonylutprobe;
import java.io.*;
import java.util.*;

public final class NativePreviewTest {
    private static final String SHA="0000000000000000000000000000000000000000000000000000000000000000";
    private static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
    private static String profile(){StringBuilder s=new StringBuilder("SONY_PREVIEW_V1\n"+SHA+"\nmatrix_gamma\n1024 0 0 0 1024 0 0 0 1024\n");
        for(int i=0;i<1024;i++)s.append(i).append(' ');return s.toString();}
    private static NativePreviewProfile parse(String text,String digest)throws Exception{
        return NativePreviewProfile.read(new ByteArrayInputStream(text.getBytes("US-ASCII")),digest);
    }
    interface Checked {void run()throws Exception;}
    private static void rejects(Checked r)throws Exception{boolean rejected=false;try{r.run();}catch(Exception e){rejected=true;}check(rejected,"must reject");}
    private static final class Driver implements NativePreviewSession.Driver {
        boolean matrix,gamma,owned,failWrite,failRestore,failVerify;
        int releases,restores;
        public void snapshot(){}
        public void applyMatrix(int[] m)throws Exception{matrix=true;if(failWrite)throw new IOException("partial matrix write");}
        public void applyGamma(byte[] g){owned=true;gamma=true;}
        public void verifyApplied(int[] m,byte[] g)throws Exception{if(failVerify)throw new IOException("readback");}
        public void restoreGamma()throws Exception{restores++;if(failRestore)throw new IOException("detach failed");gamma=false;}
        public void restoreMatrix(){matrix=false;}
        public void verifyBaseline()throws Exception{if(matrix||gamma)throw new IOException("dirty baseline");}
        public void releaseOwned(){check(!gamma,"never release a table still consumed by camera");if(owned){owned=false;releases++;}}
    }
    public static void main(String[] args)throws Exception{
        final NativePreviewProfile p=parse(profile(),SHA);check(p.gamma.length==2048,"exact gamma bytes");
        check((p.gamma[2046]&255)==255&&(p.gamma[2047]&255)==3,"little endian 1023");
        rejects(new Checked(){public void run()throws Exception{parse(profile(),SHA.replace('0','1'));}});
        rejects(new Checked(){public void run()throws Exception{parse(profile()+" 0",SHA);}});
        rejects(new Checked(){public void run()throws Exception{parse(profile().replace("1024 0 0","99999 0 0"),SHA);}});
        rejects(new Checked(){public void run()throws Exception{parse(profile().replace("1022 1023","1022 1000"),SHA);}});
        rejects(new Checked(){public void run()throws Exception{parse(profile().replace("matrix_gamma","unknown"),SHA);}});
        Driver d=new Driver();final NativePreviewSession s=new NativePreviewSession(d);s.open();
        for(int i=0;i<5;i++){s.apply(p);check(s.state()==NativePreviewSession.State.PREVIEW,"preview");
            rejects(new Checked(){public void run()throws Exception{s.requireBaseline();}});s.restore();s.requireBaseline();}
        check(d.releases==5,"all owned buffers detached/released");s.close();s.close();
        rejects(new Checked(){public void run()throws Exception{s.apply(p);}});
        final Driver partial=new Driver();final NativePreviewSession a=new NativePreviewSession(partial);a.open();partial.failWrite=true;
        rejects(new Checked(){public void run()throws Exception{a.apply(p);}});a.requireBaseline();
        check(!partial.matrix&&partial.restores==1,"partial write restores both controls");
        final Driver readback=new Driver();final NativePreviewSession b=new NativePreviewSession(readback);b.open();readback.failVerify=true;
        rejects(new Checked(){public void run()throws Exception{b.apply(p);}});b.requireBaseline();check(readback.releases==1,"failed verify releases after restore");
        final Driver stuck=new Driver();final NativePreviewSession c=new NativePreviewSession(stuck);c.open();c.apply(p);stuck.failRestore=true;
        rejects(new Checked(){public void run()throws Exception{c.restore();}});
        check(c.state()==NativePreviewSession.State.DIRTY&&!stuck.matrix&&stuck.owned,"retain gamma and try matrix rollback");
        rejects(new Checked(){public void run()throws Exception{c.requireBaseline();}});
        rejects(new Checked(){public void run()throws Exception{c.apply(p);}});
        stuck.failRestore=false;c.restore();c.requireBaseline();c.close();check(stuck.releases==1,"recovery retry");
        for(String file:args){String text=new String(java.nio.file.Files.readAllBytes(new File(file).toPath()),"US-ASCII");
            NativePreviewProfile actual=parse(text,text.split("\\s+")[1]);check(actual.gamma.length==2048,"fitted file");}
        System.out.println("NativePreviewTest PASS: parse, stale identity, transition guards, partial write, rollback failure, buffer ownership");
    }
}
