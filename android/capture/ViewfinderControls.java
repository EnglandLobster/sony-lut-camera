package io.github.englandlobster.lutcamera;

import java.util.ArrayList;
import java.util.List;

/** Viewfinder key routing and list state for the manual controls. Pure: the
 * Activity decides when controls may run (capture gate, preview state) and
 * forwards rotary inputs and UiKeys actions here.
 *
 * Live view: dials follow {@link ControlLayout}; → ISO list, ← white
 * balance list, ↓ focus/shooting settings, centre resets EV (or switches the
 * shared function on one-dial bodies). Lists: ↑↓/dials move, ←→ change a
 * setting row, centre applies, MENU closes. Every function is reachable with
 * keys all supported bodies have (wheel, four-way, centre, MENU). */
final class ViewfinderControls {
    interface Host {
        void changed();
        void hint(String text);
        void failed(String where,Exception error);
        /** A verified WB write: the preview baseline must follow it. */
        void whiteBalanceChanged();
    }
    enum Panel { NONE, ISO, WB, SETTINGS, POINT }
    private enum Row { POINT, AREA, FACE, MODE }

    private final ManualExposure exposure;
    private final FocusControls focus;
    private final ControlLayout layout;
    private boolean physicalDial;
    private final Host host;
    private Panel panel=Panel.NONE;
    private int selected;
    private boolean alternate;
    private final List<Row> rows=new ArrayList<Row>();

    ViewfinderControls(ManualExposure exposure,FocusControls focus,ControlLayout layout,boolean physicalDial,Host host){
        this.exposure=exposure;this.focus=focus;this.layout=layout;this.physicalDial=physicalDial;this.host=host;
    }

    /** A PASM dial event arrived on a body whose probe did not report one. */
    void physicalDial(boolean value){physicalDial=value;}
    Panel panel(){return panel;}
    boolean panelOpen(){return panel!=Panel.NONE;}
    void closePanel(){
        if(panel==Panel.POINT&&focus!=null)focus.cancelPoint();
        if(panel!=Panel.NONE){panel=Panel.NONE;host.changed();}
    }

