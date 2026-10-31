package org.hongen.sonydevconnect;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DebugLifecycleTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void rejects(ConnectionGate gate, long token) throws Exception {
        final AtomicBoolean called = new AtomicBoolean();
        try {
            gate.mutate(token, new ConnectionGate.Action() { public void run() { called.set(true); } });
            throw new AssertionError("stale request accepted");
        } catch (ConnectionGate.Cancelled expected) { }
        check(!called.get(), "cancelled request changed link state");
    }
    private static void gateTest() throws Exception {
        final ConnectionGate gate = new ConnectionGate();
        final long token = gate.begin();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final CountDownLatch suspending = new CountDownLatch(1), suspended = new CountDownLatch(1);
        final AtomicBoolean actionDone = new AtomicBoolean(), failed = new AtomicBoolean();
        Thread worker = new Thread(new Runnable() { public void run() {
            try { gate.mutate(token, new ConnectionGate.Action() { public void run() throws Exception {
                entered.countDown();
                if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                actionDone.set(true);
            } }); } catch (Exception e) { failed.set(true); }
        } });
        Thread stopper = new Thread(new Runnable() { public void run() {
            suspending.countDown(); gate.suspend();
            if (!actionDone.get()) failed.set(true);
            suspended.countDown();
        } });
        worker.start();
        check(entered.await(1, TimeUnit.SECONDS), "mutation did not start");
        stopper.start();
        try {
            check(suspending.await(1, TimeUnit.SECONDS), "stopper did not start");
            check(!suspended.await(40, TimeUnit.MILLISECONDS), "suspend raced a live mutation");
        } finally { release.countDown(); }
        check(suspended.await(1, TimeUnit.SECONDS), "suspend did not complete");
        worker.join(1000); stopper.join(1000);
        check(!failed.get(), "incorrect mutation/cleanup order");
        rejects(gate, token);
        try { gate.begin(); throw new AssertionError("connection started during suspend"); }
        catch (ConnectionGate.Cancelled expected) { }
        gate.resume();
        rejects(gate, token); // An old worker must not revive after a quick resume.
        check(gate.active(gate.begin()), "fresh explicit connection blocked after resume");
    }
    private static void shutdownTest() throws Exception {
        // Simulated processes only. The shell kill function below records calls;
        // no real host or camera process is signalled by this test.
        String fake = "a=1; b=1; c=1\n" +
            "busybox() { case \"$1\" in\n" +
            "pidof) echo 101 202 303;;\n" +
            "readlink) case \"$2\" in\n" +
            "/proc/101/exe) test $a = 1 && echo \"$expected\";;\n" +
            "/proc/202/exe) echo /android/data/lib/another.package/lib/libadbd.so;;\n" +
            "/proc/303/exe) test $c = 1 && echo \"$expected (deleted)\";;\n" +
            "esac;; usleep) :;; *) return 2;; esac; }\n" +
            "kill() { echo \"$1 $2\"; case \"$2\" in\n" +
            "101) a=0;; 303) test \"$1\" != -KILL || c=0;; *) exit 9;; esac; }\n";
        String script = fake + AdbShutdown.script("/android/data/lib/test'package/lib/libadbd.so") +
            "test $a = 0 && test $b = 1 && test $c = 0\n";
        Process child = new ProcessBuilder("/bin/sh").redirectErrorStream(true).start();
        OutputStream input = child.getOutputStream(); input.write(script.getBytes("UTF-8")); input.close();
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buf = new byte[512]; int n;
        while ((n = child.getInputStream().read(buf)) >= 0) output.write(buf, 0, n);
        check(child.waitFor() == 0, "daemon ownership or quoting failure");
        check("-TERM 101\n-TERM 303\n-KILL 303\n".equals(output.toString("UTF-8")),
            "unexpected target or signal order: " + output.toString("UTF-8"));
    }
    public static void main(String[] args) throws Exception {
        gateTest(); shutdownTest();
        System.out.println("PASS suspend/mutation ordering, stale worker cancellation, resume, owned-only daemon cleanup, deleted executable and bounded forced-stop path");
    }
}
