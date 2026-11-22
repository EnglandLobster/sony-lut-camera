package io.github.englandlobster.lutcamera;

import java.io.*;
import java.util.Arrays;

/** Synthetic JPEG containers only: EXIF orientation and the MPF large
 * thumbnail are found in both byte orders; a file without MPF still reports
 * its orientation; a preview pointing past the file is refused. */
public final class JpegPreviewTest {
    public static void main(String[] args)throws Exception{
        File dir=new File("out/product-ui-tests/jpeg-preview");dir.mkdirs();
        byte[] preview={(byte)0xff,(byte)0xd8,1,2,3,4,5,(byte)0xff,(byte)0xd9};
        for(boolean little:new boolean[]{true,false}){
            File f=new File(dir,little?"II.JPG":"MM.JPG");write(f,photo(little,6,preview,0x010002,true));
            JpegPreview.Found found=JpegPreview.read(f);
            check(found.jpeg!=null&&Arrays.equals(found.jpeg,preview),"preview bytes "+(little?"II":"MM"));
            check(found.orientation==6,"orientation "+found.orientation);
            check(JpegPreview.orientation(f)==6,"orientation only");
        }
        File plain=new File(dir,"PLAIN.JPG");write(plain,photo(true,8,preview,0x010002,false));
        JpegPreview.Found none=JpegPreview.read(plain);
        check(none.jpeg==null&&none.orientation==8,"no MPF keeps orientation");
        File other=new File(dir,"OTHER.JPG");write(other,photo(true,1,preview,0x020002,true));
        check(JpegPreview.read(other).jpeg==null,"non-thumbnail MP entries ignored");
        File cut=new File(dir,"CUT.JPG");byte[] whole=photo(true,1,preview,0x010002,true);
        write(cut,Arrays.copyOf(whole,whole.length-4));
        try{JpegPreview.read(cut);throw new AssertionError("truncated preview accepted");}catch(IOException expected){}
        System.out.println("PASS jpeg preview: MPF large thumbnail and EXIF orientation in II/MM, absent MPF, foreign entries, truncated file");
    }
    private static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
    private static void write(File f,byte[] data)throws IOException{FileOutputStream out=new FileOutputStream(f);try{out.write(data);}finally{out.close();}}
    /** SOI, APP1 EXIF (IFD0 orientation), optional APP2 MPF, a stand-in main
     * image ending in EOI, then the preview at the MP entry's offset. */
    private static byte[] photo(boolean little,int orientation,byte[] preview,int type,boolean mpf)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(0xff);out.write(0xd8);
        ByteArrayOutputStream exif=new ByteArrayOutputStream();exif.write("Exif\0\0".getBytes("ISO-8859-1"));
        exif.write(tiffHeader(little));exif.write(u16(1,little));
        exif.write(u16(0x0112,little));exif.write(u16(3,little));exif.write(u32(1,little));exif.write(u16(orientation,little));exif.write(u16(0,little));
        exif.write(u32(0,little));
        segment(out,0xe1,exif.toByteArray());
        int mpHeaderAt=-1;ByteArrayOutputStream mp=null;
        if(mpf){
            mp=new ByteArrayOutputStream();mp.write("MPF\0".getBytes("ISO-8859-1"));
            mp.write(tiffHeader(little));mp.write(u16(1,little));
            int valuesAt=8+2+12+4;
            mp.write(u16(0xb002,little));mp.write(u16(7,little));mp.write(u32(32,little));mp.write(u32(valuesAt,little));mp.write(u32(0,little));
            mp.write(new byte[32]); // entries filled once the preview offset is known
            mpHeaderAt=out.size()+4+4; // marker+length, then "MPF\0"
        }
        byte[] mpBytes=mp==null?null:mp.toByteArray();
        int mainAt=out.size()+(mpBytes==null?0:4+mpBytes.length);
        byte[] main={(byte)0xff,(byte)0xda,0,2,9,9,9,(byte)0xff,(byte)0xd9};
        int previewAt=mainAt+main.length;
        if(mpBytes!=null){
            int entries=4+8+2+12+4;
            System.arraycopy(entry(0x030000|0xa0000000,previewAt,0,little),0,mpBytes,entries,16);
            System.arraycopy(entry(type,preview.length,previewAt-mpHeaderAt,little),0,mpBytes,entries+16,16);
            segment(out,0xe2,mpBytes);
        }
        out.write(main);out.write(preview);
        return out.toByteArray();
    }
    private static byte[] entry(int attr,int size,int offset,boolean little)throws IOException{
        ByteArrayOutputStream e=new ByteArrayOutputStream();e.write(u32(attr,little));e.write(u32(size,little));e.write(u32(offset,little));e.write(new byte[4]);return e.toByteArray();
    }
    private static byte[] tiffHeader(boolean little)throws IOException{
        ByteArrayOutputStream t=new ByteArrayOutputStream();t.write(little?'I':'M');t.write(little?'I':'M');t.write(u16(42,little));t.write(u32(8,little));return t.toByteArray();
    }
    private static void segment(ByteArrayOutputStream out,int marker,byte[] data)throws IOException{
        out.write(0xff);out.write(marker);out.write((data.length+2)>>8);out.write((data.length+2)&0xff);out.write(data);
    }
    private static byte[] u16(int v,boolean little){return little?new byte[]{(byte)v,(byte)(v>>8)}:new byte[]{(byte)(v>>8),(byte)v};}
    private static byte[] u32(long v,boolean little){
        byte[] b=new byte[4];for(int i=0;i<4;i++)b[little?i:3-i]=(byte)(v>>(8*i));return b;
    }
}
