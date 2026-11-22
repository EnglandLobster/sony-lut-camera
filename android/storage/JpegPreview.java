package io.github.englandlobster.lutcamera;

import java.io.*;

/** The large preview a Sony JPEG carries (CIPA MPF "large thumbnail", 1616x1080
 * after the main image on the A6300) and the EXIF orientation, read from the
 * file itself. Works while the camera is open; Sony's screen-nail decoder does
 * not (StillPlayHAL decodeImage errCd 0x1004, A6300 2.01, 2026-11-22). */
final class JpegPreview {
    static final class Found {
        /** Null when the file carries no MPF preview. */
        final byte[] jpeg;final int orientation;final long offset;
        Found(byte[] jpeg,int orientation,long offset){this.jpeg=jpeg;this.orientation=orientation;this.offset=offset;}
    }
    /** APP1 (EXIF, up to 64 KB) and APP2 (MPF) both sit before the image data. */
    static final int HEADER_BYTES=128*1024;
    static final int MAX_PREVIEW_BYTES=4*1024*1024;
    private static final int LARGE_THUMBNAIL_VGA=0x010001,LARGE_THUMBNAIL_HD=0x010002,LARGE_THUMBNAIL_4K=0x010003;

    static Found read(File file)throws IOException{return read(file,true);}
    /** EXIF orientation only (1 when absent); reads the header, not the preview. */
    static int orientation(File file)throws IOException{return read(file,false).orientation;}
    private static Found read(File file,boolean preview)throws IOException{
        RandomAccessFile in=new RandomAccessFile(file,"r");
        try{
            long length=in.length();
            byte[] head=new byte[(int)Math.min(HEADER_BYTES,length)];in.readFully(head);
            if(head.length<4||(head[0]&0xff)!=0xff||(head[1]&0xff)!=0xd8)throw new IOException("不是 JPEG 照片");
            int orientation=1;long previewAt=-1;int previewSize=0;
            int at=2;
            while(at+4<=head.length&&(head[at]&0xff)==0xff){
                int marker=head[at+1]&0xff;
                if(marker==0xd8||(marker>=0xd0&&marker<=0xd7)||marker==0x01){at+=2;continue;}
                if(marker==0xda||marker==0xd9)break;
                int size=u16(head,at+2,false);if(size<2)break;
                int data=at+4,end=at+2+size;if(end>head.length)break;
                if(marker==0xe1&&starts(head,data,end,"Exif\0\0"))orientation=orientation(head,data+6,end,orientation);
                else if(marker==0xe2&&starts(head,data,end,"MPF\0")){
                    long[] found=largeThumbnail(head,data+4,end);
                    if(found!=null){previewAt=found[0];previewSize=(int)found[1];}
                }
                at=end;
            }
            if(previewAt<0||!preview)return new Found(null,orientation,-1);
            if(previewSize<4||previewSize>MAX_PREVIEW_BYTES||previewAt+previewSize>length)throw new IOException("照片预览位置无效");
            byte[] jpeg=new byte[previewSize];in.seek(previewAt);in.readFully(jpeg);
            if((jpeg[0]&0xff)!=0xff||(jpeg[1]&0xff)!=0xd8)throw new IOException("照片预览数据无效");
            return new Found(jpeg,orientation,previewAt);
        }finally{in.close();}
    }
    /** {file offset, size} of the preferred large thumbnail, or null. MP entry
     * offsets count from the MP header (the TIFF header after "MPF\0"). */
    private static long[] largeThumbnail(byte[] b,int tiff,int end){
        if(tiff+8>end)return null;
        boolean little;
        if(b[tiff]=='I'&&b[tiff+1]=='I')little=true;else if(b[tiff]=='M'&&b[tiff+1]=='M')little=false;else return null;
        long ifd=u32(b,tiff+4,little);if(ifd<8||tiff+ifd+2>end)return null;
        int count=u16(b,tiff+(int)ifd,little);
        long best=-1,bestSize=0;int bestRank=0;
        for(int i=0;i<count;i++){
            int entry=tiff+(int)ifd+2+12*i;if(entry+12>end)return null;
            if(u16(b,entry,little)!=0xb002)continue;
            long bytes=u32(b,entry+4,little),values=u32(b,entry+8,little);
            if(bytes%16!=0||tiff+values+bytes>end)return null;
            for(int j=0;j<bytes/16;j++){
                int e=tiff+(int)values+16*j;
                int type=(int)(u32(b,e,little)&0xffffff);long size=u32(b,e+4,little),offset=u32(b,e+8,little);
                int rank=type==LARGE_THUMBNAIL_HD?3:type==LARGE_THUMBNAIL_4K?2:type==LARGE_THUMBNAIL_VGA?1:0;
                if(j==0||offset==0||rank==0||rank<=bestRank)continue;
                // The MP header sits at this absolute offset in the file.
                best=tiff+offset;bestSize=size;bestRank=rank;
            }
        }
        return best<0?null:new long[]{best,bestSize};
    }
    private static int orientation(byte[] b,int tiff,int end,int fallback){
        if(tiff+8>end)return fallback;
        boolean little;
        if(b[tiff]=='I'&&b[tiff+1]=='I')little=true;else if(b[tiff]=='M'&&b[tiff+1]=='M')little=false;else return fallback;
        long ifd=u32(b,tiff+4,little);if(ifd<8||tiff+ifd+2>end)return fallback;
        int count=u16(b,tiff+(int)ifd,little);
        for(int i=0;i<count;i++){
            int entry=tiff+(int)ifd+2+12*i;if(entry+12>end)return fallback;
            if(u16(b,entry,little)==0x0112&&u16(b,entry+2,little)==3){
                int value=u16(b,entry+8,little);return value>=1&&value<=8?value:fallback;
            }
        }
        return fallback;
    }
    private static boolean starts(byte[] b,int at,int end,String text){
        if(at+text.length()>end)return false;
        for(int i=0;i<text.length();i++)if(b[at+i]!=(byte)text.charAt(i))return false;
        return true;
    }
    private static int u16(byte[] b,int at,boolean little){
        int x=b[at]&0xff,y=b[at+1]&0xff;return little?(y<<8)|x:(x<<8)|y;
    }
    private static long u32(byte[] b,int at,boolean little){
        long v=0;for(int i=0;i<4;i++)v|=(long)(b[at+(little?i:3-i)]&0xff)<<(8*i);return v;
    }
}
