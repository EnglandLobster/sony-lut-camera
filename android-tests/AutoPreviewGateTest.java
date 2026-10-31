package org.hongen.sonylutprobe;

public final class AutoPreviewGateTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static final class Rig implements AutoPreviewGate.Driver,AutoPreviewGate.Listener {
        AutoPreviewGate gate=new AutoPreviewGate(this,this);Runnable stopped;
        int applies,enables,failures,colors,readies;String fail="",order="";
        void step(String name)throws Exception{order+=name+";";if(fail.equals(name))throw new Exception(name);}
        public void lock()throws Exception{step("lock");}
        public void stopDirect(Runnable done)throws Exception{step("stop");stopped=done;}
        public void apply()throws Exception{step("apply");applies++;}
        public void restore()throws Exception{step("restore");}
        public void restartPreview()throws Exception{step("restart");}
        public void enable()throws Exception{step("enable");enables++;}
        public void colored(){colors++;}
        public void ready(){readies++;}
        public void failed(Exception e){failures++;}
    }
    public static void main(String[] args){
        Rig r=new Rig();r.gate.start();check(r.applies==1&&r.enables==0,"color starts behind native lock");
        r.gate.previewReady();check(r.enables==0,"initial/duplicate preview callback cannot unlock");
        r.gate.press();r.gate.press();check(r.order.equals("lock;apply;restore;restart;"),"restore once before native ack");
        r.gate.previewReady();r.gate.previewReady();check(r.enables==1&&r.gate.ready(),"single enable after ack");
        check(r.gate.shotStarted(),"physical shot accepted");r.gate.release();check(r.applies==1,"no color changes during capture/save");
        r.gate.shotFinished();check(r.applies==1&&!r.gate.ready(),"await native stop before next preview");
        Runnable old=r.stopped;old.run();old.run();check(r.applies==2,"stop callback applies once");
        r.gate.press();r.gate.previewReady();check(r.enables==2,"second shot can prepare");
        r.gate.release();old.run();check(r.applies==2,"stale old stop cannot apply color");r.stopped.run();
        r.gate.press();r.gate.release();r.gate.previewReady();check(r.enables==2,"released half press never enables late capture");
        for(String failure:new String[]{"restore","restart","enable","stop","apply"}){
            Rig b=new Rig();b.gate.start();b.fail=failure;b.gate.press();b.gate.previewReady();
            if(b.gate.ready())b.gate.release();if(b.stopped!=null)b.stopped.run();
            check(b.failures==1&&!b.gate.ready(),"fail closed: "+failure);
        }
        Rig timeout=new Rig();timeout.gate.start();timeout.gate.press();long token=timeout.gate.generation();
        timeout.gate.timeout(token);timeout.gate.previewReady();check(timeout.enables==0&&timeout.failures==1,"timeout rejects late ready");
        Rig paused=new Rig();paused.gate.start();paused.gate.press();paused.gate.previewReady();paused.gate.release();
        paused.gate.close();paused.stopped.run();check(paused.applies==1,"pause rejects late stop");
        Rig stopTimeout=new Rig();stopTimeout.gate.start();stopTimeout.gate.press();stopTimeout.gate.previewReady();stopTimeout.gate.release();
        stopTimeout.gate.timeout(stopTimeout.gate.generation());stopTimeout.stopped.run();
        check(stopTimeout.applies==1&&stopTimeout.failures==1,"stop timeout rejects late callback and keeps baseline");
        Rig cancel=new Rig();cancel.gate.start();cancel.gate.press();cancel.gate.previewReady();cancel.gate.shotStarted();cancel.gate.shotCanceled();
        check(cancel.applies==1,"cancel waits for stop");cancel.stopped.run();check(cancel.applies==2,"cancel returns color safely");
        System.out.println("AutoPreviewGateTest PASS: half-press, release, save barrier, continuous shots, cancel, failures, stale callbacks and timeout");
    }
}
