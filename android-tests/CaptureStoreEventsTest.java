package org.hongen.sonylutprobe;

public final class CaptureStoreEventsTest {
    public static class LegacyCamera {
        public interface JpegListener {void onPictureTaken(byte[] jpeg,LegacyCamera owner);}
        JpegListener listener;int registrations;
        public void setJpegListener(JpegListener value){listener=value;registrations++;}
    }
    public static class ModernCamera {
        public interface StoreImageCompleteListener {void onStore(int status,Info info,ModernCamera owner);}
        public interface JpegListener {void onPictureTaken(byte[] jpeg,ModernCamera owner);}
        public static class Info {public String DirectoryName="DCIM/100MSDCF/",FileName="DSC00001";}
        StoreImageCompleteListener listener;boolean fail;int legacyCalls;
        public void setStoreImageCompleteListener(StoreImageCompleteListener value){if(fail)throw new IllegalStateException("vendor rejected");listener=value;}
        public void setJpegListener(JpegListener value){legacyCalls++;}
    }
    public static class MissingSetterCamera {
        public interface StoreImageCompleteListener {void onStore(int status,Object info);}
    }
    static class Sink implements CaptureStoreEvents.Sink {
        int named,errors;String path;
        public void named(Object status,String directory,String name){named++;path=directory+name;}
        public void failed(Throwable error){errors++;}
    }
    static void check(boolean value,String detail){if(!value)throw new AssertionError(detail);}
    public static void main(String[] args)throws Exception {
        // The beta.5 lookup fails on this firmware-derived legacy API shape.
        boolean missing=false;
        try{Class.forName(LegacyCamera.class.getName()+"$StoreImageCompleteListener");}catch(ClassNotFoundException expected){missing=true;}
        check(missing,"old mandatory startup lookup reproduced");
        LegacyCamera old=new LegacyCamera();Sink sink=new Sink();CaptureStoreEvents events=new CaptureStoreEvents(old,sink);
        check(!events.hasNamedReceipt()&&old.listener==null&&old.registrations==0,"legacy initialization selects index without full JPEG callback");
        events.close();check(old.registrations==0,"legacy close does not enable data callback");
        events=new CaptureStoreEvents(new MissingSetterCamera(),sink);
        check(!events.hasNamedReceipt(),"missing setter selects index protocol");events.close();
        ModernCamera modern=new ModernCamera();sink=new Sink();events=new CaptureStoreEvents(modern,sink);
        check(events.hasNamedReceipt()&&modern.legacyCalls==0,"modern protocol preserved");
        modern.listener.onStore(0,new ModernCamera.Info(),modern);
        check(sink.named==1&&"DCIM/100MSDCF/DSC00001".equals(sink.path),"exact store identity retained");
        modern.listener.onStore(0,null,modern);check(sink.errors==1,"malformed callback surfaced");
        ModernCamera.StoreImageCompleteListener stale=modern.listener;events.close();stale.onStore(0,new ModernCamera.Info(),modern);
        check(modern.listener==null&&sink.named==1,"detached late callback ignored");
        modern.fail=true;boolean failed=false;try{new CaptureStoreEvents(modern,sink);}catch(Exception expected){failed=true;}
        check(failed&&modern.legacyCalls==0,"runtime error not hidden by fallback");
        System.out.println("PASS store API: old startup failure reproduced, legacy index/modern callbacks, detach, malformed data and setter failure");
    }
}
