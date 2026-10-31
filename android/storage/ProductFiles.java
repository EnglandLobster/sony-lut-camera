package org.hongen.sonylutprobe;
import android.os.Environment;
import java.io.File;
/** Sony's app data is a tiny RAM-backed partition. User content lives on SD. */
final class ProductFiles {
    private static File root(){return new File(Environment.getExternalStorageDirectory(),"SONYLUT");}
    static File library(){return new File(root(),"LIBRARY");}
    static File photos(){return new File(root(),"PHOTOS");}
    static File builtins(){return new File(root(),"BUILTIN");}
}
