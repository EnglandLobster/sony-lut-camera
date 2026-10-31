package org.hongen.sonylutprobe;

import java.io.*;
import java.util.*;

/** Bounded structural check for the A6300 compressed RAW store contract.
 * Check the CFA SubIFD and its entire strip, never an embedded JPEG thumbnail.
 * Pixel decoding/visual acceptance is a separate host-side validation.
 */
final class ArwFile {
    static boolean completeA6300(File file) throws IOException {
        RandomAccessFile in=new RandomAccessFile(file,"r");
        try {
            long length=in.length();if(length<8||in.read()!=73||in.read()!=73||u16(in)!=42)return false;
            Map<Integer,Long> root=directory(in,u32(in),length);
            Long sub=root.get(330);if(sub==null)return false;
            Map<Integer,Long> raw=directory(in,sub.longValue(),length);
            if(!is(raw,256,6048)||!is(raw,257,4024)||!is(raw,258,12)
                    ||!is(raw,259,32767)||!is(raw,262,32803)||!is(raw,277,1)
                    ||!is(raw,278,4024))return false;
            Long offset=raw.get(273),bytes=raw.get(279);
            // Sony ARW2 compressed payload: one byte per stored sensor sample.
            return offset!=null&&bytes!=null&&bytes.longValue()==6048L*4024
                    &&offset.longValue()>=8&&offset.longValue()<=length-bytes.longValue();
        }catch(EOFException e){return false;}
        finally{in.close();}
    }
    private static boolean is(Map<Integer,Long> tags,int tag,long expected){
        Long actual=tags.get(tag);return actual!=null&&actual.longValue()==expected;
    }
    private static Map<Integer,Long> directory(RandomAccessFile in,long offset,long length)throws IOException{
        Map<Integer,Long> tags=new HashMap<Integer,Long>();
        if(offset<8||offset>length-2)return tags;
        in.seek(offset);int count=u16(in);if(count<1||count>128||offset+2+12L*count+4>length)return tags;
        for(int i=0;i<count;i++){
            int tag=u16(in),type=u16(in);long size=u32(in),value=u32(in);
            if(size==1&&(type==3||type==4)){
                if(tags.containsKey(tag))return new HashMap<Integer,Long>();
                tags.put(tag,type==3?value&65535:value);
            }
        }
        return tags;
    }
    private static int u16(RandomAccessFile in)throws IOException{return in.readUnsignedByte()|(in.readUnsignedByte()<<8);}
    private static long u32(RandomAccessFile in)throws IOException{return (long)u16(in)|((long)u16(in)<<16);}
}
