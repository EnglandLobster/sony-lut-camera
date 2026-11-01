package org.hongen.sonylutprobe;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Select an exact platform/pixel map, preferring a reviewed newer firmware map. */
final class CaptureMemoryMap {
    interface Bundled {File extract(String asset)throws IOException;}
    static final class Entry {
        final String memory,filename,asset,sha256;final int pixels,revision,bytes;
        Entry(String[] p)throws IOException{
            if(p.length!=7)throw new IOException("invalid memory map catalog row");
            memory=p[0];filename=p[3];asset=p[4];sha256=p[6];
            try{pixels=Integer.parseInt(p[1]);revision=Integer.parseInt(p[2]);bytes=Integer.parseInt(p[5]);}
            catch(NumberFormatException e){throw new IOException("invalid memory map catalog number",e);}
            if(!memory.matches("[A-Za-z0-9_]+")||pixels<1||pixels>60||revision<0||revision>99||bytes<20||bytes>65536
                    ||!filename.equals("lib"+memory+pixels+"m_PictureEffectPlus_00_"+(revision<10?"0":"")+revision+".so")
                    ||!(asset.equals("-")||asset.matches("[A-Z0-9]{1,8}\\.BIN"))||!sha256.matches("[a-f0-9]{64}"))
                throw new IOException("invalid memory map catalog identity");
        }
    }
    static final class Selection {
        final Entry entry;final File file;final boolean firmware;
        Selection(Entry entry,File file,boolean firmware){this.entry=entry;this.file=file;this.firmware=firmware;}
        String avPath(){return "/android"+file.getAbsolutePath();}
        int cacheMiB(){return entry.memory.contains("4Gb")||entry.memory.equals("unknown")?8:entry.pixels>=36?16:32;}
        int workers(){return entry.memory.equals("unknown")?1:entry.memory.contains("4Gb")?2:4;}
    }
    static List<Entry> read(InputStream input)throws IOException{
        BufferedReader in=new BufferedReader(new InputStreamReader(input,"UTF-8"));List<Entry> entries=new ArrayList<Entry>();
        String line;while((line=in.readLine())!=null){
            if(line.length()>1024||entries.size()>64)throw new IOException("memory map catalog exceeds bounds");
            if(line.length()>0&&!line.startsWith("#"))entries.add(new Entry(line.split("\\t",-1)));
        }
        return entries;
    }
    static Selection select(List<Entry> entries,String memory,String pixels,File firmwareDir,Bundled bundled)throws IOException{
        List<Entry> candidates=new ArrayList<Entry>();
        for(Entry e:entries)if(e.memory.equals(memory)&&String.valueOf(e.pixels).equals(pixels)){
            // Old ILC 24/36 and 4Gb revision00 maps lack the revised save layout.
            int minimum=e.memory.equals("unknown")||e.pixels==12||e.pixels==42||e.memory.equals("DSCSC_MUSASHI8Gb")?0:1;
            if(e.revision>=minimum)candidates.add(e);
        }
        Collections.sort(candidates,new Comparator<Entry>(){public int compare(Entry a,Entry b){return b.revision-a.revision;}});
        Selection onDevice=null;Entry own=null;
        for(Entry e:candidates){
            File onCamera=new File(firmwareDir,e.filename);
            if(onDevice==null&&onCamera.isFile()&&matches(onCamera,e))onDevice=new Selection(e,onCamera,true);
            if(own==null&&!e.asset.equals("-"))own=e;
        }
        if(onDevice!=null&&(own==null||onDevice.entry.revision>=own.revision))return onDevice;
        if(own!=null){
            File file=bundled.extract(own.asset);
            if(!matches(file,own))throw new IOException("bundled memory map checksum mismatch: "+own.asset);
            return new Selection(own,file,false);
        }
        throw new IOException("当前内存布局尚未适配："+memory+"/"+pixels+"; matching reviewed memory map unavailable");
    }
    static boolean matches(File file,Entry entry)throws IOException{
        if(file.length()!=entry.bytes)return false;
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");InputStream in=new FileInputStream(file);
            try{byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);}finally{in.close();}
            StringBuilder hex=new StringBuilder();for(byte value:digest.digest())hex.append(String.format(java.util.Locale.US,"%02x",value&255));
            return entry.sha256.equals(hex.toString());
        }catch(java.security.NoSuchAlgorithmException e){throw new IOException("SHA-256 unavailable",e);}
    }
}
