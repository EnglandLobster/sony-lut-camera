package org.hongen.sonylutprobe;

/** Optimization evidence is narrower than the supported capture API. */
final class CapturePlatform {
    private CapturePlatform(){}
    static boolean saveOverlapVerified(String model,String firmware){
        // Only this body/firmware has verified that storeImage's return ends
        // the encoder's read of the source buffer. Other bodies wait for receipt.
        return "ILCE-6300".equals(model)&&"2.01".equals(firmware);
    }
}
