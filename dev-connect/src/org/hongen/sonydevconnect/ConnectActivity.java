package org.hongen.sonydevconnect;

import android.app.Activity;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.SupplicantState;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.github.ma1co.openmemories.tweak.Shell;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Properties;

/** Explicitly opened development connector; no boot receiver or camera API. */
public final class ConnectActivity extends Activity {
    private static final String NETWORK = "he_2.4G_test";
    // /data/system and /data/data are RAM disks on this A6300. /data itself is
    // persistent flash. Use our own 8.3 directory and owner-only profile.
    private static final File DIR = new File("/data/SONYDEV");
    private static final File PROFILE = new File(DIR,"NET.CFG");
    private final Handler ui = new Handler();
    private HandlerThread thread;
    private Handler work;
    private WifiManager wifi;
    private TextView status;
    private Button reconnect;
    private volatile boolean destroyed, busy;
    private final ExitVisit visit=new ExitVisit();
    private boolean foreground;
    private Runnable exitTimeout;
    private static final ConnectionGate GATE = DebugLifecycle.GATE;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        wifi = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(16,10,16,10);
        TextView title = new TextView(this); title.setText("测试连接"); title.setTextSize(23); page.addView(title);
        status = new TextView(this); status.setTextSize(17); status.setText("正在准备连接…"); page.addView(status,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout buttons = new LinearLayout(this);
        reconnect = new Button(this); reconnect.setText("重新连接"); reconnect.setOnClickListener(new View.OnClickListener(){public void onClick(View v){connect();}});
        buttons.addView(reconnect,new LinearLayout.LayoutParams(0,-2,1));
        Button capture = new Button(this); capture.setText("打开 LUT Camera"); capture.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            if(busy||visit.exiting())return;
            Intent next=getPackageManager().getLaunchIntentForPackage("org.hongen.sonylutprobe");
            if(next!=null)startActivity(next);else show("未安装 LUT Camera");
        }}); buttons.addView(capture,new LinearLayout.LayoutParams(0,-2,1)); page.addView(buttons); setContentView(page);
        thread = new HandlerThread("dev-connect"); thread.start(); work = new Handler(thread.getLooper()); connect();
    }
    private void show(final String message){ui.post(new Runnable(){public void run(){if(!destroyed&&!visit.exiting())status.setText(message);}});}
    private void show(final long token,final String message){ui.post(new Runnable(){public void run(){if(!destroyed&&!visit.exiting()&&GATE.active(token))status.setText(message);}});}
    private void connect(){
        if(busy||destroyed||visit.exiting())return;
        final long token;
        try{token=GATE.begin();}catch(ConnectionGate.Cancelled e){return;}
        busy=true; reconnect.setEnabled(false);
        work.post(new Runnable(){public void run(){try{runConnection(token);}catch(ConnectionGate.Cancelled e){
            android.util.Log.i("SonyDevConnect","connection_cancelled_for_suspend");
        }catch(Exception e){
            // Do not log system configuration or exception payloads that might
            // contain credentials. Our own state errors carry no secrets.
            android.util.Log.e("SonyDevConnect","connection_failed type="+e.getClass().getSimpleName());
            show(token,"连接未完成\n"+(e instanceof IOException?e.getMessage():"请保留此页，读取诊断状态")+"\n可按“重新连接”重试。");
        }finally{busy=false;ui.post(new Runnable(){public void run(){if(!destroyed&&!visit.exiting()&&GATE.active(token))reconnect.setEnabled(true);}});}}});
    }
    private void runConnection(final long token) throws Exception {
        GATE.check(token);
        WpaProfile profile=load();
        GATE.check(token);
        if(profile!=null) {
            if(!NETWORK.equals(WpaProfile.unquote(profile.ssid)))throw new IOException("保存的网络与测试网络不一致");
            android.util.Log.i("SonyDevConnect","profile_loaded persistent=true");
        }else{
            WifiInfo info=wifi.getConnectionInfo();
            if(!connected(info))
                throw new IOException("首次设置：先用 Tweak 连上 "+NETWORK+"，再打开此应用一次");
            show(token,"正在记住当前测试网络…");profile=importCurrent();save(profile);
            android.util.Log.i("SonyDevConnect","profile_saved persistent=true");
        }
        GATE.check(token);
        if(!wifi.isWifiEnabled()){
            show(token,"正在开启 Wi-Fi…");
            GATE.mutate(token,new ConnectionGate.Action(){public void run()throws Exception{
                if(!wifi.setWifiEnabled(true))throw new IOException("Wi-Fi 开启请求被拒绝");
            }});
            long end=SystemClock.elapsedRealtime()+12000;
            while(!wifi.isWifiEnabled()&&SystemClock.elapsedRealtime()<end&&!destroyed&&GATE.active(token))SystemClock.sleep(200);
            GATE.check(token);
            if(!wifi.isWifiEnabled())throw new IOException("Wi-Fi 开启超时");
        }
        WifiInfo current=wifi.getConnectionInfo();
        if(!connected(current)){
            show(token,"正在连接 "+NETWORK+"…");
            final WpaProfile selected=profile;
            GATE.mutate(token,new ConnectionGate.Action(){public void run()throws Exception{
                WifiConfiguration config=new WifiConfiguration();config.SSID=selected.ssid;config.preSharedKey=selected.psk;
                config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                List<WifiConfiguration> known=wifi.getConfiguredNetworks();
                if(known!=null)for(WifiConfiguration c:known)if(selected.ssid.equals(c.SSID)){config.networkId=c.networkId;break;}
                int id=config.networkId<0?wifi.addNetwork(config):wifi.updateNetwork(config);
                if(id<0)throw new IOException("系统未接受测试网络配置");
                if(!wifi.saveConfiguration())throw new IOException("系统未保存网络配置");
                if(!wifi.enableNetwork(id,true)||!wifi.reconnect())throw new IOException("系统未启动网络连接");
            }});
        }
        long end=SystemClock.elapsedRealtime()+25000;
        while(!destroyed&&GATE.active(token)&&SystemClock.elapsedRealtime()<end){
            current=wifi.getConnectionInfo();
            if(connected(current))break;
            SystemClock.sleep(250);
        }
        GATE.check(token);
        if(destroyed)return;
        if(!connected(current))throw new IOException("连接测试网络超时；请检查 AP 是否开启");
        show(token,"Wi-Fi 已连接，正在启动调试服务…");
        GATE.mutate(token,new ConnectionGate.Action(){public void run()throws Exception{
            if(!listening(23))Shell.exec("busybox telnetd -l sh");
            if(!listening(5555))Shell.execAndroid(quote(getApplicationInfo().nativeLibraryDir+"/libadbd.so")+" &");
        }});
        end=SystemClock.elapsedRealtime()+5000;
        boolean adb=false,telnet=false;
        do{adb=listening(5555);telnet=listening(23);if(adb&&telnet)break;SystemClock.sleep(150);}while(SystemClock.elapsedRealtime()<end&&!destroyed&&GATE.active(token));
        GATE.check(token);
        if(!adb||!telnet)throw new IOException("Wi-Fi 已连接，但调试服务未全部就绪");
        String ip=Formatter.formatIpAddress(current.getIpAddress());
        android.util.Log.i("SonyDevConnect","ready ip="+ip+" adb=5555 telnet=23");
        show(token,"已连接 "+NETWORK+"\nIP："+ip+"\nADB 5555  ·  Telnet 23\n下次打开此应用会自动连接。");
    }
    private WpaProfile importCurrent() throws Exception {
        final File ready=new File(getCacheDir(),"NET.IN");
        if(ready.exists()&&!ready.delete())throw new IOException("无法清理导入临时文件");
        try{
            RootTask.copyToPrivate(this,"/android/data/system/wpa_supplicant.conf",ready,false);
            if(!ready.isFile()||ready.length()>65536)throw new IOException("未能导入当前测试网络");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();FileInputStream in=new FileInputStream(ready);
            try{byte[] buf=new byte[2048];int n;while((n=in.read(buf))>0){bytes.write(buf,0,n);if(bytes.size()>65536)throw new IOException("网络配置过大");}}finally{in.close();}
            return WpaProfile.select(new String(bytes.toByteArray(),"UTF-8"),NETWORK);
        }finally{ready.delete();}
    }
    private WpaProfile load() throws Exception {
        File privateCopy=new File(getCacheDir(),"LOAD.CFG");
        try{
            // This flash mount assigns uid=system regardless of chown. Keep
            // 0700/0600 there and use the root bridge; never make it world-readable.
            RootTask.copyToPrivate(this,RootTask.nativePath(PROFILE),privateCopy,true);
            if(!privateCopy.isFile())return null;
            if(privateCopy.length()>2048)throw new IOException("保存的网络配置格式不正确");
            Properties p=new Properties();FileInputStream in=new FileInputStream(privateCopy);try{p.load(in);}finally{in.close();}
            return new WpaProfile(p.getProperty("ssid"),p.getProperty("psk"));
        }finally{privateCopy.delete();}
    }
    private void save(WpaProfile profile) throws Exception {
        File next=new File(getCacheDir(),"SAVE.CFG");
        try{
            FileOutputStream out=new FileOutputStream(next);try{profile.properties().store(out,"Sony development network");out.flush();}finally{out.close();}
            String dir=quote(RootTask.nativePath(DIR)),temp=quote(RootTask.nativePath(new File(DIR,"NET.NEW")));
            RootTask.run(this,"umask 077 && mkdir -p "+dir+" && chmod 700 "+dir+
                " && cp "+quote(RootTask.nativePath(next))+" "+temp+" && chmod 600 "+temp+
                " && sync && mv "+temp+" "+quote(RootTask.nativePath(PROFILE))+" && sync");
        }finally{next.delete();}
    }
    private static String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
    private static String cleanSsid(String value){if(value==null)return "";try{return WpaProfile.unquote(value);}catch(IOException e){return value;}}
    private static boolean connected(WifiInfo info){return info!=null&&info.getIpAddress()!=0&&info.getSupplicantState()==SupplicantState.COMPLETED&&NETWORK.equals(cleanSsid(info.getSSID()));}
    private boolean listening(int port){Socket socket=new Socket();try{socket.connect(new InetSocketAddress("127.0.0.1",port),400);return true;}catch(IOException e){return false;}finally{try{socket.close();}catch(IOException e){}}}
    private void enterVisit(boolean explicitLaunch){
        boolean recovered=visit.enter(explicitLaunch);
        if(recovered){
            if(exitTimeout!=null)ui.removeCallbacks(exitTimeout);
            status.setText("正在恢复连接…");reconnect.setEnabled(true);
            android.util.Log.i("SonyDevConnect","foreground_reentry exit_state_reset=true");
        }
        if(!visit.exiting()){GATE.resume();if(work!=null)connect();}
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);enterVisit(true);
    }
    @Override protected void onResume(){super.onResume();foreground=true;enterVisit(false);}
    @Override protected void onPause(){foreground=false;visit.leftForeground();super.onPause();}
    private void exitFailed(long token,String message){
        if(destroyed||!visit.fail(token))return;
        if(exitTimeout!=null)ui.removeCallbacks(exitTimeout);
        GATE.resume();reconnect.setEnabled(true);status.setText(message);
    }
    @Override public void finish(){
        if(visit.exiting())return;
        if(work==null||destroyed){super.finish();return;}
        final long token=visit.begin();
        GATE.suspend();reconnect.setEnabled(false);status.setText("正在返回拍摄…");
        android.util.Log.i("SonyDevConnect","user_exit cleanup_begin");
        work.post(new Runnable(){public void run(){
            try{
                DebugLifecycle.stopLink(ConnectActivity.this,"user_exit",true);
                ui.post(new Runnable(){public void run(){
                    if(destroyed)return;
                    ExitVisit.Completion completion=visit.cleaned(token);
                    if(completion==ExitVisit.Completion.STALE)return;
                    if(completion==ExitVisit.Completion.RECONNECT){
                        android.util.Log.i("SonyDevConnect","reopen_after_cleanup obsolete_exit_skipped=true");
                        enterVisit(false);return;
                    }
                    try{
                        Class<?> type=Class.forName("android.app.DAConnectionManager");
                        Object manager=type.getConstructor(android.content.Context.class).newInstance(ConnectActivity.this);
                        android.util.Log.i("SonyDevConnect","user_exit native_finish_requested");
                        type.getMethod("finish").invoke(manager);
                        exitTimeout=new Runnable(){public void run(){
                            if(foreground&&visit.waitingForNative(token)){
                                android.util.Log.e("SonyDevConnect","user_exit native_finish_timeout");
                                exitFailed(token,"相机未完成退出\n可点重新连接恢复调试，或按 Menu 重试。");
                            }
                        }};
                        ui.postDelayed(exitTimeout,15000);
                    }catch(Exception error){
                        android.util.Log.e("SonyDevConnect","user_exit native_finish_failed",error);
                        exitFailed(token,"相机未完成退出\n可点重新连接恢复调试，或按 Menu 重试。");
                    }
                }});
            }catch(final Exception error){
                android.util.Log.e("SonyDevConnect","user_exit cleanup_failed type="+error.getClass().getSimpleName());
                ui.post(new Runnable(){public void run(){exitFailed(token,"未完成退出\n"+
                    (error instanceof IOException?error.getMessage():"清理连接时发生错误")+
                    "\n可按 Menu 重试，或点“重新连接”恢复调试。");}});
            }
        }});
    }
    @Override protected void onDestroy(){destroyed=true;visit.destroy();if(exitTimeout!=null)ui.removeCallbacks(exitTimeout);if(thread!=null)thread.quit();super.onDestroy();}
    @Override public boolean onKeyDown(int key,KeyEvent event){if(key==KeyEvent.KEYCODE_MENU){finish();return true;}return super.onKeyDown(key,event);}
}
