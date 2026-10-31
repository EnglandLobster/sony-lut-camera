package org.hongen.sonylutprobe;

import java.io.*;

/** Last capture-start attempt, readable with a card reader even after native exit. */
final class CaptureDiagnostics {
    static final int MAX_BYTES=65536;
    private final File file;
    CaptureDiagnostics(File sd)throws IOException {
        File dir=new File(sd,"SONYLUT/DIAG");LutLibrary.ensureDirectory(dir);
        file=new File(dir,"INIT.TXT");
        OutputStream out=new FileOutputStream(file);out.close();
    }
    synchronized void append(String text)throws IOException {
        byte[] bytes=(text+"\n").getBytes("UTF-8");
        long remaining=MAX_BYTES-file.length();if(remaining<=0)return;
        OutputStream out=new FileOutputStream(file,true);
        try{out.write(bytes,0,(int)Math.min(bytes.length,remaining));out.flush();}finally{out.close();}
    }
    void failure(Throwable error)throws IOException {
        StringWriter text=new StringWriter();error.printStackTrace(new PrintWriter(text));append(text.toString());
    }
}
