package org.hongen.sonylutprobe;

import java.io.*;
import java.util.zip.*;

/** Validate cached fixtures against their APK entry; publish only a complete copy. */
final class Assets {
    private static long crc(File file) throws IOException {
        InputStream in=new FileInputStream(file);CRC32 crc=new CRC32();
        try {byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)crc.update(b,0,n);}
        finally {in.close();}
        return crc.getValue();
    }
    static synchronized File extract(File apk,String name,File directory) throws IOException {
        if(!name.matches("[A-Z0-9_]{1,8}\\.[A-Z0-9]{1,3}"))throw new IOException("invalid asset name");
        ZipFile zip=new ZipFile(apk);
        try {
            ZipEntry entry=zip.getEntry("assets/"+name);
            if(entry==null)throw new IOException("missing APK asset: "+name);
            File dst=new File(directory,name);
            if(dst.isFile() && dst.length()==entry.getSize() && crc(dst)==entry.getCrc())return dst;
            File temp=new File(directory,name.substring(0,name.indexOf('.'))+".TMP");
            if(temp.exists() && !temp.delete())throw new IOException("cannot remove interrupted asset stage");
            if(!temp.createNewFile())throw new IOException("cannot create asset stage");
            try {
                InputStream in=zip.getInputStream(entry);
                long count=0;CRC32 crc=new CRC32();
                try {
                    FileOutputStream out=new FileOutputStream(temp);
                    try {byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){out.write(b,0,n);crc.update(b,0,n);count+=n;}out.flush();}
                    finally {out.close();}
                }finally {in.close();}
                if(count!=entry.getSize() || crc.getValue()!=entry.getCrc())
                    throw new IOException("incomplete APK asset: "+name+" bytes="+count+" expected="+entry.getSize());
                // The existing target is already known invalid; Sony's FAT layer
                // needs it removed before rename. A valid cache is returned above.
                if(dst.exists() && !dst.delete())throw new IOException("cannot remove invalid asset: "+name);
                if(!temp.renameTo(dst))throw new IOException("cannot publish asset: "+name);
                return dst;
            }finally {if(temp.exists())temp.delete();}
        }finally {zip.close();}
    }
}
