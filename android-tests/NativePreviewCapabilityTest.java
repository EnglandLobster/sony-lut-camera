package org.hongen.sonylutprobe;
public final class NativePreviewCapabilityTest {
    public static void main(String[] args){
        boolean expected=Boolean.parseBoolean(args[0]);
        if(NativePreviewCapability.available()!=expected)throw new AssertionError("wrong preview capability");
        System.out.println("PASS preview declarations available="+expected+" without initializing Sony CameraEx");
    }
}
