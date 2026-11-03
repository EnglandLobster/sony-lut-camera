#!/usr/bin/env python3
"""Run the actual Activity receipt callback against a no-index capture fixture.

The Android scheduling/clock is replaced by a synchronous host queue. The
callback body is extracted verbatim so an Activity-only status gate is tested.
Pass --baseline-ref to require that the old source stalls on receipt 1.
"""
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--baseline-ref')
args = parser.parse_args()
os.chdir(ROOT)
java = Path(os.environ.get('JAVA_HOME') or subprocess.check_output(
    ['/usr/libexec/java_home', '-v', '17'], text=True).strip()) / 'bin'
out = ROOT / 'out/receipt-dispatch'
out.mkdir(parents=True, exist_ok=True)

def source(path, ref):
    return subprocess.check_output(['git', 'show', ref + ':' + path], text=True) if ref else Path(path).read_text()

for label, ref in ([('baseline', args.baseline_ref)] if args.baseline_ref else []) + [('candidate', None)]:
    folder = out / label
    folder.mkdir(exist_ok=True)
    activity = source('android/capture/PreJpegProbeActivity.java', ref)
    start = activity.index('storeEvents=new CaptureStoreEvents(owner,new CaptureStoreEvents.Sink(){')
    end = activity.index('\n        if(!initOnly&&develops())', start)
    callback = activity[start:end]
    (folder / 'CaptureStoreEvents.java').write_text(source('android/capture/CaptureStoreEvents.java', ref))
    harness = '''package org.hongen.sonylutprobe;
import java.io.*;
import java.util.*;
public final class ReceiptDispatchReplay {
    static class SystemClock {static long elapsedRealtime(){return 41000;}}
    static class Queue {void post(Runnable r){r.run();}}
    static class Gate {long shot(){return 1;} String state(){return "ORIGINAL_SAVING";}}
    boolean closed;final Queue storeObserver=new Queue(),work=new Queue();final Gate gate=new Gate();
    final CaptureReceiptPipelineTest.Camera cameraEx=new CaptureReceiptPipelineTest.Camera();
    PhotoFiles photoFiles;CaptureStoreCompletion storeCompletion;CaptureStoreEvents storeEvents;
    CaptureStoreCompletion.Receipt receipt;
    void event(String name,String detail){}
    void failure(String stage,Throwable e){throw new AssertionError(stage+": "+e);}
    Throwable cause(Throwable e){return e;}
    void showStoreRecovery(){}
    void verifyStored(String directory,String file,long at){throw new AssertionError("unexpected fallback");}
    void pollStore(long token,long shot){
        try{CaptureStoreCompletion.Receipt r=storeCompletion.poll(token,41000);if(r!=null)receipt=r;}
        catch(Exception e){throw new AssertionError(e);}
    }
    void register()throws Exception{final Object owner=cameraEx;
CALLBACK
    }
    void run(File root,boolean baseline)throws Exception{
        File folder=new File(root,"DCIM/100MSDCF");if(!folder.mkdirs())throw new AssertionError("scratch");
        photoFiles=new PhotoFiles(root,PhotoFiles.Format.JPEG,false);photoFiles.expect(new CaptureGeometry(6000,4000));
        storeCompletion=new CaptureStoreCompletion(photoFiles,new CaptureStoreCompletion.Registration(){
            public void begin(){} public PhotoIndex.Entry saved(){return null;} public void accepted(File f){}
        },true);
        register();int number=0;
        for(int id:new int[]{0,1,2,41})for(int role=0;role<2;role++){
            long token=role==0?storeCompletion.begin(0):storeCompletion.beginFinalStore(0);
            storeCompletion.rpcStarted(token);storeCompletion.rpcReturned(token);receipt=null;
            File file=new File(folder,String.format(Locale.US,"DSC%05d.JPG",++number));
            CapturePipelineTest.write(file,CapturePipelineTest.jpeg(6000,4000,true));
            cameraEx.saved(id,file);
            for(int n=0;n<5&&receipt==null;n++)pollStore(token,1);
            if(receipt==null){
                if(baseline&&id==1&&role==0){System.out.println("PASS actual RC7 Activity callback reproduces stall on second capture, receipt 1");return;}
                throw new AssertionError("Activity discarded receipt "+id+" role="+role);
            }
            if(receipt.recovered)throw new AssertionError("must use named callback");
            photoFiles.accepted(receipt.file);
        }
        if(baseline)throw new AssertionError("baseline unexpectedly passed");
        System.out.println("PASS actual candidate Activity callback: four consecutive pairs with IDs 0/1/2/41, index absent");
    }
    public static void main(String[] args)throws Exception{
        File root=File.createTempFile("dispatch-","");root.delete();
        try{new ReceiptDispatchReplay().run(root,Boolean.parseBoolean(args[0]));}finally{CapturePipelineTest.remove(root);}
    }
}
'''.replace('CALLBACK', callback)
    (folder / 'ReceiptDispatchReplay.java').write_text(harness)
    subprocess.run([str(java / 'javac'), '--release', '7', '-Xlint:-options', '-encoding', 'UTF-8',
                    '-cp', 'out/capture-pipeline/classes', '-d', str(folder),
                    str(folder / 'CaptureStoreEvents.java'), str(folder / 'ReceiptDispatchReplay.java')], check=True)
    subprocess.run([str(java / 'java'), '-Xmx24m', '-cp', str(folder) + os.pathsep + 'out/capture-pipeline/classes',
                    'org.hongen.sonylutprobe.ReceiptDispatchReplay', str(bool(ref)).lower()], check=True)
