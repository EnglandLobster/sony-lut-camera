package org.hongen.sonylutprobe;
import android.graphics.*;
import android.media.ExifInterface;
import java.io.*;
final class ReviewLoader {
    static long capturedAt(File file){
        try{String value=new ExifInterface(file.getPath()).getAttribute(ExifInterface.TAG_DATETIME);
            if(value==null)return 0;
            java.text.SimpleDateFormat parser=new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss",java.util.Locale.US);parser.setLenient(false);return parser.parse(value).getTime();
        }catch(Exception e){return 0;}
    }
    static Bitmap rotate(Bitmap bitmap,int orientation){
        Matrix m=new Matrix();switch(orientation){
            case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;
            case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;
            case 7:m.setRotate(270);m.postScale(-1,1);break;case 8:m.setRotate(270);break;default:return bitmap;
        }
        Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),m,true);if(rotated!=bitmap)bitmap.recycle();return rotated;
    }
}
