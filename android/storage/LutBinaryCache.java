package org.hongen.sonylutprobe;
import java.io.*;
import java.security.MessageDigest;

/** The CUBE stays authoritative. The cache belongs to its managed import folder. */
final class LutBinaryCache {
    static File binary(File cube){return new File(cube.getParentFile(),"TABLE.BIN");}
    static String digest(File cube)throws Exception{
        MessageDigest hash=MessageDigest.getInstance("SHA-256");InputStream in=new FileInputStream(cube);
        try{byte[] buffer=new byte[32768];long total=0;int n;while((n=in.read(buffer))!=-1){
            total+=n;if(total>LutLibrary.MAX_BYTES)throw new IOException("LUT 超过 64 MB");hash.update(buffer,0,n);
        }}finally{in.close();}
        return LutLibrary.hex(hash.digest());
    }
    static int inspect(File cube)throws Exception{
        return Native.compileLut(cube.getPath(),binary(cube).getPath(),digest(cube));
    }
    static void verifySource(LutLibrary.Entry entry)throws Exception{
        if(!entry.id.equals(digest(entry.file)))throw new IOException("LUT 内容已变化，请从 LUTS 重新导入");
    }
}