    // ---- inputs -------------------------------------------------------------------
    /** One rotary detent (or a coalesced burst). */
    void rotate(ControlLayout.Input input,int steps){
        if(steps==0)return;
        try{
            switch(panel){
                case NONE:{
                    ControlLayout.Action action=layout.action(exposure.mode(),input,alternate);
                    boolean changed=exposure.step(action,steps);
                    if(!changed&&action==ControlLayout.Action.EV&&!exposure.evAvailable())host.hint("M 档固定 ISO 时不使用 EV");
                    host.changed();return;
                }
                case POINT:{
                    boolean horizontal=layout.role(input)==0;
                    if(focus.movePoint(horizontal?steps:0,horizontal?0:steps))host.changed();
                    return;
                }
                default:move(steps);
            }
        }catch(Exception e){host.failed("rotate_"+input,e);host.changed();}
    }
    /** @return true when consumed */
    boolean press(int action){
        try{
            switch(panel){
                case NONE:return live(action);
                case POINT:return point(action);
                case SETTINGS:return settings(action);
                default:return list(action);
            }
        }catch(Exception e){host.failed("key_"+panel,e);host.changed();return true;}
    }
    private boolean live(int action)throws Exception{
        if(action==UiKeyActions.RIGHT){open(Panel.ISO);return true;}
        if(action==UiKeyActions.LEFT){open(Panel.WB);return true;}
        if(action==UiKeyActions.DOWN){open(Panel.SETTINGS);return true;}
        if(action==UiKeyActions.OK){
            if(layout.shared(exposure.mode())){alternate=!alternate;host.hint(layout.hint(exposure.mode(),alternate));}
            else exposure.resetEv();
            host.changed();return true;
        }
        return false;
    }
    private void open(Panel next){
        if(next==Panel.SETTINGS){
            buildRows();
            if(rows.isEmpty()){host.hint("本机未提供可调的对焦设置");return;}
        }
        panel=next;
        if(next==Panel.ISO)selected=Math.max(0,exposure.isoValues().indexOf(Integer.valueOf(exposure.iso())));
        else if(next==Panel.WB)selected=Math.max(0,exposure.whiteBalanceValues().indexOf(exposure.whiteBalance()));
        else selected=0;
        host.changed();
    }
    private void buildRows(){
        rows.clear();
        if(focus!=null&&focus.pointAvailable())rows.add(Row.POINT);
        if(focus!=null&&focus.areas().size()>1)rows.add(Row.AREA);
        if(focus!=null&&focus.faceAvailable())rows.add(Row.FACE);
        if(!physicalDial)rows.add(Row.MODE);
    }
    private int size(){
        switch(panel){
            case ISO:return exposure.isoValues().size();
            case WB:return exposure.whiteBalanceValues().size();
            case SETTINGS:return rows.size();
            default:return 0;
        }
    }
    private void move(int steps){
        int count=size();if(count==0)return;
        int next=Math.max(0,Math.min(count-1,selected+steps));
        if(next!=selected){selected=next;host.changed();}
    }
    private boolean list(int action)throws Exception{
        if(action==UiKeyActions.UP||action==UiKeyActions.LEFT){move(-1);return true;}
        if(action==UiKeyActions.DOWN||action==UiKeyActions.RIGHT){move(1);return true;}
        if(action==UiKeyActions.BACK){closePanel();return true;}
        if(action==UiKeyActions.OK){
            Panel was=panel;panel=Panel.NONE;
            if(was==Panel.ISO)exposure.setIso(exposure.isoValues().get(selected).intValue());
            else{exposure.setWhiteBalance(exposure.whiteBalanceValues().get(selected));host.whiteBalanceChanged();}
            host.changed();return true;
        }
        return true;
    }
    private boolean settings(int action)throws Exception{
        if(action==UiKeyActions.UP){move(-1);return true;}
        if(action==UiKeyActions.DOWN){move(1);return true;}
        if(action==UiKeyActions.BACK){closePanel();return true;}
        Row row=rows.get(selected);
        if(action==UiKeyActions.OK&&row==Row.POINT){
            focus.beginPoint();panel=Panel.POINT;host.changed();return true;
        }
        int direction=action==UiKeyActions.LEFT?-1:action==UiKeyActions.RIGHT||action==UiKeyActions.OK?1:0;
        if(direction==0)return true;
        if(row==Row.AREA){
            List<String> areas=focus.areas();int i=areas.indexOf(focus.area());
            focus.setArea(areas.get(((i<0?0:i)+direction+areas.size())%areas.size()));
        }else if(row==Row.FACE)focus.setFace(!focus.face());
        else if(row==Row.MODE){
            ManualExposure.Mode[] modes=ManualExposure.Mode.values();int i=exposure.mode().ordinal();
            for(int n=0;n<modes.length;n++){
                i=(i+direction+modes.length)%modes.length;
                if(exposure.supports(modes[i])){exposure.selectMode(modes[i]);break;}
            }
            alternate=false;host.whiteBalanceChanged();
        }
        host.changed();return true;
    }
    private boolean point(int action)throws Exception{
        int dx=0,dy=0;
        if(action==UiKeyActions.UP)dy=-1;else if(action==UiKeyActions.DOWN)dy=1;
        else if(action==UiKeyActions.LEFT)dx=-1;else if(action==UiKeyActions.RIGHT)dx=1;
        if(dx!=0||dy!=0){if(focus.movePoint(dx,dy))host.changed();return true;}
        if(action==UiKeyActions.OK){focus.confirmPoint();panel=Panel.NONE;host.changed();return true;}
        if(action==UiKeyActions.BACK){focus.cancelPoint();panel=Panel.SETTINGS;host.changed();return true;}
        return true;
    }

