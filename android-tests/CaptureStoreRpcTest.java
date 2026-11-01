package org.hongen.sonylutprobe;

import java.io.*;
import java.util.concurrent.*;

/** A native-call worker is held while an independent observer confirms disk.
 * Neither confirmation nor a timeout may transfer that worker's ownership. */
public final class CaptureStoreRpcTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void write(File file,byte[] bytes)throws IOException{FileOutputStream o=new FileOutputStream(file);try{o.write(bytes);}finally{o.close();}}
    private static byte[] jpeg()throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);
        d.writeShort(0xffd8);d.writeShort(0xffc0);d.writeShort(17);d.writeByte(8);d.writeShort(4000);d.writeShort(6000);d.writeByte(3);
        d.write(new byte[]{1,0x22,0,2,0x11,1,3,0x11,1});d.writeShort(0xffda);d.writeShort(12);d.write(new byte[]{3,1,0,2,0,3,0,0,63,0});d.write(new byte[]{12,34,56});d.writeShort(0xffd9);return b.toByteArray();
    }
    public static void main(String[] args)throws Exception{
        if(args.length==2&&args[0].startsWith("--")){
            File dir=new File(args[1]);if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("fixture directory");
            String boot="11111111-1111-1111-1111-111111111111";
            CaptureRecoveryFence owner=new CaptureRecoveryFence(dir,boot,"writer-process");
            if(args[0].equals("--write-fence")){owner.acquire();System.out.println("PASS persisted fence before writer process exits");return;}
            if(!args[0].equals("--read-fence"))throw new IllegalArgumentException();
            CaptureRecoveryFence reader=new CaptureRecoveryFence(dir,boot,"reader-process");
            check(reader.blocked(),"fence survives actual writer process exit");reader.released();check(reader.blocked(),"other process cannot clear");
            owner.released();check(!reader.blocked(),"test owner cleanup");remove(dir);
            System.out.println("PASS second process observes fence after writer death; no unsafe reopen");return;
        }
        File base=File.createTempFile("store-rpc-","",args.length==0?null:new File(args[0]));check(base.delete()&&base.mkdir(),"scratch");
        try{
            for(boolean modern:new boolean[]{false,true})for(boolean stopping:new boolean[]{false,true}){
                File sd=new File(base,"case-"+modern+stopping),dcim=new File(sd,"DCIM/100MSDCF");check(dcim.mkdirs(),"dcim");
                final PhotoFiles files=new PhotoFiles(sd);files.expect(new CaptureGeometry(6000,4000));
                final boolean[] indexed={false};
                final CaptureStoreCompletion completion=new CaptureStoreCompletion(files,new CaptureStoreCompletion.Registration(){
                    public void begin(){}public boolean registered(File f){return indexed[0];}
                },modern);
                final long token=completion.begin(0);final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
                Thread worker=new Thread(new Runnable(){public void run(){completion.rpcStarted(token);entered.countDown();try{release.await();}catch(InterruptedException e){throw new AssertionError(e);}finally{completion.rpcReturned(token);}}},"blocked-store");
                worker.setDaemon(true);worker.start();check(entered.await(2,TimeUnit.SECONDS),"worker entered");
                try{
                    check(completion.overdue(token,30001)&&completion.pending()&&completion.rpcActive(),"timeout retains in-flight ownership");
                    boolean cancelRefused=false;try{completion.cancel();}catch(IllegalStateException e){cancelRefused=true;}check(cancelRefused,"timeout cannot cancel JNI ownership");
                    if(stopping)completion.requestStop();
                    File file=new File(dcim,"DSC00001.JPG");write(file,jpeg());indexed[0]=true;
                    if(modern)completion.notified(token,"DCIM/100MSDCF/","DSC00001.JPG");
                    completion.poll(token,31000);check(completion.poll(token,32000)==null,"file alone cannot advance JNI call");
                    check(completion.confirmed()&&completion.pending()&&worker.isAlive(),"independent disk confirmation while call blocked");
                    boolean refused=false;try{completion.begin(33000);}catch(IllegalStateException e){refused=true;}check(refused,"no concurrent resubmission");
                }finally{release.countDown();worker.join(2000);}
                check(!worker.isAlive(),"late RPC return drains worker");
                CaptureStoreCompletion.Receipt result=completion.poll(token,40000);
                check(result!=null&&result.stopRequested==stopping&&!completion.pending(),"late return completes exactly once respecting stop");
                files.accepted(result.file);completion.notified(token,"DCIM/100MSDCF/","DSC00001.JPG");
                check(completion.poll(token,41000)==null,"late duplicate ignored");
                System.out.println("PASS blocked store modern="+modern+" stop="+stopping);
            }
            String boot="11111111-1111-1111-1111-111111111111",next="22222222-2222-2222-2222-222222222222";
            CaptureRecoveryFence owner=new CaptureRecoveryFence(base,boot,"owner"),other=new CaptureRecoveryFence(base,boot,"new-process");
            check(!owner.blocked(),"initially clear");owner.acquire();owner.acquire();check(other.blocked(),"restart cannot bypass live/persisted owner");
            boolean refused=false;try{other.acquire();}catch(IOException e){refused=true;}check(refused,"second process rejected");other.released();check(owner.blocked(),"non-owner cannot clear");
            owner.snapshot("rpc_active=true disk_confirmed=true");check(CaptureRecoveryFence.snapshot(base).contains("rpc_active=true"),"private snapshot readable without SD");
            owner.released();check(!other.blocked(),"confirmed cleanup permits fresh entry");owner.acquire();
            CaptureRecoveryFence reboot=new CaptureRecoveryFence(base,next,"after-reboot");check(!reboot.blocked(),"new kernel boot resets AV scope");reboot.acquire();owner.released();check(reboot.blocked(),"old owner cannot erase newer boot fence");reboot.released();
            write(new File(base,"CAPTURE.PENDING"),new byte[]{1,2});refused=false;try{other.blocked();}catch(IOException e){refused=true;}check(refused,"damaged record fails closed");
            System.out.println("PASS capture ownership fence: timeout, process death/reentry, owner-only cleanup, reboot, stale owner, corrupt record, private diagnostics");
        }finally{remove(base);}
    }
    private static void remove(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);file.delete();}
}
