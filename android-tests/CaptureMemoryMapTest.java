package org.hongen.sonylutprobe;
import java.io.*;
import java.util.*;

public final class CaptureMemoryMapTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        final File assets=new File(args[0]);InputStream in=new FileInputStream(new File(assets,"CAPMAP.TXT"));
        List<CaptureMemoryMap.Entry> catalog;try{catalog=CaptureMemoryMap.read(in);}finally{in.close();}
        CaptureMemoryMap.Bundled bundled=new CaptureMemoryMap.Bundled(){public File extract(String name){return new File(assets,name);}};
        File empty=new File(assets,"missing-firmware");int bundledCount=0;
        for(CaptureMemoryMap.Entry entry:catalog)if(!entry.asset.equals("-")){
            CaptureMemoryMap.Selection selected=CaptureMemoryMap.select(catalog,entry.memory,String.valueOf(entry.pixels),empty,bundled);
            check(!selected.firmware&&selected.entry.sha256.equals(entry.sha256),"bundled profile identity");bundledCount++;
        }
        check(bundledCount==6,"six independent bundled profiles");
        BufferedReader cases=args.length>1?new BufferedReader(new FileReader(args[1])):null;String line;int count=0;
        try{while(cases!=null&&(line=cases.readLine())!=null){
            String[] c=line.split("\t");
            CaptureMemoryMap.Selection selected=CaptureMemoryMap.select(catalog,c[1],c[2],new File(c[3]),bundled);
            check(selected.entry.filename.equals(c[4]),"wrong revision for "+c[0]+": "+selected.entry.filename);
            check(selected.firmware==Boolean.parseBoolean(c[5]),"wrong map source for "+c[0]);
            check(selected.cacheMiB()<=32&&selected.workers()>=1,"bounded budget");
            if(c[0].equals("ILCE-6300"))check(selected.entry.sha256.equals("843b70399744074e9544d69982d8b37964747f9c93f907389c3c7b9efb759ee0")&&selected.cacheMiB()==32&&selected.workers()==4,"A6300 baseline unchanged");
            System.out.println("PASS map "+c[0]+" "+selected.entry.filename+" "+(selected.firmware?"firmware":"bundled"));count++;
        }}finally{if(cases!=null)cases.close();}
        check(count==(args.length>1?20:0),"all firmware-backed model fixtures");
        for(String[] bad:new String[][]{{"ILDCSC_MUSASHI8Gb","42"},{"DSCSC_MUSASHI8Gb","20"},{"ILDCSC_MUSASHI8Gb","12"},{"unknown","18"},{"OTHER","24"},{"../ILDCSC_MUSASHI8Gb","24"}}){
            boolean failed=false;try{CaptureMemoryMap.select(catalog,bad[0],bad[1],empty,bundled);}catch(IOException e){failed=true;}
            check(failed,"missing exact-platform map must not use 24MP: "+Arrays.toString(bad));
        }
        CaptureMemoryMap.Bundled corrupt=new CaptureMemoryMap.Bundled(){public File extract(String name){return new File(assets,"CAPMAP.TXT");}};
        boolean failed=false;try{CaptureMemoryMap.select(catalog,"ILDCSC_MUSASHI8Gb","24",empty,corrupt);}catch(IOException e){failed=true;}
        check(failed,"corrupt map refused");
        for(String bad:new String[]{"invalid", "unknown\t16\t0\t../../x\tX.BIN\t20\t"+new String(new char[64]).replace('\0','a')}){
            failed=false;try{CaptureMemoryMap.read(new ByteArrayInputStream(bad.getBytes("UTF-8")));}catch(IOException e){failed=true;}
            check(failed,"malformed catalog refused");
        }
        System.out.println("PASS missing/corrupt/wrong-platform maps and malformed catalog rejection");
    }
}