    /** Physical PASM dial moved.
     * @param position Sony ISV_KEY_MODE_* code, or -1 when unreadable (then
     * the camera's own scene-mode readback is followed) */
    void dialChanged(int position){
        try{
            boolean changed=position<0?exposure.sync()
                :exposure.follow(ControlLayout.dialMode(position),ControlLayout.dialName(position));
            alternate=false;host.whiteBalanceChanged();
            String fallback=exposure.fallbackFrom();
            if(fallback!=null)host.hint("转盘档位 "+fallback+" 不可用 · 按 P 档拍摄");
            else if(changed)host.hint(modeName()+" · "+layout.hint(exposure.mode(),false));
        }catch(Exception e){host.failed("dial_sync",e);}
        host.changed();
    }
    /** Full press or half press: lists never cover a shot. */
    void shutter(){
        if(panel==Panel.POINT){
            // A shot never waits behind the editor: a refused point is dropped.
            try{focus.confirmPoint();}catch(Exception e){focus.cancelPoint();host.failed("af_point",e);}
            panel=Panel.NONE;host.changed();
        }
        else closePanel();
    }

    // ---- HUD model --------------------------------------------------------------
    String modeLabel(){return exposure.modeLabel();}
    String modeName(){
        switch(exposure.mode()){
            case A:return "A 光圈优先";
            case S:return "S 快门优先";
            case M:return "M 手动曝光";
            default:return "P 程序自动";
        }
    }
    String hint(){
        return layout.hint(exposure.mode(),alternate)+" · → ISO · ← 白平衡 · ↓ 对焦";
    }
    String isoText(int measured){
        int iso=exposure.iso();
        if(iso>0)return "ISO "+iso;
        return measured>0?"AUTO "+measured:"ISO AUTO";
    }
    String compensationText(){return exposure.compensationLabel();}
    String whiteBalanceText(){return ManualExposure.whiteBalanceLabel(exposure.whiteBalance());}
    String title(){
        switch(panel){
            case ISO:return "ISO";
            case WB:return "白平衡";
            case SETTINGS:return "对焦与拍摄设置";
            default:return null;
        }
    }
    String[] rowLabels(){
        switch(panel){
            case ISO:{
                List<Integer> v=exposure.isoValues();String[] out=new String[v.size()];
                for(int i=0;i<out.length;i++)out[i]=v.get(i).intValue()==0?"AUTO":Integer.toString(v.get(i).intValue());
                return out;
            }
            case WB:{
                List<String> v=exposure.whiteBalanceValues();String[] out=new String[v.size()];
                for(int i=0;i<out.length;i++)out[i]=ManualExposure.whiteBalanceLabel(v.get(i));
                return out;
            }
            case SETTINGS:{
                String[] out=new String[rows.size()];
                for(int i=0;i<out.length;i++){
                    Row r=rows.get(i);
                    out[i]=r==Row.POINT?"移动对焦点":r==Row.AREA?"对焦区域":r==Row.FACE?"人脸优先":"曝光模式";
                }
                return out;
            }
            default:return null;
        }
    }
    String[] rowValues(){
        if(panel!=Panel.SETTINGS)return null;
        String[] out=new String[rows.size()];
        for(int i=0;i<out.length;i++){
            Row r=rows.get(i);
            if(r==Row.POINT)out[i]="";
            else if(r==Row.AREA)out[i]=FocusControls.areaLabel(focus.area());
            else if(r==Row.FACE)out[i]=focus.face()?"开":"关";
            else out[i]=modeName();
        }
        return out;
    }
    int selected(){return selected;}
    String panelHint(){
        switch(panel){
            case ISO:case WB:return "↑↓ 选择 · 中键确定 · MENU 返回";
            case SETTINGS:return rows.isEmpty()?"":rows.get(selected)==Row.POINT?"中键移动对焦点 · MENU 返回":"←→ 更改 · MENU 返回";
            case POINT:return "方向键／拨轮移动 · 中键确定 · MENU 取消";
            default:return "";
        }
    }
    boolean afVisible(){return focus!=null&&focus.pointAvailable()&&(panel==Panel.POINT||focus.flexible());}
    boolean afEditing(){return panel==Panel.POINT;}
    int afX(){return focus==null?0:focus.x();}
    int afY(){return focus==null?0:focus.y();}
}
