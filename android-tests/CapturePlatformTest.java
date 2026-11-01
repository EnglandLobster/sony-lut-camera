package org.hongen.sonylutprobe;
public final class CapturePlatformTest {
    public static void main(String[] args){
        if(!CapturePlatform.saveOverlapVerified("ILCE-6300","2.01"))throw new AssertionError();
        for(String model:new String[]{null,"ILCE-6000","ILCE-7","ILCE-6500","DSC-RX100M5","NEX-6"})
            if(CapturePlatform.saveOverlapVerified(model,"2.01"))throw new AssertionError(model);
        for(String version:new String[]{null,"","2.00","2.02"})
            if(CapturePlatform.saveOverlapVerified("ILCE-6300",version))throw new AssertionError(version);
        System.out.println("PASS save overlap restricted to device-tested body and firmware");
    }
}
