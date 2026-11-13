package io.github.englandlobster.lutcamera;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Focus area, flexible-spot AF point and the camera's own face-priority AF.
 * Pure: no Android types. Point movement is local; the camera is written once
 * on confirm. Face priority only toggles Sony's face-detection-mode; the app
 * never requests the vendor face-frame overlay (on the A7R II that overlay
 * cancelled the direct shutter) and never draws a face box. */
final class FocusControls {
    interface Driver extends ParameterDriver {
        /** Commit area and point; Sony quantizes to its AF grid. */
        void setFocusArea(String area,int x,int y) throws Exception;
        void setFaceDetection(String mode) throws Exception;
    }
    interface Log { void log(String name,String detail); }
    static final String FLEX="flex-spot";
    static final int STEP=100,LIMIT=1000;

    private final Driver driver;
    private final Log log;
    private Map<String,String> original;
    private final List<String> areas=new ArrayList<String>();
    private String area,face;
    private boolean faceAvailable,pointAvailable,editing;
    private int x,y,editX,editY;

    FocusControls(Driver driver,Log log){this.driver=driver;this.log=log;}

    static final class Memory {
        String area,face;int x=Integer.MIN_VALUE,y=Integer.MIN_VALUE;
        String encode(){
            StringBuilder s=new StringBuilder();
            if(area!=null)s.append("area=").append(area).append(';');
            if(face!=null)s.append("face=").append(face).append(';');
            if(x!=Integer.MIN_VALUE&&y!=Integer.MIN_VALUE)s.append("point=").append(x).append(',').append(y).append(';');
            return s.toString();
        }
        static Memory decode(String text){
            Memory m=new Memory();if(text==null)return m;
            for(String item:text.split(";")){
                int at=item.indexOf('=');if(at<=0)continue;
                String key=item.substring(0,at),value=item.substring(at+1);
                try{
                    if(key.equals("area")&&value.matches("[a-z0-9-]{1,40}"))m.area=value;
                    else if(key.equals("face")&&(value.equals("auto")||value.equals("off")))m.face=value;
                    else if(key.equals("point")){
                        String[] xy=value.split(",");
                        if(xy.length==2){m.x=clamp(Integer.parseInt(xy[0]));m.y=clamp(Integer.parseInt(xy[1]));}
                    }
                }catch(RuntimeException ignored){}
            }
            return m;
        }
    }
    Memory memory(){
        Memory m=new Memory();m.area=area;m.face=faceAvailable?face:null;
        if(pointAvailable){m.x=x;m.y=y;}
        return m;
    }

    static int clamp(int v){return Math.max(-LIMIT,Math.min(LIMIT,v));}
    private static int number(String v){try{return Integer.parseInt(v);}catch(RuntimeException e){return 0;}}
    private static boolean userSelectable(String value){
        return value.equals("multi")||value.equals("wide")||value.equals("zone")||value.equals("local")||value.equals("center")
            ||value.equals(FLEX)||value.equals("flex-spot-s")||value.equals("flex-spot-m")||value.equals("flex-spot-l")
            ||value.equals("expand-flex-spot")||value.equals("expanded-flex-spot");
    }
    /** Sony aliases that the native menu shows as one entry; the first is
     * preferred unless the camera is currently on the other one. */
    private static final String[][] ALIASES={{"wide","multi"},{"local","zone"}};
    private static List<String> values(String csv){
        List<String> out=new ArrayList<String>();
        if(csv!=null)for(String v:csv.split(",")){v=v.trim();if(v.length()>0&&!out.contains(v))out.add(v);}
        return out;
    }
    private static List<String> areaValues(String csv,String current){
        List<String> out=new ArrayList<String>();
        for(String v:values(csv))if(userSelectable(v))out.add(v);
        for(String[] alias:ALIASES)
            if(out.contains(alias[0])&&out.contains(alias[1]))
                out.remove(alias[1].equals(current)?alias[0]:alias[1]);
        return out;
    }
    private void log(String name,String detail){if(log!=null)log.log(name,detail);}

