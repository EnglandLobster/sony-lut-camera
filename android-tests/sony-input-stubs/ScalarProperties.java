package com.sony.scalar.sysutil;

/** Host protocol fixture; not an Android APK source. */
public final class ScalarProperties {
    public static int[] positions;
    public static boolean unavailable;
    public static int[] getIntArray(String name){
        if(!"input.mode.sw.mode.list".equals(name))throw new AssertionError("Unexpected property: "+name);
        if(unavailable)throw new UnsupportedOperationException("property API unavailable");
        return positions;
    }
}
