package org.hongen.sonydevconnect;
import java.io.IOException;
public final class WpaProfileTest {
    private static final String PSK="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static String block(String ssid,String secret){return "network={\nssid=\""+ssid+"\"\npsk="+secret+"\nkey_mgmt=WPA-PSK\n}\n";}
    private static void rejects(String source){try{WpaProfile.select(source,"he_2.4G_test");throw new AssertionError();}catch(IOException expected){if(expected.getMessage().contains(PSK))throw new AssertionError("secret in error");}}
    public static void main(String[] args)throws Exception{
        WpaProfile p=WpaProfile.select(block("unrelated","\"unrelated-secret\"")+block("he_2.4G_test",PSK),"he_2.4G_test");
        if(!p.psk.equals(PSK)||p.properties().size()!=2)throw new AssertionError();
        WpaProfile.select(block("he_2.4G_test","\"test-secret\""),"he_2.4G_test");
        rejects(block("other",PSK));rejects(block("he_2.4G_test","\"short\""));
        rejects(block("he_2.4G_test",PSK)+block("he_2.4G_test","\"different-secret\""));
        rejects(block("he_2.4G_test",PSK).replace("key_mgmt=WPA-PSK","key_mgmt=NONE"));
        rejects(block("he_2.4G_test",PSK).replace("}\n",""));
        System.out.println("PASS select only test network, PSK/passphrase validation, conflicting profiles, security type, truncated input, no credential errors");
    }
}