    void open(Memory remembered)throws Exception{
        if(original!=null)throw new IllegalStateException("focus session already entered");
        Map<String,String> p=driver.read();
        Map<String,String> saved=new LinkedHashMap<String,String>();
        for(String key:new String[]{"focus-area-type","focus-point-x","focus-point-y","face-detection-mode"})
            if(p.get(key)!=null)saved.put(key,p.get(key));
        original=saved;
        area=p.get("focus-area-type");
        areas.clear();areas.addAll(areaValues(p.get("focus-area-type-values"),area));
        if(area!=null&&!areas.contains(area))areas.add(0,area);
        pointAvailable=areas.contains(FLEX)&&p.get("focus-point-x")!=null&&p.get("focus-point-y")!=null;
        x=clamp(number(p.get("focus-point-x")));y=clamp(number(p.get("focus-point-y")));
        List<String> faces=values(p.get("face-detection-mode-values"));
        face=p.get("face-detection-mode");
        faceAvailable=face!=null&&faces.contains("auto")&&faces.contains("off");
        List<String> skipped=new ArrayList<String>();
        if(remembered.face!=null&&faceAvailable&&!remembered.face.equals(face))
            try{setFace("auto".equals(remembered.face));}catch(Exception e){skipped.add("face");log("focus_restore_skipped","face "+e);}
        if(remembered.area!=null&&areas.contains(remembered.area)&&!remembered.area.equals(area))
            try{
                if(FLEX.equals(remembered.area)&&remembered.x!=Integer.MIN_VALUE){x=remembered.x;y=remembered.y;}
                setArea(remembered.area);
            }catch(Exception e){skipped.add("area");log("focus_restore_skipped","area "+e);}
        else if(FLEX.equals(area)&&pointAvailable&&remembered.x!=Integer.MIN_VALUE&&(remembered.x!=x||remembered.y!=y))
            try{commit(FLEX,remembered.x,remembered.y);}catch(Exception e){skipped.add("point");log("focus_restore_skipped","point "+e);}
        log("focus_open",state()+" areas="+areas+" face_values="+faces+" skipped="+skipped);
    }

    List<String> areas(){return new ArrayList<String>(areas);}
    String area(){return area;}
    boolean faceAvailable(){return faceAvailable;}
    boolean face(){return "auto".equals(face);}
    boolean pointAvailable(){return pointAvailable;}
    boolean editing(){return editing;}
    int x(){return editing?editX:x;}
    int y(){return editing?editY:y;}
    boolean flexible(){return FLEX.equals(area);}

    /** A camera that refuses an area for this session loses it from the
     * list, so the row does not stop on it again. */
    void setArea(String value)throws Exception{
        if(!areas.contains(value))throw new IOException("unsupported focus area "+value);
        try{commit(value,x,y);}
        catch(Exception e){
            // Only an area the camera never switched to leaves the list; a
            // flexible spot whose point lagged is still a usable area.
            Map<String,String> now=null;try{now=driver.read();}catch(Exception ignored){}
            String actual=now==null?null:now.get("focus-area-type");
            // Keep the camera's real point, not a remembered one that was
            // never applied.
            if(now!=null&&pointAvailable){x=clamp(number(now.get("focus-point-x")));y=clamp(number(now.get("focus-point-y")));}
            if(value.equals(actual)){
                area=actual;
                log("focus_area_point_unapplied",value+" "+root(e));
                throw new IOException("对焦区域已切换，对焦点未更新");
            }
            if(!value.equals(area))areas.remove(value);
            log("focus_area_rejected",value+" "+root(e)+" areas="+areas);
            throw new IOException("相机不接受对焦区域「"+areaLabel(value)+"」，已从列表移除");
        }
    }
    private static Throwable root(Throwable e){while(e.getCause()!=null)e=e.getCause();return e;}
    void setFace(boolean on)throws Exception{
        if(!faceAvailable)throw new IOException("face detection unavailable");
        String value=on?"auto":"off";
        driver.setFaceDetection(value);
        String actual=driver.read().get("face-detection-mode");
        if(!value.equals(actual))throw new IOException("face detection readback "+actual+" expected "+value);
        face=value;
    }
    private void commit(String value,int px,int py)throws Exception{
        Map<String,String> before=driver.read();
        driver.setFocusArea(value,px,py);
        Map<String,String> p=driver.read();
        String problem=verify(value,px,py,before,p);
        if(problem!=null)throw new IOException(problem);
        if(FLEX.equals(value)){x=clamp(number(p.get("focus-point-x")));y=clamp(number(p.get("focus-point-y")));}
        area=value;
    }
    /** Null when the camera kept the area and, for a flexible spot, the
     * point. Sony rounds to its AF grid and stops at its own frame edge
     * (which may lie inside -1000..1000), so an axis counts as applied when
     * it was not asked to change, lands near the request, or moved toward
     * it. A point that stayed where it was on every requested axis was not
     * written. */
    static String verify(String area,int px,int py,Map<String,String> before,Map<String,String> after){
        String actual=after.get("focus-area-type");
        if(!area.equals(actual))return "focus area readback "+actual+" expected "+area;
        if(!FLEX.equals(area))return null;
        int bx=number(before.get("focus-point-x")),by=number(before.get("focus-point-y"));
        int ax=number(after.get("focus-point-x")),ay=number(after.get("focus-point-y"));
        boolean askX=px!=bx,askY=py!=by;
        if(!askX&&!askY)return null;
        boolean okX=!askX||applied(px,bx,ax),okY=!askY||applied(py,by,ay);
        boolean movedAny=(askX&&ax!=bx)||(askY&&ay!=by);
        // One axis pinned at Sony's edge while the other moved is still a
        // committed point.
        if(movedAny&&(okX||okY))return null;
        return "AF point readback "+ax+","+ay+" requested "+px+","+py+" before "+bx+","+by;
    }
    private static boolean applied(int requested,int before,int actual){
        if(Math.abs(actual-requested)<=STEP)return true;
        return requested>before?actual>before:actual<before;
    }

