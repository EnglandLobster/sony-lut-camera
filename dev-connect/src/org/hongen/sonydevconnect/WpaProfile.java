package org.hongen.sonydevconnect;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** Select only the authorized test SSID. Never includes credentials in errors. */
final class WpaProfile {
    final String ssid, psk;
    WpaProfile(String ssid, String psk) throws IOException {
        String name = unquote(ssid);
        if (name.getBytes("UTF-8").length < 1 || name.getBytes("UTF-8").length > 32)
            throw new IOException("SSID 格式不正确");
        if (psk == null || !(psk.matches("[0-9a-fA-F]{64}") ||
                (psk.startsWith("\"") && unquote(psk).getBytes("UTF-8").length >= 8 &&
                 unquote(psk).getBytes("UTF-8").length <= 63)))
            throw new IOException("测试网络缺少有效的 WPA2 凭据");
        this.ssid = ssid; this.psk = psk;
    }
    static String unquote(String value) throws IOException {
        if (value == null || value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length()-1) != '"')
            throw new IOException("网络字段格式不正确");
        StringBuilder result = new StringBuilder();
        for (int i=1; i<value.length()-1; i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                if (++i >= value.length()-1) throw new IOException("网络字段转义不完整");
                c = value.charAt(i);
                if (c != '\\' && c != '"') throw new IOException("不支持的网络字段转义");
            } else if (c == '"' || c < 32) throw new IOException("网络字段含非法字符");
            result.append(c);
        }
        return result.toString();
    }
    static WpaProfile select(String input, String target) throws IOException {
        if (input.length() > 65536) throw new IOException("网络配置过大");
        Map<String,String> block = null;
        WpaProfile selected = null;
        for (String raw : input.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.equals("network={")) {
                if (block != null) throw new IOException("网络配置结构不正确");
                block = new HashMap<String,String>();
            } else if (line.equals("}") && block != null) {
                String ssid = block.get("ssid");
                if (ssid != null && ssid.startsWith("\"") && target.equals(unquote(ssid))) {
                    if (!"WPA-PSK".equals(block.get("key_mgmt"))) throw new IOException("测试网络必须使用 WPA2-PSK");
                    WpaProfile candidate = new WpaProfile(ssid, block.get("psk"));
                    if (selected != null && !selected.psk.equals(candidate.psk)) throw new IOException("测试网络存在冲突配置");
                    selected = candidate;
                }
                block = null;
            } else if (block != null && !line.startsWith("#") && line.indexOf('=') > 0) {
                int at = line.indexOf('=');
                String key = line.substring(0,at).trim();
                if (block.put(key,line.substring(at+1).trim()) != null) throw new IOException("网络配置存在重复字段");
            }
        }
        if (block != null) throw new IOException("网络配置未写完");
        if (selected == null) throw new IOException("当前配置中未找到测试网络");
        return selected;
    }
    Properties properties() {
        Properties p = new Properties(); p.setProperty("ssid",ssid); p.setProperty("psk",psk); return p;
    }
}
