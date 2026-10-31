package org.hongen.sonylutprobe;

public final class PreviewCaptureBarrierTest {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static final class Driver implements PreviewCaptureBarrier.Driver {
        String order="";boolean failRestore,failRestart;int enabled;
        public void restoreAndVerify()throws Exception{order+="restore;";if(failRestore)throw new Exception("baseline mismatch");}
        public void restartPreview()throws Exception{order+="restart;";if(failRestart)throw new Exception("preview failed");}
        public void enableCapture(){order+="enable;";enabled++;}
    }
    public static void main(String[] args)throws Exception{
        Driver d=new Driver();PreviewCaptureBarrier b=new PreviewCaptureBarrier(d);
        check(!b.previewReady()&&!b.captureReady()&&d.enabled==0,"initial preview notification cannot enable shutter");
        b.prepare();b.prepare();check(d.order.equals("restore;restart;")&&!b.captureReady(),"no shutter before native preview ack");
        check(b.previewReady()&&b.captureReady(),"ready after restore and ack");
        check(!b.previewReady()&&d.enabled==1,"duplicate callback cannot restart capture");
        check(d.order.equals("restore;restart;enable;"),"required order");
        for(int failure=0;failure<2;failure++){
            Driver bad=new Driver();bad.failRestore=failure==0;bad.failRestart=failure==1;
            PreviewCaptureBarrier blocked=new PreviewCaptureBarrier(bad);boolean rejected=false;
            try{blocked.prepare();}catch(Exception expected){rejected=true;}
            check(rejected&&!blocked.previewReady()&&!blocked.captureReady()&&bad.enabled==0,"failed transition stays capture-disabled");
        }
        Driver late=new Driver();PreviewCaptureBarrier stopped=new PreviewCaptureBarrier(late);stopped.prepare();stopped.fail();
        check(!stopped.previewReady()&&late.enabled==0,"late notification after timeout/close stays disabled");
        System.out.println("PreviewCaptureBarrierTest PASS: restored baseline and native preview ack required before direct shutter; failures closed");
    }
}
