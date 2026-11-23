package io.github.englandlobster.lutcamera;

/** Session entry and exit choices without Android types, for host tests:
 * open straight into shooting, and the idle auto-exit that lets Sony's own
 * auto power-off take over. */
final class SessionPolicy {
    private SessionPolicy(){}
    static final String DIRECT_CAPTURE="direct_capture";
    static final String IDLE_EXIT="idle_exit_minutes";
    static final String EXIT_EXTRA="exit_app";
    static final int IDLE_DEFAULT=3;
    /** Unsafe moment (a write, the history, a list): look again this soon. */
    static final long IDLE_RECHECK_MS=5000;
    /** The Sony delete (trash) key, ISV_KEY_DELETE in OpenMemories ScalarInput;
     * expected from C4 on the A7 II (unconfirmed: check the exit_key log). */
    static final int DELETE_KEY=595;

    /** Off, 1 or 3 minutes; anything else is the default. */
    static int idleMinutes(int stored){return stored==0||stored==1||stored==3?stored:IDLE_DEFAULT;}
    static int nextIdle(int minutes){minutes=idleMinutes(minutes);return minutes==3?0:minutes==0?1:3;}
    static long idleMillis(int minutes){return idleMinutes(minutes)*60000L;}
    static String idleLabel(int minutes){minutes=idleMinutes(minutes);return minutes==0?"关":minutes+" 分钟";}

    /** Only the first library load of a fresh launch opens shooting; a return
     * from shooting, a capture lock or a missing LUT stays in the library. */
    static boolean autoEnter(boolean enabled,boolean firstLoad,boolean captureLocked,boolean lutSelected){
        return enabled&&firstLoad&&!captureLocked&&lutSelected;
    }

    static boolean exitKey(int key,int scan){return key==DELETE_KEY||scan==DELETE_KEY;}

    /** Inactivity deadline, re-armed by every key and shot. */
    static final class Idle {
        private final long timeout;
        private long last;
        Idle(long timeout,long now){this.timeout=Math.max(0,timeout);last=now;}
        boolean enabled(){return timeout>0;}
        void touch(long now){last=now;}
        long remaining(long now){return Math.max(0,last+timeout-now);}
        boolean due(long now){return enabled()&&now-last>=timeout;}
    }
}
