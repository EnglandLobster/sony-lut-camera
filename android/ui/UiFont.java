package org.hongen.sonylutprobe;
import android.content.Context;
import android.graphics.Typeface;
/** Sony's default Japanese font lacks many Simplified Chinese glyphs. */
final class UiFont {
    private static Typeface font;
    static synchronized Typeface get(Context context){
        if(font==null)font=Typeface.createFromAsset(context.getAssets(),"UIFONT.TTF");
        return font;
    }
}
