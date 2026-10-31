package org.hongen.sonydevconnect;

/** Stop only this package's daemon, never another application's ADB process. */
final class AdbShutdown {
    static String script(String nativeExecutable) {
        String quoted = "'" + nativeExecutable.replace("'", "'\\''") + "'";
        return "expected=" + quoted + "\n" +
            "owned() { actual=$(busybox readlink /proc/\"$1\"/exe); " +
                "test \"$actual\" = \"$expected\" || test \"$actual\" = \"$expected (deleted)\"; }\n" +
            "for pid in $(busybox pidof libadbd.so adbd); do\n" +
            "  if owned \"$pid\"; then\n" +
            "    kill -TERM \"$pid\" 2>/dev/null || true\n" +
            "    i=0; while owned \"$pid\" && test $i -lt 20; do busybox usleep 50000; i=$((i+1)); done\n" +
            "    if owned \"$pid\"; then kill -KILL \"$pid\" 2>/dev/null || true; fi\n" +
            "    i=0; while owned \"$pid\" && test $i -lt 10; do busybox usleep 50000; i=$((i+1)); done\n" +
            "    if owned \"$pid\"; then exit 1; fi\n" +
            "  fi\n" +
            "done\n";
    }
}
