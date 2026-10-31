package org.hongen.sonydevconnect;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import com.github.ma1co.openmemories.tweak.Shell;
import java.io.*;

/** Fixed local file operations via the existing Tweak shell bridge. */
final class RootTask {
    static String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
    static String nativePath(File file){return "/android"+file.getPath();}
    static synchronized void run(Context context,String body)throws Exception{
        File script=new File(context.getCacheDir(),"TASK.SH"),done=new File(context.getCacheDir(),"TASK.OK");
        File result=new File(context.getCacheDir(),"TASK.NEW");
        done.delete();result.delete();
        String target=quote(nativePath(result));
        String text="#!/bin/sh\n(\n"+body+"\n) >/dev/null 2>&1\nr=$?\n"+
            "echo \"$r\" > "+target+"\nchown "+Process.myUid()+":"+Process.myUid()+" "+target+
            "\nchmod 600 "+target+"\nmv "+target+" "+quote(nativePath(done))+"\n";
        FileOutputStream out=new FileOutputStream(script);try{out.write(text.getBytes("UTF-8"));out.flush();}finally{out.close();}
        try{
            Shell.exec("sh "+quote(nativePath(script)));
            long end=SystemClock.elapsedRealtime()+4000;
            while(!done.isFile()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);
            if(!done.isFile()||done.length()>16)throw new IOException("相机配置操作超时");
            BufferedReader in=new BufferedReader(new FileReader(done));String code;try{code=in.readLine();}finally{in.close();}
            if(!"0".equals(code))throw new IOException("相机配置操作失败（代码 "+code+"）");
        }finally{script.delete();done.delete();result.delete();}
    }
    static void copyToPrivate(Context context,String source,File destination,boolean optional)throws Exception{
        File temp=new File(destination.getParentFile(),"COPY.TMP");destination.delete();temp.delete();
        String command="cp "+quote(source)+" "+quote(nativePath(temp))+
            " && chown "+Process.myUid()+":"+Process.myUid()+" "+quote(nativePath(temp))+
            " && chmod 600 "+quote(nativePath(temp))+" && mv "+quote(nativePath(temp))+" "+quote(nativePath(destination));
        if(optional)command="if test -f "+quote(source)+"; then "+command+"; fi";
        try{run(context,command);}finally{temp.delete();}
    }
}
