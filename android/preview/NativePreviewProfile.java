package org.hongen.sonylutprobe;

import java.io.*;
import java.util.*;

/** A preview approximation, never a replacement for the full production cube. */
final class NativePreviewProfile {
    final String cubeSha256,order;
    final int[] matrix;
    final byte[] gamma;
    int fitVersion;
    double mae,p95,identityMae,fitMs;
    boolean largeError(){return fitVersion>0&&(mae>8||p95>24);}
    private NativePreviewProfile(String digest,String order,int[] matrix,byte[] gamma){
        this.cubeSha256=digest;this.order=order;this.matrix=matrix;this.gamma=gamma;
    }
    static NativePreviewProfile read(InputStream stream,String expectedDigest)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[1024];int n;
        while((n=stream.read(block))!=-1){
            if(bytes.size()+n>16384)throw new IOException("preview profile too large");bytes.write(block,0,n);
        }
        String[] words=new String(bytes.toByteArray(),"US-ASCII").trim().split("\\s+");
        boolean v2=words.length>0&&"SONY_PREVIEW_V2".equals(words[0]);int offset=v2?8:3;
        if(words.length!=offset+9+1024||(!v2&&!"SONY_PREVIEW_V1".equals(words[0])))throw new IOException("invalid preview format");
        if(!words[1].matches("[0-9a-f]{64}")||!words[1].equals(expectedDigest))throw new IOException("preview/cube digest mismatch");
        if(!"matrix_gamma".equals(words[2])&&!"gamma_matrix".equals(words[2]))throw new IOException("unknown model order");
        int[] matrix=new int[9];byte[] gamma=new byte[2048];int last=-1;
        try{
            for(int i=0;i<9;i++){
                matrix[i]=Integer.parseInt(words[offset+i]);
                if(matrix[i]<-2048||matrix[i]>2048)throw new IOException("preview coefficient exceeds fitted range");
            }
            for(int i=0;i<1024;i++){
                int v=Integer.parseInt(words[offset+9+i]);
                if(v<0||v>1023||v<last)throw new IOException("preview curve must be bounded and monotone");
                gamma[i*2]=(byte)v;gamma[i*2+1]=(byte)(v>>>8);last=v;
            }
        }catch(NumberFormatException e){throw new IOException("invalid preview number",e);}
        NativePreviewProfile p=new NativePreviewProfile(words[1],words[2],matrix,gamma);
        if(v2)try{
            p.fitVersion=Integer.parseInt(words[3]);p.mae=Double.parseDouble(words[4]);p.p95=Double.parseDouble(words[5]);
            p.identityMae=Double.parseDouble(words[6]);p.fitMs=Double.parseDouble(words[7]);
            if(p.fitVersion!=1||!"matrix_gamma".equals(p.order))throw new IOException("unsupported preview fitter");
            for(double v:new double[]{p.mae,p.p95,p.identityMae,p.fitMs})if(Double.isNaN(v)||Double.isInfinite(v)||v<0)throw new IOException("invalid preview metrics");
            if(p.mae>255||p.p95>255||p.identityMae>255)throw new IOException("invalid preview error range");
        }catch(NumberFormatException e){throw new IOException("invalid preview metrics",e);}
        return p;
    }
}
