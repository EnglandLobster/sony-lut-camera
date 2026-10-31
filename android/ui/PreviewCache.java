package org.hongen.sonylutprobe;
import java.util.*;

/** UI-thread-owned byte-bounded LRU. Never recycles the image still on screen. */
final class PreviewCache<T> {
    interface Disposal<T>{void release(T value);}
    private static final class Item<T>{final T value;final long bytes;Item(T v,long b){value=v;bytes=b;}}
    private final LinkedHashMap<String,Item<T>> items=new LinkedHashMap<String,Item<T>>(8,.75f,true);
    private final long budget;private final Disposal<T> disposal;private long used;private String pinned;
    PreviewCache(long budget,Disposal<T> disposal){if(budget<=0)throw new IllegalArgumentException("cache budget");this.budget=budget;this.disposal=disposal;}
    T get(String key){Item<T> item=items.get(key);return item==null?null:item.value;}
    boolean contains(String key){return items.containsKey(key);}
    void pin(String key){if(key!=null&&!items.containsKey(key))throw new IllegalArgumentException("pin missing preview");pinned=key;}
    boolean put(String key,T value,long bytes){
        if(bytes<=0||bytes>budget||items.containsKey(key)){disposal.release(value);return false;}
        // Make space before insertion; the visible bitmap remains protected.
        Iterator<Map.Entry<String,Item<T>>> i=items.entrySet().iterator();
        while(used+bytes>budget&&i.hasNext()){
            Map.Entry<String,Item<T>> entry=i.next();if(entry.getKey().equals(pinned))continue;
            used-=entry.getValue().bytes;disposal.release(entry.getValue().value);i.remove();
        }
        if(used+bytes>budget){disposal.release(value);return false;}
        items.put(key,new Item<T>(value,bytes));used+=bytes;return true;
    }
    long bytes(){return used;}
    void clear(){for(Item<T> item:items.values())disposal.release(item.value);items.clear();used=0;pinned=null;}
}
