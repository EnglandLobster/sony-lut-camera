package io.github.englandlobster.lutcamera;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The native camera's own settings as a session found them, kept on disk
 * until that session restores them. Pure: no Android types.
 *
 * Exposure and focus settings live in the body, shared with the native
 * camera. A session that ends without a matching restore (process killed,
 * battery pulled, a value the body would not walk back to) leaves the app's
 * values there, and the next session would otherwise record those as the
 * native settings and "restore" them for good. {@link #adopt} recognises such
 * leftovers: a value still equal to what the app left is the app's, so the
 * stored native value is restored instead; a value that changed since was
 * set on the native camera and is kept. */
final class NativeSettings {
    /** Native values and what the app last left in the camera. */
    static final class Pending {
        final Map<String,String> original,left;
        Pending(Map<String,String> original,Map<String,String> left){this.original=original;this.left=left;}
        static Pending decode(String original,String left){
            Map<String,String> o=NativeSettings.decode(original),l=NativeSettings.decode(left);
            return o.isEmpty()||l.isEmpty()?null:new Pending(o,l);
        }
    }

    private NativeSettings(){}

    /** Keys and values are Sony parameter tokens; anything else is dropped. */
    static String encode(Map<String,String> values){
        StringBuilder s=new StringBuilder();
        if(values!=null)for(Map.Entry<String,String> e:values.entrySet())
            if(token(e.getKey())&&token(e.getValue()))s.append(e.getKey()).append('=').append(e.getValue()).append(';');
        return s.toString();
    }
    static Map<String,String> decode(String text){
        Map<String,String> out=new LinkedHashMap<String,String>();if(text==null)return out;
        for(String item:text.split(";")){
            int at=item.indexOf('=');if(at<=0)continue;
            String key=item.substring(0,at),value=item.substring(at+1);
            if(token(key)&&token(value))out.put(key,value);
        }
        return out;
    }
    private static boolean token(String v){return v!=null&&v.matches("[A-Za-z0-9._/-]{1,64}");}

    /** The values of {@code current} for {@code keys}. */
    static Map<String,String> project(Map<String,String> current,Collection<String> keys){
        Map<String,String> out=new LinkedHashMap<String,String>();
        for(String key:keys)if(current.get(key)!=null)out.put(key,current.get(key));
        return out;
    }

    /** Replaces in {@code saved} each value the previous session left behind
     * by the native value it recorded. Only {@code keys} are considered; a
     * key missing from any of the three maps is left alone.
     * @return the adopted keys, for the log */
    static List<String> adopt(Map<String,String> saved,Pending pending,String... keys){
        List<String> adopted=new ArrayList<String>();
        if(pending==null)return adopted;
        // "a+b" compares and adopts keys that only mean something together
        // (shutter numerator and denominator).
        for(String joint:keys){
            String[] parts=joint.split("\\+");
            String now=joined(saved,parts),left=joined(pending.left,parts),native_=joined(pending.original,parts);
            if(now==null||left==null||native_==null)continue;
            if(now.equals(left)&&!now.equals(native_)){
                for(String key:parts)saved.put(key,pending.original.get(key));
                adopted.add(joint+"="+now+"->"+native_);
            }
        }
        return adopted;
    }
    /** True when {@code key} ("a+b" allowed) was left different from native. */
    static boolean leftChanged(Pending pending,String key){
        if(pending==null)return false;
        String[] parts=key.split("\\+");
        String left=joined(pending.left,parts),native_=joined(pending.original,parts);
        return left!=null&&native_!=null&&!left.equals(native_);
    }
    private static String joined(Map<String,String> values,String[] keys){
        StringBuilder s=new StringBuilder();
        for(String key:keys){String v=values.get(key);if(v==null)return null;if(s.length()>0)s.append('/');s.append(v);}
        return s.toString();
    }
}
