package org.hongen.sonylutprobe;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Only creates new run directories below SONYLUT; never visits camera originals. */
final class RunFiles {
    static File file(File parent,String name) throws IOException {
        if(!name.matches("[A-Z0-9_]{1,8}(\\.[A-Z0-9_]{1,3})?"))
            throw new IOException("Sony SD requires uppercase DOS 8.3 name: "+name);
        return new File(parent,name);
    }
    static File create(File sd) throws IOException {
        File root=file(sd,"SONYLUT");
        if(!root.isDirectory() && !root.mkdirs())throw new IOException("SD folder unavailable: "+root);
        for(int i=0;i<100;++i) {
            String name=String.format(java.util.Locale.US,"R%07X",(System.currentTimeMillis()+i)&0x0fffffffL);
            File run=file(root,name);
            if(run.exists())continue;
            if(run.mkdir())return run;
            throw new IOException("cannot mkdir "+run+" (8.3 name, parent writable="+root.canWrite()+")");
        }
        throw new IOException("cannot create a new SD test directory");
    }
    static void copy(File source,File target) throws IOException {
        file(target.getParentFile(),target.getName());
        FileInputStream in=new FileInputStream(source);
        try {
            FileOutputStream out=new FileOutputStream(target);
            try {byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.flush();}
            finally {out.close();}
        }finally {in.close();}
    }
    static void bytes(ByteBuffer source,int length,File target) throws IOException {
        file(target.getParentFile(),target.getName());
        ByteBuffer b=source.duplicate();b.position(0);b.limit(length);
        FileOutputStream out=new FileOutputStream(target);
        try {FileChannel channel=out.getChannel();while(b.hasRemaining())channel.write(b);out.flush();}
        finally {out.close();}
    }
    static void state(File run,String value) throws IOException {
        // Sony's libInfraFuFsys has no fsync. Flush/close errors still propagate;
        // record the actual export policy rather than claiming a durable sync.
        FileOutputStream out=new FileOutputStream(file(run,"STATE.TXT"));
        try {out.write((value+"\nJAVA_SYNC=FLUSH_CLOSE\n").getBytes("UTF-8"));out.flush();}finally {out.close();}
    }
    /** Bounded, opt-in ADB export. Older copies in this app's RAM scratch area expire. */
    static File exportAdb(File run,File original) throws IOException {
        return exportAdb(run,original,null);
    }
    static File exportAdb(File run,File original,File externalOutput) throws IOException {
        clearAdbCopies();
        File root=new File("/mnt/wv_cache/SONYLUT");
        if(!root.isDirectory()&&!root.mkdir())throw new IOException("ADB RAM export unavailable");
        root.setReadable(true,false);root.setExecutable(true,false);
        File[] files=run.listFiles();if(files==null)throw new IOException("cannot list completed run");
        long bytes=(original==null?0:original.length())+(externalOutput==null?0:externalOutput.length());for(File f:files)if(f.isFile())bytes+=f.length();
        if(bytes>24L*1024*1024||root.getUsableSpace()<bytes+1024*1024)throw new IOException("insufficient bounded RAM export space");
        File out=file(root,run.getName());if(!out.mkdir())throw new IOException("cannot create ADB run");
        out.setReadable(true,false);out.setExecutable(true,false);
        for(File f:files)if(f.isFile()){File dest=file(out,f.getName());copy(f,dest);dest.setReadable(true,false);}
        if(original!=null){File dest=file(out,"ORIG.JPG");copy(original,dest);dest.setReadable(true,false);}
        if(externalOutput!=null){File dest=file(out,"LUT.JPG");copy(externalOutput,dest);dest.setReadable(true,false);}
        return out;
    }
    static void clearAdbCopies() throws IOException {
        File root=new File("/mnt/wv_cache/SONYLUT");File[] old=root.listFiles();
        if(old!=null)for(File dir:old)if(dir.getName().matches("R[0-9A-F]{7}")&&dir.isDirectory()
                &&root.getCanonicalFile().equals(dir.getCanonicalFile().getParentFile())) {
            File[] files=dir.listFiles();if(files==null)continue;
            boolean clean=true;
            for(File f:files) {
                if(!f.isFile()||!f.getName().matches("[A-Z0-9_]{1,8}(\\.[A-Z0-9_]{1,3})?")){clean=false;break;}
            }
            if(clean){for(File f:files)if(!f.delete())throw new IOException("cannot clear old ADB copy");if(!dir.delete())throw new IOException("cannot clear old ADB run");}
        }
    }
}
