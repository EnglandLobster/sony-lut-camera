package org.hongen.sonylutprobe;
import java.io.*;
import java.util.Properties;

/** Derived preview cache and two-slot per-LUT user preferences. Never writes CUBE. */
final class PreviewProfileStore {
    interface Fitter {String fit(LutLibrary.Entry entry)throws Exception;}
    static NativePreviewProfile load(LutLibrary.Entry entry)throws IOException{
        File file=new File(entry.file.getParentFile(),"PREVIEW.PRV");if(!file.isFile())return null;
        InputStream in=new FileInputStream(file);NativePreviewProfile p;
        try{p=NativePreviewProfile.read(in,entry.id);}finally{in.close();}
        if(p.fitVersion!=1)throw new IOException("preview cache requires regeneration");return p;
    }
    static NativePreviewProfile ensure(LutLibrary.Entry entry,Fitter fitter)throws Exception{
        try{NativePreviewProfile existing=load(entry);if(existing!=null)return existing;}catch(IOException stale){/* Rebuild derived data only. */}
        String text=fitter.fit(entry);byte[] bytes=text.getBytes("US-ASCII");
        NativePreviewProfile result=NativePreviewProfile.read(new ByteArrayInputStream(bytes),entry.id);
        if(result.fitVersion!=1)throw new IOException("unexpected fitter result");
        File dir=entry.file.getParentFile(),tmp=new File(dir,"PREVIEW.TMP"),dest=new File(dir,"PREVIEW.PRV");
        try{OutputStream out=new FileOutputStream(tmp);try{out.write(bytes);out.flush();}finally{out.close();}
            if(dest.exists()&&!dest.delete())throw new IOException("无法更新预览缓存");
            if(!tmp.renameTo(dest))throw new IOException("无法保存预览缓存");
        }finally{tmp.delete();}
        return result;
    }
    static final class Choice {
        final boolean enabled;final int acknowledged;final long generation;final int slot;
        Choice(boolean e,int a,long g,int s){enabled=e;acknowledged=a;generation=g;slot=s;}
    }
    static Choice choice(LutLibrary.Entry entry)throws IOException{
        Choice best=null;boolean existed=false;
        for(int slot=0;slot<2;slot++){
            File file=new File(entry.file.getParentFile(),slot==0?"VIEWA.CFG":"VIEWB.CFG");if(!file.isFile())continue;existed=true;
            try{Properties p=LutLibrary.readProperties(file);String enabled=p.getProperty("enabled");
                long g=Long.parseLong(p.getProperty("generation","-1"));int ack=Integer.parseInt(p.getProperty("ack","0"));
                if(!entry.id.equals(p.getProperty("sha"))||g<0||ack<0||(!"true".equals(enabled)&&!"false".equals(enabled)))continue;
                if(best==null||g>best.generation)best=new Choice(Boolean.parseBoolean(enabled),ack,g,slot);
            }catch(IOException e){/* Try the other committed slot. */}catch(NumberFormatException e){/* Interrupted slot. */}
        }
        if(best==null&&existed)throw new IOException("预览偏好损坏，请重新选择预览方式");
        return best==null?new Choice(true,0,-1,-1):best;
    }
    static void choose(LutLibrary.Entry entry,boolean enabled,int acknowledged)throws IOException{
        Choice old;try{old=choice(entry);}catch(IOException damaged){old=new Choice(true,0,-1,1);}
        File dir=entry.file.getParentFile(),tmp=new File(dir,"VIEW.TMP"),next=new File(dir,old.slot==0?"VIEWB.CFG":"VIEWA.CFG");
        Properties p=new Properties();p.setProperty("sha",entry.id);p.setProperty("enabled",Boolean.toString(enabled));
        p.setProperty("ack",Integer.toString(acknowledged));p.setProperty("generation",Long.toString(old.generation+1));
        try{LutLibrary.writeProperties(tmp,p);if(next.exists()&&!next.delete())throw new IOException("无法保存预览偏好");
            if(!tmp.renameTo(next))throw new IOException("无法保存预览偏好");}finally{tmp.delete();}
    }
}
