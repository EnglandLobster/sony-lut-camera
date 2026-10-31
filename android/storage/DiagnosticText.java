package org.hongen.sonylutprobe;

/** Keep event size bounded and avoid exporting absolute device paths. */
final class DiagnosticText {
    static String clean(String text){
        if(text==null)return "";if(text.length()>6000)text=text.substring(0,6000)+" [truncated]";
        return text.replaceAll("(?:/android/data/|/data/|/storage/|/mnt/|/sdcard/)[^\\s\"<>]*","<path>");
    }
    static String quote(String text){
        StringBuilder out=new StringBuilder("\"");
        for(int i=0;i<text.length();i++){char c=text.charAt(i);switch(c){
            case '\\':out.append("\\\\");break;case '"':out.append("\\\"");break;
            case '\n':out.append("\\n");break;case '\r':out.append("\\r");break;case '\t':out.append("\\t");break;
            default:if(c<32)out.append(' ');else out.append(c);
        }}return out.append('"').toString();
    }
}
