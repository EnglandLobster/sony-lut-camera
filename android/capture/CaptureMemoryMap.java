package org.hongen.sonylutprobe;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Follow Sony's platform/pixel/revision selection; pin only APK-supplied maps. */
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
        final Entry entry;final File file;final boolean firmware,reviewed;
        Selection(Entry entry,File file,boolean firmware,boolean reviewed){this.entry=entry;this.file=file;this.firmware=firmware;this.reviewed=reviewed;}
        String avPath(){return "/android"+file.getAbsolutePath();}
        int cacheMiB(){return !entry.memory.contains("8Gb")?8:entry.pixels>=36?16:32;}
        int workers(){return entry.memory.contains("8Gb")?4:entry.memory.contains("4Gb")?2:1;}
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
        if(memory==null||!memory.matches("[A-Za-z0-9_]{1,64}")||pixels==null||!pixels.matches("[1-9][0-9]?")||Integer.parseInt(pixels)>60)
            throw new IOException("invalid camera memory properties: "+memory+"/"+pixels);
        int minimum=minimumRevision(memory,Integer.parseInt(pixels));
        List<Entry> candidates=new ArrayList<Entry>();
        for(Entry e:entries)if(e.memory.equals(memory)&&String.valueOf(e.pixels).equals(pixels)){
            if(e.revision>=minimum)candidates.add(e);
        }
        Collections.sort(candidates,new Comparator<Entry>(){public int compare(Entry a,Entry b){return b.revision-a.revision;}});
        Entry own=null;
        for(Entry e:candidates)if(own==null&&!e.asset.equals("-"))own=e;
        // Firmware is already supplied for this body by Sony. The audit
        // catalog is evidence, not a whitelist of all possible camera ROMs.
        String prefix="lib"+memory+pixels+"m_PictureEffectPlus_00_";
        File latest=null;int revision=-1;File root=firmwareDir.getCanonicalFile();
        String[] names=root.list();
        if(names==null&&root.isDirectory())throw new IOException("cannot read camera memory map directory");
        if(names!=null)for(String name:names){
            if(!name.startsWith(prefix)||!name.substring(prefix.length()).matches("[0-9]{2}\\.so"))continue;
            int value=Integer.parseInt(name.substring(prefix.length(),prefix.length()+2));
            if(value>=minimum&&value>revision){revision=value;latest=new File(root,name);}
        }
        if(latest!=null&&(own==null||revision>=own.revision)){
            File real=latest.getCanonicalFile();
            if(!root.equals(real.getParentFile())||!real.getName().equals(latest.getName()))throw new IOException("camera memory map outside matching firmware path");
            validateFirmware(real);
            Entry observed=new Entry(new String[]{memory,pixels,String.valueOf(revision),latest.getName(),"-",String.valueOf(real.length()),digest(real)});
            boolean reviewed=false;
            for(Entry e:candidates)if(e.filename.equals(observed.filename)&&e.bytes==observed.bytes&&e.sha256.equals(observed.sha256)){reviewed=true;break;}
            return new Selection(observed,real,true,reviewed);
        }
        if(own!=null){
            File file=bundled.extract(own.asset);
            if(!matches(file,own))throw new IOException("bundled memory map checksum mismatch: "+own.asset);
            return new Selection(own,file,false,true);
        }
        throw new IOException("camera memory map missing: "+memory+"/"+pixels+" prefix="+prefix+" minimum_revision="+minimum);
    }
    private static int minimumRevision(String memory,int pixels){
        // Preserve only the existing corrections for known older layouts;
        // do not impose an invented revision floor on 18MP or other bodies.
        return (memory.equals("ILDCSC_MUSASHI8Gb")&&(pixels==24||pixels==36))
            ||(pixels==20&&(memory.equals("ILDCSC_MUSASHI4Gb")||memory.equals("DSCSC_KOJIRO4Gb")||memory.equals("DSCSC_MUSASHI4Gb")))?1:0;
    }
    private static void validateFirmware(File file)throws IOException{
        long length=file.length();
        if(!file.isFile()||length<20||length>65536||length%4!=0)throw new IOException("invalid camera memory map size");
        // Shared header of the pinned app tables and all collected firmware
        // tables. This detects empty/truncated/foreign files, not sensor layout.
        DataInputStream in=new DataInputStream(new FileInputStream(file));
        try{if(in.readInt()!=0||in.readInt()!=0xffff0000||in.readInt()!=-1)throw new IOException("invalid camera memory map header");}
        finally{in.close();}
    }
    static boolean matches(File file,Entry entry)throws IOException{
        if(file.length()!=entry.bytes)return false;
        return entry.sha256.equals(digest(file));
    }
    private static String digest(File file)throws IOException{
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");InputStream in=new FileInputStream(file);
            try{byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);}finally{in.close();}
            StringBuilder hex=new StringBuilder();for(byte value:digest.digest())hex.append(String.format(java.util.Locale.US,"%02x",value&255));
            return hex.toString();
        }catch(java.security.NoSuchAlgorithmException e){throw new IOException("SHA-256 unavailable",e);}
    }
}
