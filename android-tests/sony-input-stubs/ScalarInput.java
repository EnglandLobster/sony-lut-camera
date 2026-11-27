package com.sony.scalar.sysutil;

/** The regression: a readable logical mode status without a physical dial. */
public final class ScalarInput {
    public static final class KeyStatus {public int valid=1,status=539;}
    public static KeyStatus getKeyStatus(int code){return new KeyStatus();}
}
