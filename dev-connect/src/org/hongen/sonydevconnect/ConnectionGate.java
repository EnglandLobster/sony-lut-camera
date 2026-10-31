package org.hongen.sonydevconnect;

/** Serializes link mutations against Sony's ordered suspend notification. */
final class ConnectionGate {
    interface Action { void run() throws Exception; }
    static final class Cancelled extends Exception { }
    private long generation;
    private boolean suspended;

    synchronized long begin() throws Cancelled {
        if (suspended) throw new Cancelled();
        return generation;
    }
    synchronized boolean active(long token) {
        return !suspended && token == generation;
    }
    synchronized void check(long token) throws Cancelled {
        if (!active(token)) throw new Cancelled();
    }
    synchronized void mutate(long token, Action action) throws Exception {
        check(token);
        action.run();
    }
    synchronized void suspend() {
        suspended = true;
        ++generation;
    }
    synchronized void resume() { suspended = false; }
}
