package org.hongen.sonylutprobe;

public final class NativeExitGateTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        NativeExitGate gate=new NativeExitGate();long first=gate.begin();
        check(gate.current(first),"IPC check only runs for current request");
        check(gate.accept(first),"one cleanup acknowledgement may exit");
        check(!gate.accept(first),"duplicate callback cannot repeat exit");
        long timedOut=gate.begin();gate.cancel();
        check(!gate.current(timedOut),"late IPC check cannot start link cleanup");
        check(!gate.accept(timedOut),"late cleanup after timeout must not unexpectedly exit");
        long resumed=gate.begin();check(!gate.accept(timedOut),"old reply cannot consume new request");
        check(gate.accept(resumed),"new request remains usable");
        long paused=gate.begin();gate.cancel();check(!gate.accept(paused),"paused Activity cannot exit from late callback");
        System.out.println("NativeExitGateTest PASS: one acknowledgement, timeout, duplicate, pause and resumed generation");
    }
}
