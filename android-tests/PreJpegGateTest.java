package org.hongen.sonylutprobe;
public final class PreJpegGateTest {
    private static void check(boolean b){if(!b)throw new AssertionError();}
    private static void rejects(Runnable r){try{r.run();throw new AssertionError("accepted invalid transition");}catch(IllegalStateException expected){}}
    private static void defaults(PreJpegGate g){g.preview();g.registering();g.defaultRegistered();g.defaultUnlocked();}
    private static void ready(PreJpegGate g){defaults(g);g.configuring();g.registered(false);}
    public static void main(String[] args){
        final PreJpegGate g=new PreJpegGate();check(!g.arm());
        rejects(new Runnable(){public void run(){g.registering();}});
        rejects(new Runnable(){public void run(){g.registered(false);}});
        ready(g);
        rejects(new Runnable(){public void run(){g.saved();}});check(g.arm());check(!g.arm());
        check(g.busy());g.raw();rejects(new Runnable(){public void run(){g.raw();}});
        rejects(new Runnable(){public void run(){g.saved();}});g.saving();g.saved();check(!g.busy());check(!g.arm());
        rejects(new Runnable(){public void run(){g.saved();}});
        final PreJpegGate init=new PreJpegGate();defaults(init);check(!init.arm());init.defaultsComplete();
        check(init.state()==PreJpegGate.State.INITIALIZED&&!init.arm());
        rejects(new Runnable(){public void run(){init.raw();}});
        rejects(new Runnable(){public void run(){init.configuring();}});
        final PreJpegGate config=new PreJpegGate();config.preview();config.registering();
        rejects(new Runnable(){public void run(){config.configuring();}});
        rejects(new Runnable(){public void run(){config.registered(false);}});
        config.defaultRegistered();check(!config.arm());
        rejects(new Runnable(){public void run(){config.configuring();}});
        config.defaultUnlocked();check(!config.arm());config.configuring();check(!config.arm());config.registered(true);
        check(config.state()==PreJpegGate.State.INITIALIZED&&!config.arm());
        final PreJpegGate failed=new PreJpegGate();ready(failed);failed.arm();failed.fail();check(!failed.busy()&&!failed.arm());
        rejects(new Runnable(){public void run(){failed.preview();}});
        final PreJpegGate pair=new PreJpegGate();ready(pair);pair.arm();pair.raw();
        rejects(new Runnable(){public void run(){pair.originalSaved();}});
        rejects(new Runnable(){public void run(){pair.savingGraded();}});
        pair.savingOriginal();check(pair.busy()&&!pair.arm());
        rejects(new Runnable(){public void run(){pair.saved();}});
        rejects(new Runnable(){public void run(){pair.savingGraded();}});
        pair.originalSaved();check(pair.busy());
        rejects(new Runnable(){public void run(){pair.originalSaved();}});
        pair.savingGraded();check(pair.busy());pair.saved();check(!pair.busy()&&!pair.arm());
        rejects(new Runnable(){public void run(){pair.savingOriginal();}});
        long first=pair.shot();check(first==1);check(!pair.beginRearm(first+1));
        check(pair.beginRearm(first));check(!pair.arm());check(!pair.beginRearm(first));
        check(!pair.rearmed(first+1));check(pair.rearmed(first));check(!pair.rearmed(first));
        check(pair.arm());long second=pair.shot();check(second==2);
        check(!pair.busyShot(first)&&pair.busyShot(second));
        check(!pair.beginRearm(first)&&!pair.rearmed(first));
        pair.raw();pair.savingOriginal();pair.originalSaved();pair.savingGraded();pair.saved();
        check(pair.beginRearm(second));pair.fail();check(!pair.rearmed(second)&&!pair.arm());
        // Replay the device incident: full press -> CANCELED -> OK -> RAW.
        final PreJpegGate canceled=new PreJpegGate();ready(canceled);
        check(!canceled.cancelShutter());check(canceled.arm());long canceledShot=canceled.shot();
        check(canceled.cancelShutter());check(!canceled.busyShot(canceledShot));
        check(canceled.state()==PreJpegGate.State.READY);check(!canceled.cancelShutter());
        check(canceled.arm());check(canceled.shot()==canceledShot+1);check(!canceled.busyShot(canceledShot));
        canceled.raw();check(!canceled.cancelShutter());check(canceled.state()==PreJpegGate.State.RAW);
        canceled.savingOriginal();check(!canceled.cancelShutter());canceled.originalSaved();
        canceled.savingGraded();check(!canceled.cancelShutter());canceled.saved();
        check(canceled.beginRearm(canceled.shot()));check(!canceled.cancelShutter());
        check(canceled.rearmed(canceled.shot()));check(canceled.arm());canceled.fail();
        check(!canceled.cancelShutter()&&!canceled.arm());
        System.out.println("PASS cancellation retry, stale timeout rejection, late cancellation cannot unlock RAW/save or failure");
        System.out.println("PASS two-stage initialization, single shot, terminal failure, original completion required before graded store, both saves required before completion");
        System.out.println("PASS repeated capture, explicit preview/unlock barrier, stale timeout/rearm rejection, failure remains locked");
    }
}
