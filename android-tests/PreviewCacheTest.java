package org.hongen.sonylutprobe;
import java.util.*;
public final class PreviewCacheTest {
    static final class Image{final int id;boolean recycled;Image(int id){this.id=id;}}
    static void check(boolean yes,String detail){if(!yes)throw new AssertionError(detail);}
    public static void main(String[] args){
        PreviewCache<Image> cache=new PreviewCache<Image>(8,new PreviewCache.Disposal<Image>(){public void release(Image i){check(!i.recycled,"double recycle");i.recycled=true;}});
        Image a=new Image(1),b=new Image(2),c=new Image(3);
        check(cache.put("a",a,3),"a accepted");cache.pin("a");cache.put("b",b,3);cache.put("c",c,3);
        check(!a.recycled&&b.recycled&&!c.recycled&&cache.bytes()==6,"visible image protected during eviction");
        Image huge=new Image(4);check(!cache.put("huge",huge,9)&&huge.recycled,"oversize rejected and released");
        Image duplicate=new Image(5);check(!cache.put("a",duplicate,3)&&duplicate.recycled&&!a.recycled,"duplicate doesn't recycle visible image");
        // Move display to another image before evicting the previously shown one.
        cache.pin("c");Image d=new Image(6);cache.put("d",d,3);check(a.recycled&&!c.recycled,"old frame released after unpin");
        for(int i=7;i<100;i++){
            Image image=new Image(i);check(cache.put("image"+i,image,3),"navigation inserts");cache.pin("image"+i);
            check(cache.get("image"+i)==image&&!image.recycled&&cache.bytes()<=8,"bounded navigation retains frame");
        }
        cache.clear();check(cache.bytes()==0&&cache.get("c")==null,"teardown clears ownership");cache.clear();
        PreviewCache<Image> window=new PreviewCache<Image>(12,new PreviewCache.Disposal<Image>(){public void release(Image i){check(!i.recycled,"double recycle");i.recycled=true;}});
        // Graded page plus neighbours -> original page plus its neighbours.
        for(String k:new String[]{"graded","next","original","next2"})window.put(k,new Image(1),3);
        window.pin("original");String[] wanted={"original","next-original","graded","next2-original"};
        for(String candidate:wanted){
            for(int i=wanted.length-1;i>=0;i--)window.get(wanted[i]);
            if(!window.contains(candidate))window.put(candidate,new Image(2),3);
        }
        check(window.contains("graded")&&window.contains("original"),"prefetch preserves immediate comparison pair");
        window.clear();
        System.out.println("PreviewCacheTest passed: budget, pin, eviction, duplicate, oversize, repeated navigation, teardown");
    }
}
