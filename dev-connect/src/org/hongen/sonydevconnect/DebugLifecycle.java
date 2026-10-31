package org.hongen.sonydevconnect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;

/** Releases our link before Sony snapshots and unmounts Android's data volume. */
public final class DebugLifecycle extends BroadcastReceiver {
    static final ConnectionGate GATE = new ConnectionGate();
    static final String SUSPEND = "com.android.server.SyncAndroidService.SUSPEND";
    static final String RESUME = "com.android.server.SyncAndroidService.RESUME";

    @Override public void onReceive(Context context, Intent intent) {
        if (RESUME.equals(intent.getAction())) {
            GATE.resume();
            Log.i("SonyDevConnect", "system_resume");
            return;
        }
        if (!SUSPEND.equals(intent.getAction())) return;
        // Any connection operation that already started finishes before this
        // barrier. Its token then expires, so it cannot re-enable Wi-Fi later.
        GATE.suspend();
        Log.i("SonyDevConnect", "system_suspend cleanup_begin");
        try {
            stopLink(context, "system_suspend", false);
        } catch (Exception error) {
            Log.e("SonyDevConnect", "system_suspend cleanup_failed type=" + error.getClass().getSimpleName());
        }
    }

    /** The Menu path calls this while Android is still active, before finish(). */
    static void stopLink(Context context, String reason, boolean waitForWifi) throws Exception {
        long started = SystemClock.elapsedRealtime();
        Exception adbError = null;
        try {
            RootTask.run(context, AdbShutdown.script(
                "/android" + context.getApplicationInfo().nativeLibraryDir + "/libadbd.so"));
            Log.i("SonyDevConnect", reason + " owned_adb_stopped");
        } catch (Exception error) {
            adbError = error;
        }
        // Even when daemon cleanup fails, release Wi-Fi before reporting it.
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) throw new IOException("无法访问 Wi-Fi 服务");
        if (wifi.getWifiState() != WifiManager.WIFI_STATE_DISABLED && !wifi.setWifiEnabled(false))
            throw new IOException("Wi-Fi 关闭请求被拒绝");
        Log.i("SonyDevConnect", reason + " wifi_disable_requested");
        if (waitForWifi) {
            long deadline = SystemClock.elapsedRealtime() + 4000;
            while (wifi.getWifiState() != WifiManager.WIFI_STATE_DISABLED && SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(50);
            if (wifi.getWifiState() != WifiManager.WIFI_STATE_DISABLED)
                throw new IOException("Wi-Fi 关闭超时，尚未退出应用");
            Log.i("SonyDevConnect", reason + " wifi_disabled");
        }
        if (adbError != null) throw new IOException("ADB 停止失败，尚未退出应用");
        Log.i("SonyDevConnect", reason + " cleanup_ms=" + (SystemClock.elapsedRealtime() - started));
    }
}
