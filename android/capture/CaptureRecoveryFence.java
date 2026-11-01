package org.hongen.sonylutprobe;

import java.io.*;
import java.nio.channels.FileLock;
import java.util.UUID;

/** Cross-process ownership fence. A dead Java process is not AV completion.
 * Only its owner after cleanup, or a different kernel boot, clears the fence. */
final class CaptureRecoveryFence {
    private final File file,lockFile;
    private final String boot,owner;
    CaptureRecoveryFence(File directory,String boot,String owner)throws IOException{
        if(boot==null||!boot.matches("[a-fA-F0-9-]{36}")||owner==null||!owner.matches("[a-zA-Z0-9-]{1,80}"))throw new IOException("capture recovery identity unavailable");
        file=new File(directory,"CAPTURE.PENDING");lockFile=new File(directory,"CAPTURE.LOCK");this.boot=boot;this.owner=owner;
    }
    static CaptureRecoveryFence local(File directory)throws IOException{
        BufferedReader in=new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"));String boot;
        try{boot=in.readLine();}finally{in.close();}
        return new CaptureRecoveryFence(directory,boot,UUID.randomUUID().toString());
    }
    private String[] read()throws IOException{
        if(!file.exists())return null;
        BufferedReader in=new BufferedReader(new FileReader(file));
        try{String a=in.readLine(),b=in.readLine();
            if(a==null||!a.matches("[a-fA-F0-9-]{36}")||b==null||!b.matches("[a-zA-Z0-9-]{1,80}")||in.readLine()!=null)throw new IOException("capture recovery record damaged");
            return new String[]{a,b};
        }finally{in.close();}
    }
    synchronized boolean blocked()throws IOException{
        RandomAccessFile lock=new RandomAccessFile(lockFile,"rw");FileLock held=null;
        try{held=lock.getChannel().lock();String[] state=read();return state!=null&&boot.equals(state[0]);}finally{try{if(held!=null)held.release();}finally{lock.close();}}
    }
    synchronized void acquire()throws IOException{
        RandomAccessFile lock=new RandomAccessFile(lockFile,"rw");FileLock held=null;
        try{held=lock.getChannel().lock();String[] state=read();
            if(state!=null&&boot.equals(state[0])){if(owner.equals(state[1]))return;throw new IOException("previous capture still owns the camera");}
            File temp=new File(file.getParentFile(),"CAPTURE.NEW");FileOutputStream out=new FileOutputStream(temp);
            try{out.write((boot+"\n"+owner+"\n").getBytes("UTF-8"));out.getFD().sync();}finally{out.close();}
            if(!temp.renameTo(file))throw new IOException("cannot persist capture recovery ownership");
            // A new session must not export the previous session's wait state.
            try{snapshot("state=owned; store session started");}catch(IOException ignored){}
        }finally{try{if(held!=null)held.release();}finally{lock.close();}}
    }
    synchronized void released()throws IOException{
        RandomAccessFile lock=new RandomAccessFile(lockFile,"rw");FileLock held=null;
        try{held=lock.getChannel().lock();String[] state=read();if(state!=null&&boot.equals(state[0])&&owner.equals(state[1])){
            if(!file.delete())throw new IOException("cannot clear capture recovery ownership");
            try{snapshot("state=released; native cleanup completed\nlast observation:\n"+snapshot(file.getParentFile()));}catch(IOException ignored){}
        }}
        finally{try{if(held!=null)held.release();}finally{lock.close();}}
    }
    // Private storage only: the watchdog must not wait on the camera's SD service.
    void snapshot(String detail)throws IOException{
        if(detail.length()>6000)detail=detail.substring(0,6000);
        FileOutputStream out=new FileOutputStream(new File(file.getParentFile(),"CAPTURE.WAIT"));
        try{out.write(("boot="+boot+" owner="+owner+"\n"+detail).getBytes("UTF-8"));}finally{out.close();}
    }
    static String snapshot(File directory){
        try{FileInputStream in=new FileInputStream(new File(directory,"CAPTURE.WAIT"));
            try{byte[] b=new byte[8192];int n=in.read(b);return n>0?new String(b,0,n,"UTF-8"):"none";}finally{in.close();}
        }catch(IOException e){return "none";}
    }
}
