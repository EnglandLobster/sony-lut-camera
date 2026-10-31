package org.hongen.sonylutprobe;
import java.io.*;
import java.util.*;
import org.json.*;

/** Curated content is data, independent of pages and capture code. */
final class BuiltinLuts {
    static final class Entry {
        final String asset,name,source,sha256;final int grid;final boolean selected;
        Entry(JSONObject object)throws JSONException,IOException{
            asset=object.getString("asset");name=object.getString("name");source=object.getString("source");sha256=object.getString("sha256");grid=object.getInt("grid");selected=object.optBoolean("default",false);
            if(!asset.matches("[A-Z0-9_]{1,8}\\.CUB")||!sha256.matches("[0-9a-f]{64}")||name.length()==0||grid<2||grid>65)throw new IOException("内置 LUT 清单无效");
        }
    }
    static List<Entry> read(InputStream input)throws IOException,JSONException{
        StringBuilder text=new StringBuilder();Reader reader=new InputStreamReader(input,"UTF-8");
        try{char[] buffer=new char[2048];int n;while((n=reader.read(buffer))!=-1){text.append(buffer,0,n);if(text.length()>65536)throw new IOException("内置 LUT 清单过大");}}finally{reader.close();}
        JSONArray array=new JSONArray(text.toString());List<Entry> result=new ArrayList<Entry>();Set<String> names=new HashSet<String>();int defaults=0;
        for(int i=0;i<array.length();i++){Entry entry=new Entry(array.getJSONObject(i));if(!names.add(entry.asset))throw new IOException("内置 LUT 文件名重复");if(entry.selected)defaults++;result.add(entry);}
        if(defaults!=1)throw new IOException("内置 LUT 必须有一个默认项");return result;
    }
}