    // ---- point editing ------------------------------------------------------------
    void beginPoint(){if(!pointAvailable)return;editing=true;editX=x;editY=y;}
    /** Local only; false at the frame edge. */
    boolean movePoint(int dx,int dy){
        if(!editing)return false;
        int nx=clamp(editX+dx*STEP),ny=clamp(editY+dy*STEP);
        if(nx==editX&&ny==editY)return false;
        editX=nx;editY=ny;return true;
    }
    void centerPoint(){if(editing){editX=0;editY=0;}}
    /** On failure editing stays open at the requested point so the user
     * can adjust and confirm again; the detail goes to the log only. */
    void confirmPoint()throws Exception{
        if(!editing)return;
        int rx=editX,ry=editY;
        try{commit(FLEX,rx,ry);}
        catch(Exception e){
            log("af_point_failed","requested="+rx+","+ry+" "+root(e));
            throw new IOException("对焦点未保存，请移动后再按中键");
        }
        editing=false;
        log("af_point","requested="+rx+","+ry+" x="+x+" y="+y+" area="+area);
    }
    void cancelPoint(){editing=false;}

    static String areaLabel(String value){
        if(value==null)return "--";
        if(value.equals("multi")||value.equals("wide"))return "广域";
        if(value.equals("zone")||value.equals("local"))return "区";
        if(value.equals("center"))return "中间";
        if(value.equals(FLEX))return "自由点";
        if(value.equals("flex-spot-s"))return "自由点 S";
        if(value.equals("flex-spot-m"))return "自由点 M";
        if(value.equals("flex-spot-l"))return "自由点 L";
        if(value.equals("expand-flex-spot")||value.equals("expanded-flex-spot"))return "扩展自由点";
        return value;
    }
    String state(){return "area="+area+" x="+x+" y="+y+" face="+face+" point="+pointAvailable+" face_available="+faceAvailable;}

    void close()throws Exception{
        editing=false;if(original==null)return;
        Map<String,String> saved=original;original=null;
        Map<String,String> now=driver.read();
        StringBuilder differs=new StringBuilder();
        String savedFace=saved.get("face-detection-mode");
        if(savedFace!=null&&!savedFace.equals(now.get("face-detection-mode"))){
            driver.setFaceDetection(savedFace);
            if(!savedFace.equals(driver.read().get("face-detection-mode")))differs.append("face-detection-mode ");
        }
        String savedArea=saved.get("focus-area-type");
        boolean pointChanged=saved.get("focus-point-x")!=null&&(!saved.get("focus-point-x").equals(now.get("focus-point-x"))
            ||!saved.get("focus-point-y").equals(now.get("focus-point-y")));
        if(savedArea!=null&&(!savedArea.equals(now.get("focus-area-type"))||pointChanged)){
            int sx=number(saved.get("focus-point-x")),sy=number(saved.get("focus-point-y"));
            // Sony keeps the flexible-spot point while another area is active;
            // put it back first so the native camera's next flexible spot is
            // where the user left it.
            if(pointChanged&&!FLEX.equals(savedArea)&&pointAvailable)
                try{driver.setFocusArea(FLEX,sx,sy);}catch(Exception e){log("focus_point_restore_skipped",root(e).toString());}
            driver.setFocusArea(savedArea,sx,sy);
            Map<String,String> after=driver.read();
            if(!savedArea.equals(after.get("focus-area-type")))differs.append("focus-area-type ");
            log("focus_close","area="+after.get("focus-area-type")+" point="+after.get("focus-point-x")+","+after.get("focus-point-y")
                +" saved="+savedArea+" "+sx+","+sy);
        }
        if(differs.length()>0)throw new IOException("focus restore differs: "+differs.toString().trim());
    }
}
